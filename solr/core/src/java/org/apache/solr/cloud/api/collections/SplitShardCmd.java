/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud.api.collections;

import static org.apache.solr.common.cloud.ZkStateReader.COLLECTION_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.REPLICA_TYPE;
import static org.apache.solr.common.cloud.ZkStateReader.SHARD_ID_PROP;
import static org.apache.solr.common.params.CollectionAdminParams.FOLLOW_ALIASES;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.ADDREPLICA;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.CREATESHARD;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.DELETESHARD;
import static org.apache.solr.common.params.CommonAdminParams.ASYNC;
import static org.apache.solr.common.params.CommonAdminParams.NUM_SUB_SHARDS;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.cloud.DistribStateManager;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.cloud.VersionedData;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.cloud.DistributedClusterStateUpdater;
import org.apache.solr.cloud.Overseer;
import org.apache.solr.cloud.api.collections.CollectionHandlingUtils.ShardRequestTracker;
import org.apache.solr.cloud.overseer.OverseerAction;
import org.apache.solr.common.LinkedHashMapWriter;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.CompositeIdRouter;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocRouter;
import org.apache.solr.common.cloud.PlainIdRouter;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ReplicaCount;
import org.apache.solr.common.cloud.ReplicaPosition;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.CommonAdminParams;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.Utils;
import org.apache.solr.handler.component.ShardHandler;
import org.apache.solr.update.SolrIndexSplitter;
import org.apache.solr.util.RTimerTree;
import org.apache.solr.util.TestInjection;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SolrCloud logic for splitting a shard. It's complicated! See {@code split()} below. */
public class SplitShardCmd implements CollApiCmds.CollectionApiCommand {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final int MIN_NUM_SUB_SHARDS = 2;
  // This is an arbitrary number that seems reasonable at this time.
  private static final int MAX_NUM_SUB_SHARDS = 8;
  private static final int DEFAULT_NUM_SUB_SHARDS = 2;

  public static final String SHARDSPLIT_CHECKDISKSPACE_ENABLED =
      "solr.shardSplit.checkDiskSpace.enabled";

  private final CollectionCommandContext ccc;

  public SplitShardCmd(CollectionCommandContext ccc) {
    this.ccc = ccc;
  }

  @Override
  public void call(ClusterState state, ZkNodeProps message, NamedList<Object> results)
      throws Exception {
    split(state, message, results);
  }

  /**
   * Shard splits start here and make additional requests to the host of the parent shard. The
   * sequence of requests is as follows:
   *
   * <ul>
   *   <li>1. Verify that there is enough disk space to create sub-shards.
   *   <li>2. If splitByPrefix is true, make request to get prefix ranges.
   *   <li>3. If this split was attempted previously and there are lingering sub-shards, delete
   *       them.
   *   <li>4. Create sub-shards in CONSTRUCTION state.
   *   <li>5. Add an initial replica to each sub-shard.
   *   <li>6. Request that parent shard wait for children to become ACTIVE.
   *   <li>7. Execute split: either LINK or REWRITE.
   *   <li>8. Apply buffered updates to the sub-shards so they are up-to-date with parent.
   *   <li>9. Determine node placement for additional replicas (but do not create yet).
   *   <li>10. If replicationFactor is more than 1, set shard state for sub-shards to RECOVERY; else
   *       mark ACTIVE.
   *   <li>11. Create additional replicas of sub-shards.
   * </ul>
   *
   * <br>
   *
   * <p>There is a shard split doc (dev-docs/shard-split/shard-split.adoc) on how shard split works;
   * illustrated with diagrams.
   */
  public boolean split(ClusterState clusterState, ZkNodeProps message, NamedList<Object> results)
      throws Exception {
    final String asyncId = message.getStr(ASYNC);

    boolean waitForFinalState = message.getBool(CommonAdminParams.WAIT_FOR_FINAL_STATE, false);
    String methodStr =
        message.getStr(
            CommonAdminParams.SPLIT_METHOD, SolrIndexSplitter.SplitMethod.REWRITE.toLower());
    SolrIndexSplitter.SplitMethod splitMethod = SolrIndexSplitter.SplitMethod.get(methodStr);
    if (splitMethod == null) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "Unknown value '" + CommonAdminParams.SPLIT_METHOD + ": " + methodStr);
    }
    boolean withTiming = message.getBool(CommonParams.TIMING, false);

    String extCollectionName = message.getStr(CoreAdminParams.COLLECTION);

    boolean followAliases = message.getBool(FOLLOW_ALIASES, false);
    String collectionName;
    if (followAliases) {
      collectionName =
          ccc.getSolrCloudManager().getClusterStateProvider().resolveSimpleAlias(extCollectionName);
    } else {
      collectionName = extCollectionName;
    }

    log.debug("Split shard invoked: {}", message);
    ZkStateReader zkStateReader = ccc.getZkStateReader();
    zkStateReader.forceUpdateCollection(collectionName);
    AtomicReference<String> slice = new AtomicReference<>();
    slice.set(message.getStr(ZkStateReader.SHARD_ID_PROP));
    Set<String> offlineSlices = new HashSet<>();
    RTimerTree timings = new RTimerTree();

    String splitKey = message.getStr("split.key");
    DocCollection collection = clusterState.getCollection(collectionName);

    // verify that parent shard is active; if not, throw exception
    Slice parentSlice = getParentSlice(clusterState, collectionName, slice, splitKey);
    if (parentSlice.getState() != Slice.State.ACTIVE) {
      throw new SolrException(
          SolrException.ErrorCode.INVALID_STATE,
          "Parent slice is not active: "
              + collectionName
              + "/ "
              + parentSlice.getName()
              + ", state="
              + parentSlice.getState());
    }

    // find the leader for the parent shard
    Replica parentShardLeader;
    try {
      parentShardLeader = zkStateReader.getLeaderRetry(collectionName, slice.get(), 10000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Interrupted.");
    }

    RTimerTree t;
    if (ccc.getCoreContainer().getNodeConfig().getMetricsConfig().isEnabled()) {
      // check disk space for shard split
      if (Boolean.parseBoolean(System.getProperty(SHARDSPLIT_CHECKDISKSPACE_ENABLED, "true"))) {
        // 1. verify that there is enough space on disk to create sub-shards
        log.debug(
            "SplitShardCmd: verify that there is enough space on disk to create sub-shards for slice: {}",
            parentShardLeader);
        t = timings.sub("checkDiskSpace");
        checkDiskSpace(
            collectionName, slice.get(), parentShardLeader, splitMethod, ccc.getSolrCloudManager());
        t.stop();
      }
    }

    // let's record the ephemeralOwner of the parent leader node
    Stat leaderZnodeStat =
        zkStateReader
            .getZkClient()
            .exists(
                ZkStateReader.LIVE_NODES_ZKNODE + "/" + parentShardLeader.getNodeName(),
                null,
                true);
    if (leaderZnodeStat == null) {
      // we just got to know the leader but its live node is gone already!
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "The shard leader node: " + parentShardLeader.getNodeName() + " is not live anymore!");
    }

    List<DocRouter.Range> subRanges = new ArrayList<>();
    List<String> subSlices = new ArrayList<>();
    List<String> subShardNames = new ArrayList<>();

    // reproduce the currently existing number of replicas per type
    ReplicaCount numReplicas = ReplicaCount.empty();
    parentSlice.getReplicas().forEach(r -> numReplicas.increment(r.getType()));
    int repFactor = numReplicas.total();

    boolean success = false;
    try {
      // type of the first subreplica will be the same as leader
      Replica.Type leaderReplicaType = parentShardLeader.getType();
      // verify that we indeed have the right number of correct replica types
      if (numReplicas.get(leaderReplicaType) < 1) {
        throw new SolrException(
            SolrException.ErrorCode.SERVER_ERROR,
            "aborting split - inconsistent replica types in collection "
                + collectionName
                + ": "
                + numReplicas
                + ", shard leader type is "
                + leaderReplicaType);
      }

      // check for the lock
      if (!lockForSplit(ccc.getSolrCloudManager(), collectionName, parentSlice.getName())) {
        // mark as success to avoid clearing the lock in the "finally" block
        success = true;
        throw new SolrException(
            SolrException.ErrorCode.INVALID_STATE,
            "Can't lock parent slice for splitting (another split operation running?): "
                + collectionName
                + "/"
                + parentSlice.getName());
      }

      List<Map<String, Object>> replicas = new ArrayList<>((repFactor - 1) * 2);

      ShardHandler shardHandler = ccc.newShardHandler();

      // 2. if split request has splitByPrefix set to true, make request to SplitOp to get prefix
      // ranges of sub-shards
      if (message.getBool(CommonAdminParams.SPLIT_BY_PREFIX, false)) {
        t = timings.sub("getRanges");

        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set(CoreAdminParams.ACTION, CoreAdminParams.CoreAdminAction.SPLIT.toString());
        params.set(CoreAdminParams.GET_RANGES, "true");
        params.set(CommonAdminParams.SPLIT_METHOD, splitMethod.toLower());
        params.set(CoreAdminParams.CORE, parentShardLeader.getStr("core"));
        // only 2 sub-shards are currently supported
        // int numSubShards = message.getInt(NUM_SUB_SHARDS, DEFAULT_NUM_SUB_SHARDS);
        // params.set(NUM_SUB_SHARDS, Integer.toString(numSubShards));

        {
          final ShardRequestTracker shardRequestTracker =
              CollectionHandlingUtils.syncRequestTracker(ccc);
          shardRequestTracker.sendShardRequest(
              parentShardLeader.getNodeName(), params, shardHandler);
          SimpleOrderedMap<Object> getRangesResults = new SimpleOrderedMap<>();
          String msgOnError = "SPLITSHARD failed to invoke SPLIT.getRanges core admin command";
          shardRequestTracker.processResponses(getRangesResults, shardHandler, true, msgOnError);

          // Extract the recommended splits from the shard response (if it exists)
          // example response:
          // getRangesResults={success={127.0.0.1:62086_solr={responseHeader={status=0,QTime=1},ranges=10-20,3a-3f}}}
          NamedList<?> successes = (NamedList<?>) getRangesResults.get("success");
          if (successes != null && successes.size() > 0) {
            NamedList<?> shardRsp = (NamedList<?>) successes.getVal(0);
            String splits = (String) shardRsp.get(CoreAdminParams.RANGES);
            if (splits != null) {
              log.info(
                  "Resulting split ranges to be used: {} slice={} leader={}",
                  splits,
                  slice,
                  parentShardLeader);
              // change the message to use the recommended split ranges
              message = message.plus(CoreAdminParams.RANGES, splits);
            }
          }
        }

        t.stop();
      }

      t = timings.sub("fillRanges");

      String rangesStr =
          fillRanges(
              ccc.getSolrCloudManager(),
              message,
              collection,
              parentSlice,
              subRanges,
              subSlices,
              subShardNames,
              leaderReplicaType);
      t.stop();

      // 3. if this shard has attempted a split before and failed, there will be lingering INACTIVE
      // sub-shards.  Clean these up before proceeding
      boolean oldShardsDeleted = false;
      for (String subSlice : subSlices) {
        Slice oSlice = collection.getSlice(subSlice);
        if (oSlice != null) {
          final Slice.State state = oSlice.getState();
          if (state == Slice.State.ACTIVE) {
            throw new SolrException(
                SolrException.ErrorCode.BAD_REQUEST,
                "Sub-shard: " + subSlice + " exists in active state. Aborting split shard.");
          } else {
            // delete the shards
            log.info("Sub-shard: {} already exists therefore requesting its deletion", subSlice);
            Map<String, Object> propMap = new HashMap<>();
            propMap.put(Overseer.QUEUE_OPERATION, DELETESHARD.toLower());
            propMap.put(COLLECTION_PROP, collectionName);
            propMap.put(SHARD_ID_PROP, subSlice);
            ZkNodeProps m = new ZkNodeProps(propMap);
            try {
              new DeleteShardCmd(ccc).call(clusterState, m, new NamedList<>());
            } catch (Exception e) {
              throw new SolrException(
                  SolrException.ErrorCode.SERVER_ERROR,
                  "Unable to delete already existing sub shard: " + subSlice,
                  e);
            }

            oldShardsDeleted = true;
          }
        }
      }

      if (oldShardsDeleted) {
        // refresh the locally cached cluster state
        // we know we have the latest because otherwise deleteshard would have failed
        clusterState = zkStateReader.getClusterState();
      }

      // 4. create the child sub-shards in CONSTRUCTION state
      String nodeName = parentShardLeader.getNodeName();

      t = timings.sub("createSubSlicesAndLeadersInState");
      for (int i = 0; i < subRanges.size(); i++) {
        String subSlice = subSlices.get(i);
        String subShardName = subShardNames.get(i);
        DocRouter.Range subRange = subRanges.get(i);

        log.debug("Creating slice {} of collection {} on {}", subSlice, collectionName, nodeName);

        LinkedHashMapWriter<Object> propMap = new LinkedHashMapWriter<>();
        propMap.put(Overseer.QUEUE_OPERATION, CREATESHARD.toLower());
        propMap.put(ZkStateReader.SHARD_ID_PROP, subSlice);
        propMap.put(ZkStateReader.COLLECTION_PROP, collectionName);
        propMap.put(ZkStateReader.SHARD_RANGE_PROP, subRange.toString());
        propMap.put(ZkStateReader.SHARD_STATE_PROP, Slice.State.CONSTRUCTION.toString());
        propMap.put(ZkStateReader.SHARD_PARENT_PROP, parentSlice.getName());
        propMap.put("shard_parent_node", nodeName);
        propMap.put("shard_parent_zk_session", leaderZnodeStat.getEphemeralOwner());

        if (ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
          ccc.getDistributedClusterStateUpdater()
              .doSingleStateUpdate(
                  DistributedClusterStateUpdater.MutatingCommand.CollectionCreateShard,
                  new ZkNodeProps((MapWriter) propMap),
                  ccc.getSolrCloudManager(),
                  ccc.getZkStateReader());
        } else {
          ccc.offerStateUpdate(propMap);
        }

        // wait until we are able to see the new shard in cluster state and refresh the local view
        // of the cluster state
        clusterState =
            CollectionHandlingUtils.waitForNewShard(
                collectionName, subSlice, ccc.getZkStateReader());

        // 5. and add the initial replica for each sub-shard
        log.debug(
            "Adding first replica {} as part of slice {} of collection {} on {}",
            subShardName,
            subSlice,
            collectionName,
            nodeName);
        propMap = new LinkedHashMapWriter<>();
        propMap.put(Overseer.QUEUE_OPERATION, ADDREPLICA.toLower());
        propMap.put(COLLECTION_PROP, collectionName);
        propMap.put(SHARD_ID_PROP, subSlice);
        propMap.put(REPLICA_TYPE, leaderReplicaType.toString());
        propMap.put("node", nodeName);
        propMap.put(CoreAdminParams.NAME, subShardName);
        propMap.put(CommonAdminParams.WAIT_FOR_FINAL_STATE, Boolean.toString(waitForFinalState));
        // copy over property params:
        for (String key : message.keySet()) {
          if (key.startsWith(CollectionAdminParams.PROPERTY_PREFIX)) {
            propMap.put(key, message.getStr(key));
          }
        }
        // add async param
        if (asyncId != null) {
          propMap.put(ASYNC, asyncId);
        }
        new AddReplicaCmd(ccc)
            .addReplica(clusterState, new ZkNodeProps((MapWriter) propMap), results, null);
      }

      {
        final ShardRequestTracker syncRequestTracker =
            CollectionHandlingUtils.syncRequestTracker(ccc);
        String msgOnError = "SPLITSHARD failed to create subshard leaders";
        syncRequestTracker.processResponses(results, shardHandler, true, msgOnError);
        handleFailureOnAsyncRequest(results, msgOnError);
      }
      t.stop();

      // 6. request that parent shard wait for children to become active
      t = timings.sub("waitForSubSliceLeadersAlive");
      {
        final ShardRequestTracker shardRequestTracker =
            CollectionHandlingUtils.asyncRequestTracker(asyncId, ccc);
        for (String subShardName : subShardNames) {
          // wait for parent leader to acknowledge the sub-shard core
          log.debug(
              "Asking parent leader to wait for: {} to be alive on: {}", subShardName, nodeName);
          String coreNodeName =
              CollectionHandlingUtils.waitForCoreNodeName(
                  collectionName, nodeName, subShardName, ccc.getZkStateReader());
          CoreAdminRequest.WaitForState cmd = new CoreAdminRequest.WaitForState();
          cmd.setCoreName(subShardName);
          cmd.setNodeName(nodeName);
          cmd.setCoreNodeName(coreNodeName);
          cmd.setState(Replica.State.ACTIVE);
          cmd.setCheckLive(true);
          cmd.setOnlyIfLeader(true);

          ModifiableSolrParams p = new ModifiableSolrParams(cmd.getParams());
          shardRequestTracker.sendShardRequest(nodeName, p, shardHandler);
        }

        String msgOnError = "SPLITSHARD timed out waiting for subshard leaders to come up";
        shardRequestTracker.processResponses(results, shardHandler, true, msgOnError);
        handleFailureOnAsyncRequest(results, msgOnError);
      }
      t.stop();

      log.debug(
          "Successfully created all sub-shards for collection {} parent shard: {} on: {}",
          collectionName,
          slice,
          parentShardLeader);

      if (log.isInfoEnabled()) {
        log.info(
            "Splitting shard {} as part of slice {} of collection {} on {}",
            parentShardLeader.getName(),
            slice,
            collectionName,
            parentShardLeader);
      }

      // 7. execute actual split
      ModifiableSolrParams params = new ModifiableSolrParams();
      params.set(CoreAdminParams.ACTION, CoreAdminParams.CoreAdminAction.SPLIT.toString());
      params.set(CommonAdminParams.SPLIT_METHOD, splitMethod.toLower());
      params.set(CoreAdminParams.CORE, parentShardLeader.getStr("core"));
      for (int i = 0; i < subShardNames.size(); i++) {
        String subShardName = subShardNames.get(i);
        params.add(CoreAdminParams.TARGET_CORE, subShardName);
      }
      params.set(CoreAdminParams.RANGES, rangesStr);

      t = timings.sub("splitParentCore");
      {
        final ShardRequestTracker shardRequestTracker =
            CollectionHandlingUtils.asyncRequestTracker(asyncId, ccc);
        shardRequestTracker.sendShardRequest(parentShardLeader.getNodeName(), params, shardHandler);

        String msgOnError = "SPLITSHARD failed to invoke SPLIT core admin command";
        shardRequestTracker.processResponses(results, shardHandler, true, msgOnError);
        handleFailureOnAsyncRequest(results, msgOnError);
      }
      t.stop();

      if (log.isDebugEnabled()) {
        log.debug("Index on shard: {} split into {} successfully", nodeName, subShardNames.size());
      }

      // 8. apply buffered updates on sub-shards
      t = timings.sub("applyBufferedUpdates");
      {
        final ShardRequestTracker shardRequestTracker =
            CollectionHandlingUtils.asyncRequestTracker(asyncId, ccc);

        for (int i = 0; i < subShardNames.size(); i++) {
          String subShardName = subShardNames.get(i);

          log.debug("Applying buffered updates on : {}", subShardName);

          params = new ModifiableSolrParams();
          params.set(
              CoreAdminParams.ACTION,
              CoreAdminParams.CoreAdminAction.REQUESTAPPLYUPDATES.toString());
          params.set(CoreAdminParams.NAME, subShardName);

          shardRequestTracker.sendShardRequest(nodeName, params, shardHandler);
        }

        String msgOnError =
            "SPLITSHARD failed while asking sub shard leaders to apply buffered updates";
        shardRequestTracker.processResponses(results, shardHandler, true, msgOnError);
        handleFailureOnAsyncRequest(results, msgOnError);
      }
      t.stop();

      log.debug("Successfully applied buffered updates on : {}", subShardNames);

      // TODO: change this to handle sharding a slice into > 2 sub-shards.

      // we have already created one subReplica for each subShard on the parent node.
      // identify locations for the remaining replicas
      numReplicas.decrement(leaderReplicaType);

      t = timings.sub("identifyNodesForReplicas");
      Assign.AssignRequest assignRequest =
          new Assign.AssignRequestBuilder()
              .forCollection(collectionName)
              .forShard(subSlices)
              .assignReplicas(numReplicas)
              .onNodes(
                  Assign.getLiveOrLiveAndCreateNodeSetList(
                      clusterState.getLiveNodes(),
                      message,
                      Utils.RANDOM,
                      ccc.getSolrCloudManager().getDistribStateManager()))
              .build();
      Assign.AssignStrategy assignStrategy = Assign.createAssignStrategy(ccc.getCoreContainer());
      List<ReplicaPosition> replicaPositions =
          assignStrategy.assign(ccc.getSolrCloudManager(), assignRequest);
      t.stop();

      t = timings.sub("createReplicaPlaceholders");
      final DistributedClusterStateUpdater.StateChangeRecorder scr;
      boolean hasRecordedDistributedUpdate = false;
      if (ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
        scr =
            ccc.getDistributedClusterStateUpdater()
                .createStateChangeRecorder(collectionName, false);
      } else {
        scr = null;
      }
      for (ReplicaPosition replicaPosition : replicaPositions) {
        String sliceName = replicaPosition.shard;
        String subShardNodeName = replicaPosition.node;
        String solrCoreName =
            Assign.buildSolrCoreName(
                collectionName, sliceName, replicaPosition.type, replicaPosition.index);

        log.debug(
            "Creating replica shard {} as part of slice {} of collection {} on {}",
            solrCoreName,
            sliceName,
            collectionName,
            subShardNodeName);

        // we first create all replicas in DOWN state without actually creating their cores in order
        // to avoid a race condition where Overseer may prematurely activate the new sub-slices (and
        // deactivate the parent slice) before all new replicas are added. This situation may lead
        // to a loss of performance because the new shards will be activated with possibly many
        // fewer replicas.
        ZkNodeProps props =
            new ZkNodeProps(
                Overseer.QUEUE_OPERATION,
                ADDREPLICA.toLower(),
                ZkStateReader.COLLECTION_PROP,
                collectionName,
                ZkStateReader.SHARD_ID_PROP,
                sliceName,
                ZkStateReader.CORE_NAME_PROP,
                solrCoreName,
                ZkStateReader.REPLICA_TYPE,
                replicaPosition.type.name(),
                ZkStateReader.STATE_PROP,
                Replica.State.DOWN.toString(),
                ZkStateReader.NODE_NAME_PROP,
                subShardNodeName,
                ZkStateReader.BASE_URL_PROP,
                zkStateReader.getBaseUrlForNodeName(subShardNodeName),
                CommonAdminParams.WAIT_FOR_FINAL_STATE,
                Boolean.toString(waitForFinalState));
        if (ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
          hasRecordedDistributedUpdate = true;
          scr.record(DistributedClusterStateUpdater.MutatingCommand.SliceAddReplica, props);
        } else {
          ccc.offerStateUpdate(props);
        }

        HashMap<String, Object> propMap = new HashMap<>();
        propMap.put(Overseer.QUEUE_OPERATION, ADDREPLICA.toLower());
        propMap.put(COLLECTION_PROP, collectionName);
        propMap.put(SHARD_ID_PROP, sliceName);
        propMap.put(REPLICA_TYPE, replicaPosition.type.name());
        propMap.put("node", subShardNodeName);
        propMap.put(CoreAdminParams.NAME, solrCoreName);
        // copy over property params:
        for (String key : message.keySet()) {
          if (key.startsWith(CollectionAdminParams.PROPERTY_PREFIX)) {
            propMap.put(key, message.getStr(key));
          }
        }
        // add async param
        if (asyncId != null) {
          propMap.put(ASYNC, asyncId);
        }
        // special flag param to instruct addReplica not to create the replica in cluster state
        // again
        propMap.put(CollectionHandlingUtils.SKIP_CREATE_REPLICA_IN_CLUSTER_STATE, "true");

        propMap.put(CommonAdminParams.WAIT_FOR_FINAL_STATE, Boolean.toString(waitForFinalState));

        replicas.add(propMap);
      }
      if (hasRecordedDistributedUpdate
          && ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
        // Actually add the replicas to the collection state. Note that when Overseer takes care of
        // the state, there is no wait here for the state update to be visible, but with distributed
        // state update done synchronously we wait (we could in theory create a thread and have it
        // do the work if we REALLY needed, but we likely don't).
        scr.executeStateUpdates(ccc.getSolrCloudManager(), ccc.getZkStateReader());
      }
      t.stop();
      assert TestInjection.injectSplitFailureBeforeReplicaCreation();

      long ephemeralOwner = leaderZnodeStat.getEphemeralOwner();
      // compare against the ephemeralOwner of the parent leader node
      leaderZnodeStat =
          zkStateReader
              .getZkClient()
              .exists(
                  ZkStateReader.LIVE_NODES_ZKNODE + "/" + parentShardLeader.getNodeName(),
                  null,
                  true);
      if (leaderZnodeStat == null || ephemeralOwner != leaderZnodeStat.getEphemeralOwner()) {
        // put sub-shards in recovery_failed state

        Map<String, Object> propMap = new HashMap<>();
        propMap.put(Overseer.QUEUE_OPERATION, OverseerAction.UPDATESHARDSTATE.toLower());
        for (String subSlice : subSlices) {
          propMap.put(subSlice, Slice.State.RECOVERY_FAILED.toString());
        }
        propMap.put(ZkStateReader.COLLECTION_PROP, collectionName);
        ZkNodeProps m = new ZkNodeProps(propMap);
        if (ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
          ccc.getDistributedClusterStateUpdater()
              .doSingleStateUpdate(
                  DistributedClusterStateUpdater.MutatingCommand.SliceUpdateShardState,
                  m,
                  ccc.getSolrCloudManager(),
                  ccc.getZkStateReader());
        } else {
          ccc.offerStateUpdate(m);
        }

        if (leaderZnodeStat == null) {
          // the leader is not live anymore, fail the split!
          throw new SolrException(
              SolrException.ErrorCode.SERVER_ERROR,
              "The shard leader node: "
                  + parentShardLeader.getNodeName()
                  + " is not live anymore!");
        } else {
          // there's a new leader, fail the split!
          throw new SolrException(
              SolrException.ErrorCode.SERVER_ERROR,
              "The zk session id for the shard leader node: "
                  + parentShardLeader.getNodeName()
                  + " has changed from "
                  + ephemeralOwner
                  + " to "
                  + leaderZnodeStat.getEphemeralOwner()
                  + ". This can cause data loss so we must abort the split");
        }
      }

      // we must set the slice state into recovery before actually creating the replica cores
      // this ensures that the logic inside ReplicaMutator to update sub-shard state to 'active'
      // always gets a chance to execute. See SOLR-7673

      // 10. if replicationFactor > 1, set shard state for sub-shards to RECOVERY; otherwise mark
      // ACTIVE
      if (repFactor == 1) {
        // A commit is needed so that documents are visible when the sub-shard replicas come up
        // (Note: This commit used to be after the state switch, but was brought here before the
        // state switch as per SOLR-13945 so that sub shards don't come up empty, momentarily, after
        // being marked active)
        t = timings.sub("finalCommit");
        CollectionHandlingUtils.commit(results, slice.get(), parentShardLeader);
        t.stop();
        // switch sub shard states to 'active'
        log.info("Replication factor is 1 so switching shard states");
        Map<String, Object> propMap = new HashMap<>();
        propMap.put(Overseer.QUEUE_OPERATION, OverseerAction.UPDATESHARDSTATE.toLower());
        propMap.put(slice.get(), Slice.State.INACTIVE.toString());
        for (String subSlice : subSlices) {
          propMap.put(subSlice, Slice.State.ACTIVE.toString());
        }
        propMap.put(ZkStateReader.COLLECTION_PROP, collectionName);
        ZkNodeProps m = new ZkNodeProps(propMap);
        if (ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
          ccc.getDistributedClusterStateUpdater()
              .doSingleStateUpdate(
                  DistributedClusterStateUpdater.MutatingCommand.SliceUpdateShardState,
                  m,
                  ccc.getSolrCloudManager(),
                  ccc.getZkStateReader());
        } else {
          ccc.offerStateUpdate(m);
        }
      } else {
        log.debug("Requesting shard state be set to 'recovery' for sub-shards: {}", subSlices);
        Map<String, Object> propMap = new HashMap<>();
        propMap.put(Overseer.QUEUE_OPERATION, OverseerAction.UPDATESHARDSTATE.toLower());
        for (String subSlice : subSlices) {
          propMap.put(subSlice, Slice.State.RECOVERY.toString());
        }
        propMap.put(ZkStateReader.COLLECTION_PROP, collectionName);
        ZkNodeProps m = new ZkNodeProps(propMap);
        if (ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
          ccc.getDistributedClusterStateUpdater()
              .doSingleStateUpdate(
                  DistributedClusterStateUpdater.MutatingCommand.SliceUpdateShardState,
                  m,
                  ccc.getSolrCloudManager(),
                  ccc.getZkStateReader());
        } else {
          ccc.offerStateUpdate(m);
        }
        // Wait for the sub-shards to change to the RECOVERY state before creating the replica
        // cores. Otherwise, there is a race condition and some recovery updates may be lost.
        zkStateReader.waitForState(
            collectionName,
            60,
            TimeUnit.SECONDS,
            (collectionState) -> {
              for (String subSlice : subSlices) {
                if (!collectionState.getSlice(subSlice).getState().equals(Slice.State.RECOVERY)) {
                  return false;
                }
              }
              return true;
            });
      }

      t = timings.sub("createCoresForReplicas");
      // 11. now actually create replica cores on sub shard nodes
      for (Map<String, Object> replica : replicas) {
        new AddReplicaCmd(ccc).addReplica(clusterState, new ZkNodeProps(replica), results, null);
      }

      assert TestInjection.injectSplitFailureAfterReplicaCreation();

      {
        final ShardRequestTracker syncRequestTracker =
            CollectionHandlingUtils.syncRequestTracker(ccc);
        String msgOnError = "SPLITSHARD failed to create subshard replicas";
        syncRequestTracker.processResponses(results, shardHandler, true, msgOnError);
        handleFailureOnAsyncRequest(results, msgOnError);
      }
      t.stop();

      log.info("Successfully created all replica shards for all sub-slices {}", subSlices);

      // The final commit was added in SOLR-4997 so that documents are visible
      // when the sub-shard replicas come up
      if (repFactor > 1) {
        t = timings.sub("finalCommit");
        CollectionHandlingUtils.commit(results, slice.get(), parentShardLeader);
        t.stop();
      }

      if (withTiming) {
        results.add(CommonParams.TIMING, timings.asNamedList());
      }
      success = true;
      // don't unlock the shard yet - only do this if the final switch-over in ReplicaMutator
      // succeeds (or fails)
      return true;
    } catch (SolrException e) {
      throw e;
    } catch (Exception e) {
      log.error(
          "Error executing split operation for collection: {} parent shard: {}",
          collectionName,
          slice,
          e);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, null, e);
    } finally {
      if (!success) {
        cleanupAfterFailure(
            zkStateReader, collectionName, parentSlice.getName(), subSlices, offlineSlices);
        unlockForSplit(ccc.getSolrCloudManager(), collectionName, parentSlice.getName());
      }
    }
  }

  /**
   * In case of async requests, the ShardRequestTracker's processResponses() does not abort on
   * failure (as it should). Handling this here temporarily for now.
   */
  private void handleFailureOnAsyncRequest(NamedList<?> results, String msgOnError) {
    Object splitResultFailure = results.get("failure");
    if (splitResultFailure != null) {
      throw new SolrException(ErrorCode.SERVER_ERROR, msgOnError);
    }
  }

  // public and static to facilitate reuse in the simulation framework and in tests
  public static void checkDiskSpace(
      String collection,
      String shard,
      Replica parentShardLeader,
      SolrIndexSplitter.SplitMethod method,
      SolrCloudManager cloudManager)
      throws Exception {

    // check that enough disk space is available on the parent leader node
    // otherwise the actual index splitting will always fail

    String replicaName = Utils.parseMetricsReplicaName(collection, parentShardLeader.getCoreName());
    String indexSizeMetricName =
        "solr.core." + collection + "." + shard + "." + replicaName + ":INDEX.sizeInBytes";
    String freeDiskSpaceMetricName = "solr.node:CONTAINER.fs.usableSpace";

    ModifiableSolrParams params =
        new ModifiableSolrParams()
            .add("key", indexSizeMetricName)
            .add("key", freeDiskSpaceMetricName);
    SolrResponse rsp =
        new GenericSolrRequest(
                SolrRequest.METHOD.GET, "/admin/metrics", SolrRequest.SolrRequestType.ADMIN, params)
            .process(cloudManager.getSolrClient());

    Number size = (Number) rsp.getResponse()._get(List.of("metrics", indexSizeMetricName), null);
    if (size == null) {
      log.warn("cannot verify information for parent shard leader");
      return;
    }
    double indexSize = size.doubleValue();

    Number freeSize =
        (Number) rsp.getResponse()._get(List.of("metrics", freeDiskSpaceMetricName), null);
    if (freeSize == null) {
      log.warn("missing node disk space information for parent shard leader");
      return;
    }

    // 100% more for REWRITE, 5% more for LINK
    double neededSpace =
        method == SolrIndexSplitter.SplitMethod.REWRITE ? 2.0 * indexSize : 1.05 * indexSize;
    if (freeSize.doubleValue() < neededSpace) {
      throw new SolrException(
          SolrException.ErrorCode.SERVER_ERROR,
          "not enough free disk space to perform index split on node "
              + parentShardLeader.getNodeName()
              + ", required: "
              + neededSpace
              + ", available: "
              + freeSize);
    }
  }

  private void cleanupAfterFailure(
      ZkStateReader zkStateReader,
      String collectionName,
      String parentShard,
      List<String> subSlices,
      Set<String> offlineSlices) {
    log.info("Cleaning up after a failed split of {}/{}", collectionName, parentShard);
    // get the latest state
    try {
      zkStateReader.forceUpdateCollection(collectionName);
    } catch (KeeperException | InterruptedException e) {
      log.warn(
          "Cleanup failed after failed split of {}/{} : (force update collection)",
          collectionName,
          parentShard,
          e);
      return;
    }
    ClusterState clusterState = zkStateReader.getClusterState();
    DocCollection coll = clusterState.getCollectionOrNull(collectionName);

    if (coll == null) { // may have been deleted
      return;
    }

    // If parent is inactive and all sub shards are active, then rolling back
    // to make the parent active again will cause data loss.
    if (coll.getSlice(parentShard).getState() == Slice.State.INACTIVE) {
      boolean allSubSlicesActive = true;
      for (String sub : subSlices) {
        if (coll.getSlice(sub).getState() != Slice.State.ACTIVE) {
          allSubSlicesActive = false;
          break;
        }
      }
      if (allSubSlicesActive) {
        return;
      }
    }

    // set already created sub shards states to CONSTRUCTION - this prevents them
    // from entering into RECOVERY or ACTIVE (SOLR-9455)
    final Map<String, Object> propMap = new HashMap<>();
    boolean sendUpdateState = false;
    propMap.put(Overseer.QUEUE_OPERATION, OverseerAction.UPDATESHARDSTATE.toLower());
    propMap.put(ZkStateReader.COLLECTION_PROP, collectionName);
    for (Slice s : coll.getSlices()) {
      if (!subSlices.contains(s.getName())) {
        continue;
      }
      propMap.put(s.getName(), Slice.State.CONSTRUCTION.toString());
      sendUpdateState = true;
    }

    // if parent is inactive activate it again
    Slice parentSlice = coll.getSlice(parentShard);
    if (parentSlice.getState() == Slice.State.INACTIVE) {
      sendUpdateState = true;
      propMap.put(parentShard, Slice.State.ACTIVE.toString());
    }
    // plus any other previously deactivated slices
    for (String sliceName : offlineSlices) {
      propMap.put(sliceName, Slice.State.ACTIVE.toString());
      sendUpdateState = true;
    }

    if (sendUpdateState) {
      try {
        ZkNodeProps m = new ZkNodeProps(propMap);
        if (ccc.getDistributedClusterStateUpdater().isDistributedStateUpdate()) {
          ccc.getDistributedClusterStateUpdater()
              .doSingleStateUpdate(
                  DistributedClusterStateUpdater.MutatingCommand.SliceUpdateShardState,
                  m,
                  ccc.getSolrCloudManager(),
                  ccc.getZkStateReader());
        } else {
          ccc.offerStateUpdate(m);
        }
      } catch (Exception e) {
        // don't give up yet - just log the error, we may still be able to clean up
        log.warn(
            "Cleanup failed after failed split of {}/{}: (slice state changes)",
            collectionName,
            parentShard,
            e);
      }
    }

    // delete existing subShards
    for (String subSlice : subSlices) {
      Slice s = coll.getSlice(subSlice);
      if (s == null) {
        continue;
      }
      log.debug("- sub-shard: {} exists therefore requesting its deletion", subSlice);
      HashMap<String, Object> props = new HashMap<>();
      props.put(Overseer.QUEUE_OPERATION, DELETESHARD.toLower());
      props.put(COLLECTION_PROP, collectionName);
      props.put(SHARD_ID_PROP, subSlice);
      ZkNodeProps m = new ZkNodeProps(props);
      try {
        new DeleteShardCmd(ccc).call(clusterState, m, new NamedList<Object>());
      } catch (Exception e) {
        log.warn(
            "Cleanup failed after failed split of {}/{} : (deleting existing sub shard{})",
            collectionName,
            parentShard,
            subSlice,
            e);
      }
    }
  }

  public static Slice getParentSlice(
      ClusterState clusterState,
      String collectionName,
      AtomicReference<String> slice,
      String splitKey) {
    DocCollection collection = clusterState.getCollection(collectionName);
    DocRouter router = collection.getRouter() != null ? collection.getRouter() : DocRouter.DEFAULT;

    Slice parentSlice;

    if (slice.get() == null) {
      if (router instanceof CompositeIdRouter) {
        Collection<Slice> searchSlices =
            router.getSearchSlicesSingle(splitKey, new ModifiableSolrParams(), collection);
        if (searchSlices.isEmpty()) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST,
              "Unable to find an active shard for split.key: " + splitKey);
        }
        if (searchSlices.size() > 1) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST,
              "Splitting a split.key: "
                  + splitKey
                  + " which spans multiple shards is not supported");
        }
        parentSlice = searchSlices.iterator().next();
        slice.set(parentSlice.getName());
        log.info("Split by route.key: {}, parent shard is: {} ", splitKey, slice);
      } else {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST,
            "Split by route key can only be used with CompositeIdRouter or subclass. Found router: "
                + router.getClass().getName());
      }
    } else {
      parentSlice = collection.getSlice(slice.get());
    }

    if (parentSlice == null) {
      // no chance of the collection being null because ClusterState#getCollection(String) would
      // have thrown an exception already
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST, "No shard with the specified name exists: " + slice);
    }
    return parentSlice;
  }

  public static String fillRanges(
      SolrCloudManager cloudManager,
      ZkNodeProps message,
      DocCollection collection,
      Slice parentSlice,
      List<DocRouter.Range> subRanges,
      List<String> subSlices,
      List<String> subShardNames,
      Replica.Type replicaType) {
    String splitKey = message.getStr("split.key");
    String rangesStr = message.getStr(CoreAdminParams.RANGES);
    String fuzzStr = message.getStr(CommonAdminParams.SPLIT_FUZZ, "0");
    float fuzz = 0.0f;
    try {
      fuzz = Float.parseFloat(fuzzStr);
    } catch (Exception e) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST, "Invalid numeric value of 'fuzz': " + fuzzStr);
    }

    DocRouter.Range range = parentSlice.getRange();
    if (range == null) {
      range = new PlainIdRouter().fullRange();
    }
    DocRouter router = collection.getRouter() != null ? collection.getRouter() : DocRouter.DEFAULT;
    if (rangesStr != null) {
      String[] ranges = rangesStr.split(",");
      if (ranges.length == 0 || ranges.length == 1) {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST,
            "There must be at least two ranges specified to split a shard");
      } else {
        for (int i = 0; i < ranges.length; i++) {
          String r = ranges[i];
          try {
            subRanges.add(DocRouter.DEFAULT.fromString(r));
          } catch (Exception e) {
            throw new SolrException(
                SolrException.ErrorCode.BAD_REQUEST,
                "Exception in parsing hexadecimal hash range: " + r,
                e);
          }
          if (!subRanges.get(i).isSubsetOf(range)) {
            throw new SolrException(
                SolrException.ErrorCode.BAD_REQUEST,
                "Specified hash range: "
                    + r
                    + " is not a subset of parent shard's range: "
                    + range.toString());
          }
        }
        List<DocRouter.Range> temp = new ArrayList<>(subRanges); // copy to preserve original order
        Collections.sort(temp);
        if (!range.equals(new DocRouter.Range(temp.get(0).min, temp.get(temp.size() - 1).max))) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST,
              "Specified hash ranges: "
                  + rangesStr
                  + " do not cover the entire range of parent shard: "
                  + range);
        }
        for (int i = 1; i < temp.size(); i++) {
          if (temp.get(i - 1).max + 1 != temp.get(i).min) {
            throw new SolrException(
                SolrException.ErrorCode.BAD_REQUEST,
                "Specified hash ranges: "
                    + rangesStr
                    + " either overlap with each other or "
                    + "do not cover the entire range of parent shard: "
                    + range);
          }
        }
      }
    } else if (splitKey != null) {
      if (router instanceof CompositeIdRouter compositeIdRouter) {
        List<DocRouter.Range> tmpSubRanges = compositeIdRouter.partitionRangeByKey(splitKey, range);
        if (tmpSubRanges.size() == 1) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST,
              "The split.key: "
                  + splitKey
                  + " has a hash range that is exactly equal to hash range of shard: "
                  + parentSlice.getName());
        }
        for (DocRouter.Range subRange : tmpSubRanges) {
          if (subRange.min == subRange.max) {
            throw new SolrException(
                SolrException.ErrorCode.BAD_REQUEST,
                "The split.key: " + splitKey + " must be a compositeId");
          }
        }
        subRanges.addAll(tmpSubRanges);
        if (log.isInfoEnabled()) {
          log.info(
              "Partitioning parent shard {} range: {} yields: {}",
              parentSlice.getName(),
              parentSlice.getRange(),
              subRanges);
        }
        rangesStr = "";
        for (int i = 0; i < subRanges.size(); i++) {
          DocRouter.Range subRange = subRanges.get(i);
          rangesStr += subRange.toString();
          if (i < subRanges.size() - 1) rangesStr += ',';
        }
      }
    } else {
      int numSubShards = message.getInt(NUM_SUB_SHARDS, DEFAULT_NUM_SUB_SHARDS);
      log.info("{} set at: {}", NUM_SUB_SHARDS, numSubShards);

      if (numSubShards < MIN_NUM_SUB_SHARDS || numSubShards > MAX_NUM_SUB_SHARDS)
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST,
            "A shard can only be split into "
                + MIN_NUM_SUB_SHARDS
                + " to "
                + MAX_NUM_SUB_SHARDS
                + " subshards in one split request. Provided "
                + NUM_SUB_SHARDS
                + "="
                + numSubShards);
      subRanges.addAll(router.partitionRange(numSubShards, range, fuzz));
    }

    for (int i = 0; i < subRanges.size(); i++) {
      String subSlice = parentSlice.getName() + "_" + i;
      subSlices.add(subSlice);
      String subShardName =
          Assign.buildSolrCoreName(
              cloudManager.getDistribStateManager(), collection, subSlice, replicaType);
      subShardNames.add(subShardName);
    }
    return rangesStr;
  }

  public static boolean lockForSplit(SolrCloudManager cloudManager, String collection, String shard)
      throws Exception {
    String path = ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection + "/" + shard + "-splitting";
    final DistribStateManager stateManager = cloudManager.getDistribStateManager();
    synchronized (stateManager) {
      if (stateManager.hasData(path)) {
        VersionedData vd = stateManager.getData(path);
        return false;
      }
      Map<String, Object> map = new HashMap<>();
      map.put(
          ZkStateReader.STATE_TIMESTAMP_PROP,
          String.valueOf(cloudManager.getTimeSource().getEpochTimeNs()));
      byte[] data = Utils.toJSON(map);
      try {
        cloudManager.getDistribStateManager().makePath(path, data, CreateMode.EPHEMERAL, true);
      } catch (Exception e) {
        throw new SolrException(
            SolrException.ErrorCode.INVALID_STATE,
            "Can't lock parent slice for splitting (another split operation running?): "
                + collection
                + "/"
                + shard,
            e);
      }
      return true;
    }
  }

  public static void unlockForSplit(SolrCloudManager cloudManager, String collection, String shard)
      throws Exception {
    if (shard != null) {
      String path =
          ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection + "/" + shard + "-splitting";
      cloudManager.getDistribStateManager().removeRecursively(path, true, true);
    } else {
      String path = ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection;
      try {
        List<String> names = cloudManager.getDistribStateManager().listData(path);
        for (String name : names) {
          if (name.endsWith("-splitting")) {
            try {
              cloudManager.getDistribStateManager().removeData(path + "/" + name, -1);
            } catch (NoSuchElementException nse) {
              // ignore
            }
          }
        }
      } catch (NoSuchElementException nse) {
        // ignore
      }
    }
  }
}
