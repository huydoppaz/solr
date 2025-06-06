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
package org.apache.solr.cloud;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudLegacySolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.CollectionStatePredicate;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.SolrCore;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.update.UpdateLog;
import org.apache.solr.util.LogLevel;
import org.apache.solr.util.TestInjection;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.KeeperException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@LogLevel(
    "org.apache.solr.handler.ReplicationHandler=DEBUG;org.apache.solr.handler.IndexFetcher=DEBUG")
public class TestPullReplica extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private String collectionName = null;
  private static final int REPLICATION_TIMEOUT_SECS = 30;

  private String suggestedCollectionName() {
    return (getTestClass().getSimpleName().replace("Test", "")
            + "_"
            + getSaferTestName().split(" ")[0])
        .replaceAll("(.)(\\p{Upper})", "$1_$2")
        .toLowerCase(Locale.ROOT);
  }

  @BeforeClass
  public static void createTestCluster() throws Exception {
    System.setProperty("cloudSolrClientMaxStaleRetries", "1");
    System.setProperty("zkReaderGetLeaderRetryTimeoutMs", "1000");

    configureCluster(2) // 2 + random().nextInt(3)
        .addConfig("conf", configset("cloud-minimal"))
        .configure();
  }

  @AfterClass
  public static void tearDownCluster() {
    System.clearProperty("cloudSolrClientMaxStaleRetries");
    System.clearProperty("zkReaderGetLeaderRetryTimeoutMs");
    TestInjection.reset();
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();

    collectionName = suggestedCollectionName();
    expectThrows(SolrException.class, () -> getCollectionState(collectionName));
  }

  @Override
  public void tearDown() throws Exception {
    for (JettySolrRunner jetty : cluster.getJettySolrRunners()) {
      if (!jetty.isRunning()) {
        log.warn("Jetty {} not running, probably some bad test. Starting it", jetty.getLocalPort());
        jetty.start();
      }
    }
    if (cluster.getSolrClient().getClusterState().getCollectionOrNull(collectionName) != null) {
      log.info("tearDown deleting collection");
      CollectionAdminRequest.deleteCollection(collectionName).process(cluster.getSolrClient());
      log.info("Collection deleted");
      waitForDeletion(collectionName);
    }
    super.tearDown();
  }

  // 2 times to make sure cleanup is complete, and we can create the same collection
  @Repeat(iterations = 30)
  public void testCreateDelete() throws Exception {
    try {
      switch (random().nextInt(3)) {
        case 0:
          // Sometimes use SolrJ
          CollectionAdminRequest.createCollection(collectionName, "conf", 2, 1, 0, 3)
              .process(cluster.getSolrClient());
          break;
        case 1:
          // Sometimes use v1 API
          String url =
              String.format(
                  Locale.ROOT,
                  "%s/admin/collections?action=CREATE&name=%s&collection.configName=%s&numShards=%s&pullReplicas=%s",
                  cluster.getRandomJetty(random()).getBaseUrl(),
                  collectionName,
                  "conf",
                  2, // numShards
                  3); // pullReplicas
          // These options should all mean the same
          url = url + pickRandom("", "&nrtReplicas=1", "&replicationFactor=1");
          HttpGet createCollectionGet = new HttpGet(url);
          getHttpClient().execute(createCollectionGet);
          break;
        case 2:
          // Sometimes use V2 API
          url = cluster.getRandomJetty(random()).getBaseUrl().toString() + "/____v2/collections";
          String requestBody =
              String.format(
                  Locale.ROOT,
                  "{\"name\": \"%s\", \"config\": \"%s\", \"numShards\": %s, \"pullReplicas\": %s %s}",
                  collectionName,
                  "conf",
                  2, // numShards
                  3, // pullReplicas
                  pickRandom(
                      "",
                      ", \"nrtReplicas\": 1",
                      ", \"replicationFactor\": 1")); // These options should all mean the same
          HttpPost createCollectionPost = new HttpPost(url);
          createCollectionPost.setHeader("Content-type", "application/json");
          createCollectionPost.setEntity(new StringEntity(requestBody));
          HttpResponse httpResponse = getHttpClient().execute(createCollectionPost);
          assertEquals(200, httpResponse.getStatusLine().getStatusCode());
          break;
      }
      boolean reloaded = false;
      while (true) {
        DocCollection docCollection = getCollectionState(collectionName);
        assertNotNull(docCollection);
        assertEquals("Expecting 4 replicas per shard", 8, docCollection.getReplicas().size());
        assertEquals(
            "Expecting 6 pull replicas, 3 per shard",
            6,
            docCollection.getReplicas(EnumSet.of(Replica.Type.PULL)).size());
        assertEquals(
            "Expecting 2 writer replicas, one per shard",
            2,
            docCollection.getReplicas(EnumSet.of(Replica.Type.NRT)).size());
        for (Slice s : docCollection.getSlices()) {
          // read-only replicas can never become leaders
          assertNotSame(s.getLeader().getType(), Replica.Type.PULL);
          List<String> shardElectionNodes =
              cluster
                  .getZkClient()
                  .getChildren(
                      ZkStateReader.getShardLeadersElectPath(collectionName, s.getName()),
                      null,
                      true);
          assertEquals(
              "Unexpected election nodes for Shard: "
                  + s.getName()
                  + ": "
                  + Arrays.toString(shardElectionNodes.toArray()),
              1,
              shardElectionNodes.size());
        }
        assertUlogPresence(docCollection);
        if (reloaded) {
          break;
        } else {
          // reload
          CollectionAdminResponse response =
              CollectionAdminRequest.reloadCollection(collectionName)
                  .process(cluster.getSolrClient());
          assertEquals(0, response.getStatus());
          reloaded = true;
        }
      }
    } finally {
      zkClient().printLayoutToStream(System.out);
    }
  }

  /**
   * For some tests (when we want to check for <i>absence</i> of tlog dir), we need a standin for
   * the common case where <code>core.getUpdateHandler().getUpdateLog() == null</code>. This method
   * returns the actual tlog dir if an {@link UpdateLog} is configured on the core's {@link
   * org.apache.solr.update.UpdateHandler}; otherwise, falls back to the legacy behavior: if {@link
   * CoreDescriptor#getUlogDir()} is specified, returns the <code>tlog</code> subdirectory of that;
   * otherwise returns the <code>tlog</code> subdirectory within {@link SolrCore#getDataDir()}.
   * (NOTE: the last of these is by far the most common default location of the tlog directory).
   */
  static Path getHypotheticalTlogDir(SolrCore core) {
    String ulogDir;
    UpdateLog ulog = core.getUpdateHandler().getUpdateLog();
    if (ulog != null) {
      return Path.of(ulog.getTlogDir());
    } else if ((ulogDir = core.getCoreDescriptor().getUlogDir()) != null) {
      return Path.of(ulogDir, UpdateLog.TLOG_NAME);
    } else {
      return Path.of(core.getDataDir(), UpdateLog.TLOG_NAME);
    }
  }

  /**
   * Asserts that Update logs don't exist for replicas of type {@link
   * org.apache.solr.common.cloud.Replica.Type#PULL}
   */
  static void assertUlogPresence(DocCollection collection) {
    for (Slice s : collection.getSlices()) {
      for (Replica r : s.getReplicas()) {
        if (r.getType() == Replica.Type.NRT) {
          continue;
        }
        try (SolrCore core =
            cluster.getReplicaJetty(r).getCoreContainer().getCore(r.getCoreName())) {
          assertNotNull(core);
          Path tlogDir = getHypotheticalTlogDir(core);
          assertFalse(
              "Update log should not exist for replicas of type Passive but file is present: "
                  + tlogDir,
              Files.exists(tlogDir));
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  public void testAddDocs() throws Exception {
    int numPullReplicas = 1 + random().nextInt(3);
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, 1, 0, numPullReplicas)
        .process(cluster.getSolrClient());
    waitForState(
        "Expected collection to be created with 1 shard and " + (numPullReplicas + 1) + " replicas",
        collectionName,
        clusterShape(1, numPullReplicas + 1));
    DocCollection docCollection = assertNumberOfReplicas(1, 0, numPullReplicas, false, true);
    assertEquals(1, docCollection.getSlices().size());

    // ugly but needed to ensure a full PULL replication cycle (every sec) has occurred on the
    // replicas before adding docs
    Thread.sleep(1500);

    boolean reloaded = false;
    int numDocs = 0;
    while (true) {
      numDocs++;
      cluster
          .getSolrClient()
          .add(collectionName, new SolrInputDocument("id", String.valueOf(numDocs), "foo", "bar"));
      cluster.getSolrClient().commit(collectionName);
      log.info("Committed doc {} to leader", numDocs);

      Slice s = docCollection.getSlices().iterator().next();
      try (SolrClient leaderClient = getHttpSolrClient(s.getLeader())) {
        assertEquals(numDocs, leaderClient.query(new SolrQuery("*:*")).getResults().getNumFound());
      }
      log.info(
          "Found {} docs in leader, verifying updates make it to {} pull replicas",
          numDocs,
          numPullReplicas);

      List<Replica> pullReplicas =
          (numDocs == 1)
              ? restartPullReplica(docCollection, numPullReplicas)
              : s.getReplicas(EnumSet.of(Replica.Type.PULL));
      waitForNumDocsInAllReplicas(numDocs, pullReplicas);

      for (Replica r : pullReplicas) {
        try (SolrClient pullReplicaClient = getHttpSolrClient(r)) {
          SolrQuery req = new SolrQuery("qt", "/admin/plugins", "stats", "true");
          QueryResponse statsResponse = pullReplicaClient.query(req);
          // The adds gauge metric should be null for pull replicas since they don't process adds
          assertNull(
              "Replicas shouldn't process the add document request: " + statsResponse,
              ((Map<String, Object>)
                      (statsResponse.getResponse())
                          ._get(List.of("plugins", "UPDATE", "updateHandler", "stats"), null))
                  .get("UPDATE.updateHandler.adds"));
        }
      }

      if (reloaded) {
        break;
      } else {
        // reload
        CollectionAdminResponse response =
            CollectionAdminRequest.reloadCollection(collectionName)
                .process(cluster.getSolrClient());
        assertEquals(0, response.getStatus());
        reloaded = true;
      }
    }
    assertUlogPresence(docCollection);
  }

  private List<Replica> restartPullReplica(DocCollection docCollection, int numPullReplicas)
      throws Exception {
    Slice s = docCollection.getSlices().iterator().next();
    List<Replica> pullReplicas = s.getReplicas(EnumSet.of(Replica.Type.PULL));

    // find a node with a PULL replica that's not hosting the leader
    JettySolrRunner leaderJetty = cluster.getReplicaJetty(s.getLeader());
    JettySolrRunner replicaJetty = null;
    for (Replica r : pullReplicas) {
      JettySolrRunner jetty = cluster.getReplicaJetty(r);
      if (!jetty.getNodeName().equals(leaderJetty.getNodeName())) {
        replicaJetty = jetty;
        break;
      }
    }

    // stop / start the node with a pull replica
    if (replicaJetty != null) {
      replicaJetty.stop();
      cluster.waitForJettyToStop(replicaJetty);
      waitForState(
          "Expected to see a downed PULL replica",
          collectionName,
          clusterStateReflectsActiveAndDownReplicas());
      replicaJetty.start();
      waitForState(
          "Expected collection to have recovered with 1 shard and "
              + (numPullReplicas + 1)
              + " replicas after restarting "
              + replicaJetty.getNodeName(),
          collectionName,
          clusterShape(1, numPullReplicas + 1));
      docCollection = assertNumberOfReplicas(1, 0, numPullReplicas, false, true);
      s = docCollection.getSlices().iterator().next();
      pullReplicas = s.getReplicas(EnumSet.of(Replica.Type.PULL));
    } // else it's ok if all replicas ended up on the same node, we're not testing replica placement
    // here, but skip this part of the test

    return pullReplicas;
  }

  public void testAddRemovePullReplica() throws Exception {
    CollectionAdminRequest.createCollection(collectionName, "conf", 2, 1, 0, 0)
        .process(cluster.getSolrClient());
    waitForState(
        "Expected collection to be created with 2 shards and 1 replica each",
        collectionName,
        clusterShape(2, 2));
    DocCollection docCollection = assertNumberOfReplicas(2, 0, 0, false, true);
    assertEquals(2, docCollection.getSlices().size());

    addReplicaToShard("shard1", Replica.Type.PULL);
    docCollection = assertNumberOfReplicas(2, 0, 1, true, false);
    addReplicaToShard("shard2", Replica.Type.PULL);
    docCollection = assertNumberOfReplicas(2, 0, 2, true, false);

    waitForState(
        "Expecting collection to have 2 shards and 2 replica each",
        collectionName,
        clusterShape(2, 4));

    // Delete pull replica from shard1
    CollectionAdminRequest.deleteReplica(
            collectionName,
            "shard1",
            docCollection
                .getSlice("shard1")
                .getReplicas(EnumSet.of(Replica.Type.PULL))
                .get(0)
                .getName())
        .process(cluster.getSolrClient());
    assertNumberOfReplicas(2, 0, 1, true, true);
  }

  @Test
  public void testRemoveAllWriterReplicas() throws Exception {
    doTestNoLeader(true);
  }

  @Test
  public void testKillLeader() throws Exception {
    doTestNoLeader(false);
  }

  @Ignore("Ignore until I figure out a way to reliably record state transitions")
  public void testPullReplicaStates() throws Exception {
    // Validate that pull replicas go through the correct states when starting, stopping,
    // reconnecting
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, 1, 0, 0)
        .process(cluster.getSolrClient());
    //    cluster.getSolrClient().getZkStateReader().registerCore(collectionName); //TODO: Is this
    // needed?
    waitForState("Replica not added", collectionName, activeReplicaCount(1, 0, 0));
    addDocs(500);
    List<Replica.State> statesSeen = new ArrayList<>(3);
    cluster
        .getZkStateReader()
        .registerCollectionStateWatcher(
            collectionName,
            (liveNodes, collectionState) -> {
              Replica r = collectionState.getSlice("shard1").getReplica("core_node2");
              log.info("CollectionStateWatcher state change: {}", r);
              if (r == null) {
                return false;
              }
              statesSeen.add(r.getState());
              if (log.isInfoEnabled()) {
                log.info("CollectionStateWatcher saw state: {}", r.getState());
              }
              return r.getState() == Replica.State.ACTIVE;
            });
    CollectionAdminRequest.addReplicaToShard(collectionName, "shard1", Replica.Type.PULL)
        .process(cluster.getSolrClient());
    waitForState("Replica not added", collectionName, activeReplicaCount(1, 0, 1));
    zkClient().printLayoutToStream(System.out);
    if (log.isInfoEnabled()) {
      log.info("Saw states: {}", Arrays.toString(statesSeen.toArray()));
    }
    assertEquals(
        "Expecting DOWN->RECOVERING->ACTIVE but saw: " + Arrays.toString(statesSeen.toArray()),
        3,
        statesSeen.size());
    assertEquals(
        "Expecting DOWN->RECOVERING->ACTIVE but saw: " + Arrays.toString(statesSeen.toArray()),
        Replica.State.DOWN,
        statesSeen.get(0));
    assertEquals(
        "Expecting DOWN->RECOVERING->ACTIVE but saw: " + Arrays.toString(statesSeen.toArray()),
        Replica.State.RECOVERING,
        statesSeen.get(0));
    assertEquals(
        "Expecting DOWN->RECOVERING->ACTIVE but saw: " + Arrays.toString(statesSeen.toArray()),
        Replica.State.ACTIVE,
        statesSeen.get(0));
  }

  public void testRealTimeGet()
      throws SolrServerException,
          IOException,
          KeeperException,
          InterruptedException,
          TimeoutException {
    cluster
        .getZkStateReader()
        .waitForLiveNodes(
            10,
            TimeUnit.SECONDS,
            (oldLiveNodes, newLiveNodes) ->
                newLiveNodes.size() == cluster.getJettySolrRunners().size());
    // should be redirected to Replica.Type.NRT
    int numReplicas = random().nextBoolean() ? 1 : 2;
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, numReplicas, 0, numReplicas)
        .process(cluster.getSolrClient());
    waitForState(
        "Unexpected replica count",
        collectionName,
        activeReplicaCount(numReplicas, 0, numReplicas));
    DocCollection docCollection = assertNumberOfReplicas(numReplicas, 0, numReplicas, false, true);
    int id = 0;
    Slice slice = docCollection.getSlice("shard1");
    List<String> ids = new ArrayList<>(slice.getReplicas().size());
    for (Replica rAdd : slice.getReplicas()) {
      try (SolrClient client = getHttpSolrClient(rAdd.getBaseUrl(), rAdd.getCoreName())) {
        client.add(new SolrInputDocument("id", String.valueOf(id), "foo_s", "bar"));
      }
      SolrDocument docCloudClient =
          cluster.getSolrClient().getById(collectionName, String.valueOf(id));
      assertEquals("bar", docCloudClient.getFieldValue("foo_s"));
      for (Replica rGet : slice.getReplicas()) {
        try (SolrClient client = getHttpSolrClient(rGet.getBaseUrl(), rGet.getCoreName())) {
          SolrDocument doc = client.getById(String.valueOf(id));
          assertEquals("bar", doc.getFieldValue("foo_s"));
        }
      }
      ids.add(String.valueOf(id));
      id++;
    }
    SolrDocumentList previousAllIdsResult = null;
    for (Replica rAdd : slice.getReplicas()) {
      try (SolrClient client = getHttpSolrClient(rAdd.getBaseUrl(), rAdd.getCoreName())) {
        SolrDocumentList allIdsResult = client.getById(ids);
        if (previousAllIdsResult != null) {
          assertTrue(compareSolrDocumentList(previousAllIdsResult, allIdsResult));
        } else {
          // set the first response here
          previousAllIdsResult = allIdsResult;
          assertEquals("Unexpected number of documents", ids.size(), allIdsResult.getNumFound());
        }
      }
      id++;
    }
  }

  /*
   * validate that replication still happens on a new leader
   */
  @SuppressWarnings({"try"})
  private void doTestNoLeader(boolean removeReplica) throws Exception {
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, 1, 0, 1)
        .process(cluster.getSolrClient());
    waitForState(
        "Expected collection to be created with 1 shard and 2 replicas",
        collectionName,
        clusterShape(1, 2));
    DocCollection docCollection = assertNumberOfReplicas(1, 0, 1, false, true);

    // Add a document and commit
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "1", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    Slice s = docCollection.getSlices().iterator().next();
    try (SolrClient leaderClient = getHttpSolrClient(s.getLeader())) {
      assertEquals(1, leaderClient.query(new SolrQuery("*:*")).getResults().getNumFound());
    }

    waitForNumDocsInAllReplicas(1, docCollection.getReplicas(EnumSet.of(Replica.Type.PULL)));

    // Delete leader replica from shard1
    ignoreException("No registered leader was found"); // These are expected
    JettySolrRunner leaderJetty = null;
    if (removeReplica) {
      CollectionAdminRequest.deleteReplica(collectionName, "shard1", s.getLeader().getName())
          .process(cluster.getSolrClient());
    } else {
      leaderJetty = cluster.getReplicaJetty(s.getLeader());
      leaderJetty.stop();
      waitForState("Leader replica not removed", collectionName, clusterShape(1, 1));
      // Wait for cluster state to be updated
      waitForState(
          "Replica state not updated in cluster state",
          collectionName,
          clusterStateReflectsActiveAndDownReplicas());
    }
    docCollection = assertNumberOfReplicas(0, 0, 1, true, true);

    // Check that there is no leader for the shard
    Replica leader = docCollection.getSlice("shard1").getLeader();
    assertTrue(
        leader == null
            || !leader.isActive(cluster.getSolrClient().getClusterState().getLiveNodes()));

    // Pull replica on the other hand should be active
    Replica pullReplica =
        docCollection.getSlice("shard1").getReplicas(EnumSet.of(Replica.Type.PULL)).get(0);
    assertTrue(pullReplica.isActive(cluster.getSolrClient().getClusterState().getLiveNodes()));

    long highestTerm = 0L;
    try (ZkShardTerms zkShardTerms = new ZkShardTerms(collectionName, "shard1", zkClient())) {
      highestTerm = zkShardTerms.getHighestTerm();
    }
    // add document, this should fail since there is no leader. Pull replica should not accept the
    // update
    expectThrows(
        SolrException.class,
        () ->
            cluster
                .getSolrClient()
                .add(collectionName, new SolrInputDocument("id", "2", "foo", "zoo")));
    if (removeReplica) {
      try (ZkShardTerms zkShardTerms = new ZkShardTerms(collectionName, "shard1", zkClient())) {
        assertEquals(highestTerm, zkShardTerms.getHighestTerm());
      }
    }

    // Also fails if I send the update to the pull replica explicitly
    try (SolrClient pullReplicaClient =
        getHttpSolrClient(docCollection.getReplicas(EnumSet.of(Replica.Type.PULL)).get(0))) {
      expectThrows(
          SolrException.class,
          () ->
              pullReplicaClient.add(
                  collectionName, new SolrInputDocument("id", "2", "foo", "zoo")));
    }
    if (removeReplica) {
      try (ZkShardTerms zkShardTerms = new ZkShardTerms(collectionName, "shard1", zkClient())) {
        assertEquals(highestTerm, zkShardTerms.getHighestTerm());
      }
    }

    // Queries should still work
    waitForNumDocsInAllReplicas(1, docCollection.getReplicas(EnumSet.of(Replica.Type.PULL)));
    // Add nrt replica back. Since there is no nrt now, new nrt will have no docs. There will be
    // data loss, since it will become the leader and pull replicas will replicate from it.
    // Maybe we want to change this. Replicate from pull replicas is not a good idea, since they are
    // by definition out of date.
    if (removeReplica) {
      CollectionAdminRequest.addReplicaToShard(collectionName, "shard1", Replica.Type.NRT)
          .process(cluster.getSolrClient());
    } else {
      leaderJetty.start();
    }
    waitForState("Expected collection to be 1x2", collectionName, clusterShape(1, 2));
    unIgnoreException("No registered leader was found"); // Should have a leader from now on

    // Validate that the new nrt replica is the leader now
    cluster.getZkStateReader().forceUpdateCollection(collectionName);
    docCollection = getCollectionState(collectionName);
    leader = docCollection.getSlice("shard1").getLeader();
    assertTrue(
        leader != null
            && leader.isActive(cluster.getSolrClient().getClusterState().getLiveNodes()));

    // If jetty is restarted, the replication is not forced, and replica doesn't replicate from
    // leader until new docs are added. Is this the correct behavior? Why should these two cases be
    // different?
    if (removeReplica) {
      // Pull replicas will replicate the empty index if a new replica was added and becomes leader
      waitForNumDocsInAllReplicas(0, docCollection.getReplicas(EnumSet.of(Replica.Type.PULL)));
    }

    // add docs agin
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "2", "foo", "zoo"));
    s = docCollection.getSlices().iterator().next();
    try (SolrClient leaderClient = getHttpSolrClient(s.getLeader())) {
      leaderClient.commit();
      assertEquals(1, leaderClient.query(new SolrQuery("*:*")).getResults().getNumFound());
    }
    waitForNumDocsInAllReplicas(
        1, docCollection.getReplicas(EnumSet.of(Replica.Type.PULL)), "id:2", null, null);
    waitForNumDocsInAllReplicas(1, docCollection.getReplicas(EnumSet.of(Replica.Type.PULL)));
  }

  public void testKillPullReplica() throws Exception {
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, 1, 0, 1)
        .process(cluster.getSolrClient());
    //    cluster.getSolrClient().getZkStateReader().registerCore(collectionName); //TODO: Is this
    // needed?
    waitForState(
        "Expected collection to be created with 1 shard and 2 replicas",
        collectionName,
        clusterShape(1, 2));
    DocCollection docCollection = assertNumberOfReplicas(1, 0, 1, false, true);
    assertEquals(1, docCollection.getSlices().size());

    waitForNumDocsInAllActiveReplicas(0);
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "1", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    waitForNumDocsInAllActiveReplicas(1);

    JettySolrRunner pullReplicaJetty =
        cluster.getReplicaJetty(
            docCollection.getSlice("shard1").getReplicas(EnumSet.of(Replica.Type.PULL)).get(0));
    pullReplicaJetty.stop();
    waitForState("Replica not removed", collectionName, activeReplicaCount(1, 0, 0));
    // Also wait for the replica to be placed in state="down"
    waitForState(
        "Didn't update state", collectionName, clusterStateReflectsActiveAndDownReplicas());

    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "2", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    waitForNumDocsInAllActiveReplicas(2);

    pullReplicaJetty.start();
    waitForState("Replica not added", collectionName, activeReplicaCount(1, 0, 1));
    waitForNumDocsInAllActiveReplicas(2);
  }

  public void testSkipLeaderRecoveryProperty() throws Exception {
    final int numDocsAdded = 13;

    assertTrue(
        "Test has been broken, not enough jetties", cluster.getJettySolrRunners().size() >= 2);

    // Track the two jetty instances we're going to (re)use w/specific replica types
    final JettySolrRunner tlogLeaderyJetty = cluster.getJettySolrRunners().get(0);
    final JettySolrRunner pullFollowerJetty = cluster.getJettySolrRunners().get(1);
    assertNotEquals(tlogLeaderyJetty, pullFollowerJetty);

    // Start with a single tlog replic on the leader jetty
    CollectionAdminRequest.createCollection(collectionName, "conf", 1, 0, 1, 0)
        .setCreateNodeSet(tlogLeaderyJetty.getNodeName())
        // NOTE: we restart the leader, so we need a non-ephemeral index
        .setProperties(Map.of("solr.directoryFactory", "solr.StandardDirectoryFactory"))
        .process(cluster.getSolrClient());

    // Add 2 PULL replicas on the follower jetty
    CollectionAdminRequest.addReplicaToShard(collectionName, "shard1", Replica.Type.PULL)
        .setCreateNodeSet(pullFollowerJetty.getNodeName())
        .setPullReplicas(2)
        .process(cluster.getSolrClient());

    waitForState("Collection init never finished?", collectionName, activeReplicaCount(0, 1, 2));

    assertEquals(
        2, getCollectionState(collectionName).getReplicas(EnumSet.of(Replica.Type.PULL)).size());

    // set our 'skip' property on one of the PULL replicas, and keep track of this replica
    final String pullThatSkipsRecovery =
        getCollectionState(collectionName)
            .getReplicas(EnumSet.of(Replica.Type.PULL))
            .get(0)
            .getName();
    CollectionAdminRequest.addReplicaProperty(
            collectionName,
            "shard1",
            pullThatSkipsRecovery,
            ZkController.SKIP_LEADER_RECOVERY_PROP,
            "true")
        .process(cluster.getSolrClient());

    // index a few docs and wait to ensure everything is in sync with our expectations
    addDocs(numDocsAdded);
    waitForNumDocsInAllReplicas(numDocsAdded, getCollectionState(collectionName).getReplicas());
    waitForState(
        "Replica prop never added?",
        collectionName,
        (liveNodes, docState) -> {
          return docState
              .getReplica(pullThatSkipsRecovery)
              .getBool(ZkController.SKIP_LEADER_RECOVERY_PROP_KEY, false);
        });

    // Now shutdown our leader node and confirm all our PULL replicas are still active and serving
    // requests
    tlogLeaderyJetty.stop();
    cluster.waitForJettyToStop(tlogLeaderyJetty);
    waitForState(
        "Leader should be down, PULLs should be active",
        collectionName,
        activeReplicaCount(0, 0, 2));
    waitForNumDocsInAllReplicas(
        numDocsAdded,
        getCollectionState(collectionName).getReplicas(EnumSet.of(Replica.Type.PULL)));

    // Add yetanother PULL replica while the leader is down.
    // This new replica will immediately stall going into recoveery, since the leader is down.
    CollectionAdminRequest.addReplicaToShard(collectionName, "shard1", Replica.Type.PULL)
        .setCreateNodeSet(pullFollowerJetty.getNodeName())
        .process(cluster.getSolrClient());
    waitForState(
        "3rd PULL replica should be down",
        collectionName,
        (liveNodes, colState) -> {
          int active = 0;
          int down = 0;
          for (Replica r : colState.getReplicas(EnumSet.of(Replica.Type.PULL))) {
            if (r.getState().equals(Replica.State.ACTIVE)) {
              active++;
            } else if (r.getState().equals(Replica.State.DOWN)) {
              down++;
            }
          }
          return ((2 == active) && (1 == down));
        });

    // But even if when set our 'skip' property on this new PULL replica, it's *next* (re)start
    // should still block waiting for RECOVERY since it won't have an active index.
    final String pullThatWantsToSkipRecoveryButMustRecoverOnce =
        getCollectionState(collectionName).getReplicas(EnumSet.of(Replica.Type.PULL)).stream()
            .filter(r -> r.getState().equals(Replica.State.DOWN))
            .map(r -> r.getName())
            .findFirst()
            .get();
    CollectionAdminRequest.addReplicaProperty(
            collectionName,
            "shard1",
            pullThatWantsToSkipRecoveryButMustRecoverOnce,
            ZkController.SKIP_LEADER_RECOVERY_PROP,
            "true")
        .process(cluster.getSolrClient());

    // Restart the node all of our PULL replicas are one, and confirm that our special REPLICA goes
    // ACTIVE while the others all stay DOWN
    // (Note: Other PULL replica can't start RECOVERING until the leader comes back)
    pullFollowerJetty.stop();
    cluster.waitForJettyToStop(pullFollowerJetty);
    pullFollowerJetty.start();
    waitForState(
        "Special PULL should be ACTIVE, all others should be DOWN",
        collectionName,
        (liveNodes, colState) -> {
          for (Replica r : colState.getReplicas()) {
            if (r.getName().equals(pullThatSkipsRecovery)) {
              if (!r.getState().equals(Replica.State.ACTIVE)) {
                return false;
              }
            } else if (!r.getState().equals(Replica.State.DOWN)) {
              return false;
            }
          }
          return true;
        });

    // Restart our leader, eventually all replicas should be ACTIVE and happy
    tlogLeaderyJetty.start();
    waitForState(
        "Leader should be back, all replicas active", collectionName, activeReplicaCount(0, 1, 3));
    waitForNumDocsInAllReplicas(numDocsAdded, getCollectionState(collectionName).getReplicas());
  }

  private void waitForNumDocsInAllActiveReplicas(int numDocs)
      throws IOException, SolrServerException, InterruptedException {
    DocCollection docCollection = getCollectionState(collectionName);
    waitForNumDocsInAllReplicas(
        numDocs,
        docCollection.getReplicas().stream()
            .filter(r -> r.getState() == Replica.State.ACTIVE)
            .collect(Collectors.toList()));
  }

  private void waitForNumDocsInAllReplicas(int numDocs, Collection<Replica> replicas)
      throws IOException, SolrServerException, InterruptedException {
    waitForNumDocsInAllReplicas(numDocs, replicas, "*:*", null, null);
  }

  static void waitForNumDocsInAllReplicas(
      int numDocs, Collection<Replica> replicas, String query, String user, String pass)
      throws IOException, SolrServerException, InterruptedException {
    TimeOut t = new TimeOut(REPLICATION_TIMEOUT_SECS, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    for (Replica r : replicas) {
      String replicaUrl = r.getCoreUrl();
      try (SolrClient replicaClient = getHttpSolrClient(r)) {
        while (true) {
          QueryRequest req = new QueryRequest(new SolrQuery(query));
          if (user != null && pass != null) {
            req.setBasicAuthCredentials(user, pass);
          }
          try {
            long numFound = req.process(replicaClient).getResults().getNumFound();
            assertEquals(
                "Replica "
                    + r.getName()
                    + " ("
                    + replicaUrl
                    + ") not up to date after "
                    + REPLICATION_TIMEOUT_SECS
                    + " seconds",
                numDocs,
                numFound);
            log.info("Replica {} ({}) has all {} docs", r.name, replicaUrl, numDocs);
            break;
          } catch (AssertionError e) {
            if (t.hasTimedOut()) {
              throw e;
            } else {
              Thread.sleep(200);
            }
          }
        }
      }
    }
  }

  static void waitForDeletion(String collection) {
    waitForState(
        "Waiting for collection " + collection + " to be deleted",
        collection,
        10,
        TimeUnit.SECONDS,
        Objects::isNull);
  }

  private DocCollection assertNumberOfReplicas(
      int numNrtReplicas,
      int numTlogReplicas,
      int numPullReplicas,
      boolean updateCollection,
      boolean activeOnly)
      throws KeeperException, InterruptedException {
    return assertNumberOfReplicas(
        collectionName,
        numNrtReplicas,
        numTlogReplicas,
        numPullReplicas,
        updateCollection,
        activeOnly);
  }

  static DocCollection assertNumberOfReplicas(
      String coll,
      int numNrtReplicas,
      int numTlogReplicas,
      int numPullReplicas,
      boolean updateCollection,
      boolean activeOnly)
      throws KeeperException, InterruptedException {
    if (updateCollection) {
      cluster.getZkStateReader().forceUpdateCollection(coll);
    }
    DocCollection docCollection = getCollectionState(coll);
    assertNotNull(docCollection);
    assertEquals(
        "Unexpected number of writer replicas: " + docCollection,
        numNrtReplicas,
        docCollection.getReplicas(EnumSet.of(Replica.Type.NRT)).stream()
            .filter(r -> !activeOnly || r.getState() == Replica.State.ACTIVE)
            .count());
    assertEquals(
        "Unexpected number of pull replicas: " + docCollection,
        numPullReplicas,
        docCollection.getReplicas(EnumSet.of(Replica.Type.PULL)).stream()
            .filter(r -> !activeOnly || r.getState() == Replica.State.ACTIVE)
            .count());
    assertEquals(
        "Unexpected number of active replicas: " + docCollection,
        numTlogReplicas,
        docCollection.getReplicas(EnumSet.of(Replica.Type.TLOG)).stream()
            .filter(r -> !activeOnly || r.getState() == Replica.State.ACTIVE)
            .count());
    return docCollection;
  }

  /*
   * passes only if all replicas are active or down, and the "liveNodes" reflect the same status
   */
  private CollectionStatePredicate clusterStateReflectsActiveAndDownReplicas() {
    return (liveNodes, collectionState) -> {
      for (Replica r : collectionState.getReplicas()) {
        if (r.getState() != Replica.State.DOWN && r.getState() != Replica.State.ACTIVE) {
          return false;
        }
        if (r.getState() == Replica.State.DOWN && liveNodes.contains(r.getNodeName())) {
          return false;
        }
        if (r.getState() == Replica.State.ACTIVE && !liveNodes.contains(r.getNodeName())) {
          return false;
        }
      }
      return true;
    };
  }

  private CollectionStatePredicate activeReplicaCount(
      int numNrtReplicas, int numTlogReplicas, int numPullReplicas) {
    return (liveNodes, collectionState) -> {
      int nrtFound = 0, tlogFound = 0, pullFound = 0;
      if (collectionState == null) return false;
      for (Slice slice : collectionState) {
        for (Replica replica : slice) {
          if (replica.isActive(liveNodes))
            switch (replica.getType()) {
              case TLOG:
                tlogFound++;
                break;
              case PULL:
                pullFound++;
                break;
              case NRT:
                nrtFound++;
                break;
              default:
                throw new AssertionError("Unexpected replica type");
            }
        }
      }
      return numNrtReplicas == nrtFound
          && numTlogReplicas == tlogFound
          && numPullReplicas == pullFound;
    };
  }

  private void addDocs(int numDocs) throws SolrServerException, IOException {
    List<SolrInputDocument> docs = new ArrayList<>(numDocs);
    for (int i = 0; i < numDocs; i++) {
      docs.add(new SolrInputDocument("id", String.valueOf(i), "fieldName_s", String.valueOf(i)));
    }
    cluster.getSolrClient().add(collectionName, docs);
    cluster.getSolrClient().commit(collectionName);
  }

  private void addReplicaToShard(String shardName, Replica.Type type)
      throws IOException, SolrServerException {
    switch (random().nextInt(3)) {
      case 0: // Add replica with SolrJ
        CollectionAdminResponse response =
            CollectionAdminRequest.addReplicaToShard(collectionName, shardName, type)
                .process(cluster.getSolrClient());
        assertEquals(
            "Unexpected response status: " + response.getStatus(), 0, response.getStatus());
        break;
      case 1: // Add replica with V1 API
        String url =
            String.format(
                Locale.ROOT,
                "%s/admin/collections?action=ADDREPLICA&collection=%s&shard=%s&type=%s",
                cluster.getRandomJetty(random()).getBaseUrl(),
                collectionName,
                shardName,
                type);
        HttpGet addReplicaGet = new HttpGet(url);
        HttpResponse httpResponse = getHttpClient().execute(addReplicaGet);
        assertEquals(200, httpResponse.getStatusLine().getStatusCode());
        break;
      case 2: // Add replica with V2 API
        url =
            String.format(
                Locale.ROOT,
                "%s/____v2/collections/%s/shards/%s/replicas",
                cluster.getRandomJetty(random()).getBaseUrl(),
                collectionName,
                shardName);
        String requestBody = String.format(Locale.ROOT, "{\"type\": \"%s\"}", type);
        HttpPost addReplicaPost = new HttpPost(url);
        addReplicaPost.setHeader("Content-type", "application/json");
        addReplicaPost.setEntity(new StringEntity(requestBody));
        httpResponse = getHttpClient().execute(addReplicaPost);
        assertEquals(200, httpResponse.getStatusLine().getStatusCode());
        break;
    }
  }

  private HttpClient getHttpClient() {
    return ((CloudLegacySolrClient) cluster.getSolrClient()).getHttpClient();
  }
}
