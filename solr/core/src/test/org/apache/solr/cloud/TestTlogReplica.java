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

import static org.apache.solr.cloud.TestPullReplica.getHypotheticalTlogDir;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import com.codahale.metrics.Meter;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.lucene.index.IndexWriter;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudLegacySolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
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
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.core.SolrCore;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.update.SolrIndexWriter;
import org.apache.solr.util.RefCounted;
import org.apache.solr.util.TestInjection;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.KeeperException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestTlogReplica extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private String collectionName = null;
  private static final int REPLICATION_TIMEOUT_SECS = 10;

  private String suggestedCollectionName() {
    return (getTestClass().getSimpleName().replace("Test", "")
            + "_"
            + getSaferTestName().split(" ")[0])
        .replaceAll("(.)(\\p{Upper})", "$1_$2")
        .toLowerCase(Locale.ROOT);
  }

  @BeforeClass
  public static void setupCluster() throws Exception {
    System.setProperty("metricsEnabled", "true");
    System.setProperty("solr.waitToSeeReplicasInStateTimeoutSeconds", "30");
    configureCluster(2) // 2 + random().nextInt(3)
        .addConfig("conf", configset("cloud-minimal-inplace-updates"))
        .configure();
  }

  @AfterClass
  public static void tearDownCluster() {
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
      waitForDeletion(collectionName);
    }
    super.tearDown();
  }

  /**
   * Asserts that Update logs exist for replicas of type {@link
   * org.apache.solr.common.cloud.Replica.Type#NRT}, but not for replicas of type {@link
   * org.apache.solr.common.cloud.Replica.Type#PULL}
   */
  private void assertUlogPresence(DocCollection collection) {
    for (Slice s : collection.getSlices()) {
      for (Replica r : s.getReplicas()) {
        SolrCore core = null;
        try {
          core = cluster.getReplicaJetty(r).getCoreContainer().getCore(r.getCoreName());
          assertNotNull(core);
          Path tlogDir = getHypotheticalTlogDir(core);
          assertTrue("Update log should exist for replicas of type Append", Files.exists(tlogDir));
        } finally {
          core.close();
        }
      }
    }
  }

  // 2 times to make sure cleanup is complete, and we can create the same collection
  @Repeat(iterations = 2)
  public void testCreateDelete() throws Exception {
    switch (random().nextInt(3)) {
      case 0:
        CollectionAdminRequest.createCollection(collectionName, "conf", 2, 0, 4, 0)
            .process(cluster.getSolrClient());
        cluster.waitForActiveCollection(collectionName, 2, 8);
        break;
      case 1:
        // Sometimes don't use SolrJ
        String url =
            String.format(
                Locale.ROOT,
                "%s/admin/collections?action=CREATE&name=%s&collection.configName=%s&numShards=%s&tlogReplicas=%s",
                cluster.getRandomJetty(random()).getBaseUrl(),
                collectionName,
                "conf",
                2, // numShards
                4); // tlogReplicas
        HttpGet createCollectionGet = new HttpGet(url);
        HttpResponse httpResponse = getHttpClient().execute(createCollectionGet);
        assertEquals(200, httpResponse.getStatusLine().getStatusCode());
        cluster.waitForActiveCollection(collectionName, 2, 8);
        break;
      case 2:
        // Sometimes use V2 API
        url = cluster.getRandomJetty(random()).getBaseUrl().toString() + "/____v2/collections";
        String requestBody =
            String.format(
                Locale.ROOT,
                "{\"name\": \"%s\", \"config\": \"%s\", \"numShards\": %s, \"tlogReplicas\": %s}",
                collectionName,
                "conf",
                2, // numShards
                4); // tlogReplicas

        HttpPost createCollectionPost = new HttpPost(url);
        createCollectionPost.setHeader("Content-type", "application/json");
        createCollectionPost.setEntity(new StringEntity(requestBody));
        httpResponse = getHttpClient().execute(createCollectionPost);
        assertEquals(200, httpResponse.getStatusLine().getStatusCode());
        cluster.waitForActiveCollection(collectionName, 2, 8);
        break;
    }

    boolean reloaded = false;
    while (true) {
      DocCollection docCollection = getCollectionState(collectionName);
      assertNotNull(docCollection);
      assertEquals("Expecting 2 shards", 2, docCollection.getSlices().size());
      assertEquals("Expecting 4 replicas per shard", 8, docCollection.getReplicas().size());
      assertEquals(
          "Expecting 8 tlog replicas, 4 per shard",
          8,
          docCollection.getReplicas(EnumSet.of(Replica.Type.TLOG)).size());
      assertEquals(
          "Expecting no nrt replicas",
          0,
          docCollection.getReplicas(EnumSet.of(Replica.Type.NRT)).size());
      assertEquals(
          "Expecting no pull replicas",
          0,
          docCollection.getReplicas(EnumSet.of(Replica.Type.PULL)).size());
      for (Slice s : docCollection.getSlices()) {
        assertSame(s.getLeader().getType(), Replica.Type.TLOG);
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
            4,
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
        waitForState("failed waiting for active collection", collectionName, clusterShape(2, 8));
        reloaded = true;
      }
    }
  }

  private HttpClient getHttpClient() {
    return ((CloudLegacySolrClient) cluster.getSolrClient()).getHttpClient();
  }

  @SuppressWarnings("unchecked")
  public void testAddDocs() throws Exception {
    int numTlogReplicas = 1 + random().nextInt(3);
    DocCollection docCollection = createAndWaitForCollection(1, 0, numTlogReplicas, 0);
    assertEquals(1, docCollection.getSlices().size());

    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "1", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);

    Slice s = docCollection.getSlices().iterator().next();
    try (SolrClient leaderClient = getHttpSolrClient(s.getLeader())) {
      assertEquals(1, leaderClient.query(new SolrQuery("*:*")).getResults().getNumFound());
    }

    TimeOut t = new TimeOut(REPLICATION_TIMEOUT_SECS, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    for (Replica r : s.getReplicas(EnumSet.of(Replica.Type.TLOG))) {
      // TODO: assert replication < REPLICATION_TIMEOUT_SECS
      try (SolrClient tlogReplicaClient = getHttpSolrClient(r)) {
        while (true) {
          try {
            assertEquals(
                "Replica " + r.getName() + " not up to date after 10 seconds",
                1,
                tlogReplicaClient.query(new SolrQuery("*:*")).getResults().getNumFound());
            // Append replicas process all updates
            SolrQuery req =
                new SolrQuery(
                    "qt", "/admin/plugins",
                    "stats", "true");
            QueryResponse statsResponse = tlogReplicaClient.query(req);
            NamedList<Object> entries = (statsResponse.getResponse());
            assertEquals(
                "Append replicas should recive all updates. Replica: "
                    + r
                    + ", response: "
                    + statsResponse,
                1L,
                ((Map<String, Object>)
                        entries._get(List.of("plugins", "UPDATE", "updateHandler", "stats"), null))
                    .get("UPDATE.updateHandler.cumulativeAdds.count"));
            break;
          } catch (AssertionError e) {
            if (t.hasTimedOut()) {
              throw e;
            } else {
              Thread.sleep(100);
            }
          }
        }
      }
    }
    assertUlogPresence(docCollection);
  }

  public void testAddRemoveTlogReplica() throws Exception {
    DocCollection docCollection = createAndWaitForCollection(2, 0, 1, 0);
    assertEquals(2, docCollection.getSlices().size());

    addReplicaToShard("shard1", Replica.Type.TLOG);
    docCollection = assertNumberOfReplicas(0, 3, 0, true, false);
    addReplicaToShard("shard2", Replica.Type.TLOG);
    docCollection = assertNumberOfReplicas(0, 4, 0, true, false);

    waitForState(
        "Expecting collection to have 2 shards and 2 replica each",
        collectionName,
        clusterShape(2, 4));

    // Delete tlog replica from shard1
    CollectionAdminRequest.deleteReplica(
            collectionName,
            "shard1",
            docCollection
                .getSlice("shard1")
                .getReplicas(EnumSet.of(Replica.Type.TLOG))
                .get(0)
                .getName())
        .process(cluster.getSolrClient());
    assertNumberOfReplicas(0, 3, 0, true, true);
  }

  private void addReplicaToShard(String shardName, Replica.Type type)
      throws ClientProtocolException, IOException, SolrServerException {
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
        if (httpResponse.getStatusLine().getStatusCode() == 400) {
          final String entity = EntityUtils.toString(httpResponse.getEntity());
          System.out.println(entity);
        }
        assertEquals(200, httpResponse.getStatusLine().getStatusCode());
        break;
    }
  }

  public void testRemoveLeader() throws Exception {
    doReplaceLeader(true);
  }

  public void testKillLeader() throws Exception {
    doReplaceLeader(false);
  }

  public void testRealTimeGet()
      throws SolrServerException, IOException, KeeperException, InterruptedException {
    // should be redirected to Replica.Type.REALTIME
    int numReplicas = random().nextBoolean() ? 1 : 2;
    int numNrtReplicas = random().nextBoolean() ? 0 : 2;
    CollectionAdminRequest.createCollection(
            collectionName, "conf", 1, numNrtReplicas, numReplicas, 0)
        .process(cluster.getSolrClient());
    waitForState(
        "Unexpected replica count",
        collectionName,
        activeReplicaCount(numNrtReplicas, numReplicas, 0));
    DocCollection docCollection =
        assertNumberOfReplicas(numNrtReplicas, numReplicas, 0, false, true);
    HttpClient httpClient = getHttpClient();
    int id = 0;
    Slice slice = docCollection.getSlice("shard1");
    List<String> ids = new ArrayList<>(slice.getReplicas().size());
    for (Replica rAdd : slice.getReplicas()) {
      try (SolrClient client =
          new HttpSolrClient.Builder(rAdd.getBaseUrl())
              .withDefaultCollection(rAdd.getCoreName())
              .withHttpClient(httpClient)
              .build()) {
        client.add(new SolrInputDocument("id", String.valueOf(id), "foo_s", "bar"));
      }
      SolrDocument docCloudClient =
          cluster.getSolrClient().getById(collectionName, String.valueOf(id));
      assertNotNull(docCloudClient);
      assertEquals("bar", docCloudClient.getFieldValue("foo_s"));
      for (Replica rGet : slice.getReplicas()) {
        try (SolrClient client =
            new HttpSolrClient.Builder(rGet.getBaseUrl())
                .withDefaultCollection(rGet.getCoreName())
                .withHttpClient(httpClient)
                .build()) {
          SolrDocument doc = client.getById(String.valueOf(id));
          assertEquals("bar", doc.getFieldValue("foo_s"));
        }
      }
      ids.add(String.valueOf(id));
      id++;
    }
    SolrDocumentList previousAllIdsResult = null;
    for (Replica rAdd : slice.getReplicas()) {
      try (SolrClient client =
          new HttpSolrClient.Builder(rAdd.getBaseUrl())
              .withDefaultCollection(rAdd.getCoreName())
              .withHttpClient(httpClient)
              .build()) {
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
   * validate leader election and that replication still happens on a new leader
   */
  private void doReplaceLeader(boolean removeReplica) throws Exception {
    DocCollection docCollection = createAndWaitForCollection(1, 0, 2, 0);

    // Add a document and commit
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "1", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    Slice s = docCollection.getSlices().iterator().next();
    try (SolrClient leaderClient = getHttpSolrClient(s.getLeader())) {
      assertEquals(1, leaderClient.query(new SolrQuery("*:*")).getResults().getNumFound());
    }

    waitForNumDocsInAllReplicas(
        1, docCollection.getReplicas(EnumSet.of(Replica.Type.TLOG)), REPLICATION_TIMEOUT_SECS);

    // Delete leader replica from shard1
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
    docCollection = assertNumberOfReplicas(0, 1, 0, true, true);

    // Wait until a new leader is elected
    waitForLeaderChange(leaderJetty, "shard1");

    // There is a new leader, I should be able to add and commit
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "2", "foo", "zoo"));
    cluster.getSolrClient().commit(collectionName);

    // Queries should still work
    waitForNumDocsInAllReplicas(
        2, docCollection.getReplicas(EnumSet.of(Replica.Type.TLOG)), REPLICATION_TIMEOUT_SECS);
    // Start back the node
    if (removeReplica) {
      addReplicaWithRetries();

    } else {
      leaderJetty.start();
    }
    waitForState("Expected collection to be 1x2", collectionName, clusterShape(1, 2));
    // added replica should replicate from the leader
    waitForNumDocsInAllReplicas(
        2, docCollection.getReplicas(EnumSet.of(Replica.Type.TLOG)), REPLICATION_TIMEOUT_SECS);
  }

  private void addReplicaWithRetries() throws SolrServerException, IOException {
    int maxAttempts = 3;
    for (int i = 0; i < maxAttempts; i++) {
      try {
        CollectionAdminResponse response =
            CollectionAdminRequest.addReplicaToShard(collectionName, "shard1", Replica.Type.TLOG)
                .process(cluster.getSolrClient());
        // This is an unfortunate hack. There are cases where the ADDREPLICA fails, will create a
        // Jira to address that separately. for now, we'll retry
        if (response.isSuccess()) {
          break;
        }
        log.error("Unsuccessful attempt to add replica. Attempt: {}/{}", i, maxAttempts);
      } catch (SolrException e) {
        log.error("Exception while adding replica. Attempt: {}/{}", i, maxAttempts, e);
      }
    }
  }

  @Ignore
  public void testKillTlogReplica() throws Exception {
    DocCollection docCollection = createAndWaitForCollection(1, 0, 2, 0);

    waitForNumDocsInAllActiveReplicas(0);
    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "1", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    waitForNumDocsInAllActiveReplicas(1);

    JettySolrRunner pullReplicaJetty =
        cluster.getReplicaJetty(
            docCollection.getSlice("shard1").getReplicas(EnumSet.of(Replica.Type.TLOG)).get(0));
    pullReplicaJetty.stop();
    waitForState("Replica not removed", collectionName, activeReplicaCount(0, 1, 0));
    waitForLeaderChange(pullReplicaJetty, "shard1");
    //    // Also wait for the replica to be placed in state="down"
    //    waitForState("Didn't update state", collectionName,
    // clusterStateReflectsActiveAndDownReplicas());

    cluster.getSolrClient().add(collectionName, new SolrInputDocument("id", "2", "foo", "bar"));
    cluster.getSolrClient().commit(collectionName);
    waitForNumDocsInAllActiveReplicas(2);

    pullReplicaJetty.start();
    waitForState("Replica not added", collectionName, activeReplicaCount(0, 2, 0));
    waitForNumDocsInAllActiveReplicas(2);
  }

  @Test
  public void testOnlyLeaderIndexes() throws Exception {
    createAndWaitForCollection(1, 0, 2, 0);

    CloudSolrClient cloudClient = cluster.getSolrClient();
    new UpdateRequest()
        .add(sdoc("id", "1"))
        .add(sdoc("id", "2"))
        .add(sdoc("id", "3"))
        .add(sdoc("id", "4"))
        .process(cloudClient, collectionName);

    {
      long docsPending =
          (long)
              getSolrCore(true)
                  .get(0)
                  .getSolrMetricsContext()
                  .getMetricRegistry()
                  .getGauges()
                  .get("UPDATE.updateHandler.docsPending")
                  .getValue();
      assertEquals(
          "Expected 4 docs are pending in core " + getSolrCore(true).get(0).getCoreDescriptor(),
          4,
          docsPending);
    }

    for (SolrCore solrCore : getSolrCore(false)) {
      long docsPending =
          (long)
              solrCore
                  .getSolrMetricsContext()
                  .getMetricRegistry()
                  .getGauges()
                  .get("UPDATE.updateHandler.docsPending")
                  .getValue();
      assertEquals(
          "Expected non docs are pending in core " + solrCore.getCoreDescriptor(), 0, docsPending);
    }

    checkRTG(1, 4, cluster.getJettySolrRunners());

    new UpdateRequest().deleteById("1").deleteByQuery("id:2").process(cloudClient, collectionName);

    // The DBQ is not processed at replicas, so we still can get doc2 and other docs by RTG
    checkRTG(2, 4, getSolrRunner(false));

    Map<SolrCore, Long> timeCopyOverPerCores = getTimesCopyOverOldUpdates(getSolrCore(false));
    new UpdateRequest().commit(cloudClient, collectionName);

    waitForNumDocsInAllActiveReplicas(2);
    // There are a small delay between new searcher and copy over old updates operation
    TimeOut timeOut = new TimeOut(5, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    while (!timeOut.hasTimedOut()) {
      if (assertCopyOverOldUpdates(1, timeCopyOverPerCores)) {
        break;
      } else {
        Thread.sleep(500);
      }
    }
    assertTrue(
        "Expect only one copy over updates per cores",
        assertCopyOverOldUpdates(1, timeCopyOverPerCores));

    boolean firstCommit = true;
    // UpdateLog copy over old updates
    for (int i = 15; i <= 150; i++) {
      cloudClient.add(collectionName, sdoc("id", String.valueOf(i)));
      if (random().nextInt(100) < 15 & i != 150) {
        if (firstCommit) {
          // because tlog replicas periodically ask leader for new segments,
          // therefore the copy over old updates action must not be triggered until
          // tlog replicas actually get new segments
          assertTrue(
              "Expect only one copy over updates per cores",
              assertCopyOverOldUpdates(1, timeCopyOverPerCores));
          firstCommit = false;
        }
        cloudClient.commit(collectionName);
      }
    }
    checkRTG(120, 150, cluster.getJettySolrRunners());
    waitForReplicasCatchUp(4 * REPLICATION_TIMEOUT_SECS);
  }

  @SuppressWarnings("unchecked")
  public void testRecovery() throws Exception {
    createAndWaitForCollection(1, 0, 2, 0);

    CloudSolrClient cloudClient = cluster.getSolrClient();
    new UpdateRequest()
        .add(sdoc("id", "3"))
        .add(sdoc("id", "4"))
        .commit(cloudClient, collectionName);
    new UpdateRequest().add(sdoc("id", "5")).process(cloudClient, collectionName);
    JettySolrRunner solrRunner = getSolrRunner(false).get(0);
    solrRunner.stop();
    waitForState("Replica still up", collectionName, activeReplicaCount(0, 1, 0));
    new UpdateRequest().add(sdoc("id", "6")).process(cloudClient, collectionName);
    solrRunner.start();
    waitForState("Replica didn't recover", collectionName, activeReplicaCount(0, 2, 0));
    // We skip peerSync, so replica will always trigger commit on leader. We query only the
    // non-leader replicas, since we haven't opened a new searcher on the leader yet

    // timeout for stale collection state
    waitForNumDocsInAllReplicas(4, getNonLeaderReplicas(collectionName), 10);

    // If I add the doc immediately, the leader fails to communicate with the follower with broken
    // pipe. Options are, wait or retry...
    for (int i = 0; i < 3; i++) {
      UpdateRequest ureq = new UpdateRequest().add(sdoc("id", "7"));
      ureq.setParam("collection", collectionName);
      NamedList<Object> response = cloudClient.request(ureq);
      if ((Integer) ((NamedList<Object>) response.get("responseHeader")).get(UpdateRequest.REPFACT)
          >= 2) {
        break;
      }
      log.info("Min RF not achieved yet. retrying");
    }
    checkRTG(3, 7, cluster.getJettySolrRunners());
    try {
      TestInjection.skipIndexWriterCommitOnClose = true;
      solrRunner.stop();
      waitForState("Replica still up", collectionName, activeReplicaCount(0, 1, 0));
    } finally {
      TestInjection.skipIndexWriterCommitOnClose = false;
    }
    solrRunner.start();
    waitForState("Replica didn't recover", collectionName, activeReplicaCount(0, 2, 0));
    // timeout for stale collection state
    waitForNumDocsInAllReplicas(5, getNonLeaderReplicas(collectionName), 10);
    checkRTG(3, 7, cluster.getJettySolrRunners());
    cluster.getSolrClient().commit(collectionName);

    // Test replica recovery apply buffer updates
    Semaphore waitingForBufferUpdates = new Semaphore(0);
    Semaphore waitingForReplay = new Semaphore(0);
    RecoveryStrategy.testing_beforeReplayBufferingUpdates =
        () -> {
          try {
            waitingForReplay.release();
            waitingForBufferUpdates.acquire();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("interrupted", e);
            fail("Test interrupted: " + e.getMessage());
          }
        };
    solrRunner.stop();
    solrRunner.start();
    waitingForReplay.acquire();
    // If I add the doc immediately, the leader fails to communicate with the follower with broken
    // pipe. Options are, wait or retry...
    for (int i = 0; i < 3; i++) {
      UpdateRequest ureq = new UpdateRequest().add(sdoc("id", "8"));
      ureq.setParam("collection", collectionName);
      NamedList<Object> response = cloudClient.request(ureq);
      if ((Integer) ((NamedList<Object>) response.get("responseHeader")).get(UpdateRequest.REPFACT)
          >= 2) {
        break;
      }
      log.info("Min RF not achieved yet. retrying");
    }
    new UpdateRequest()
        .add(sdoc("id", "9"))
        .add(sdoc("id", "10"))
        .process(cloudClient, collectionName);
    waitingForBufferUpdates.release();
    RecoveryStrategy.testing_beforeReplayBufferingUpdates = null;
    waitForState("Replica didn't recover", collectionName, activeReplicaCount(0, 2, 0));
    checkRTG(3, 10, cluster.getJettySolrRunners());
    for (SolrCore solrCore : getSolrCore(false)) {
      RefCounted<IndexWriter> iwRef =
          solrCore.getUpdateHandler().getSolrCoreState().getIndexWriter(null);
      assertFalse(
          "IndexWriter at replicas must not see updates ", iwRef.get().hasUncommittedChanges());
      iwRef.decref();
    }
  }

  private List<Replica> getNonLeaderReplicas(String collectionName) {
    return getCollectionState(collectionName).getReplicas().stream()
        .filter((r) -> !r.getBool("leader", false))
        .collect(Collectors.toList());
  }

  public void testDeleteById() throws Exception {
    createAndWaitForCollection(1, 0, 2, 0);
    CloudSolrClient cloudClient = cluster.getSolrClient();
    new UpdateRequest().deleteByQuery("*:*").commit(cluster.getSolrClient(), collectionName);
    new UpdateRequest().add(sdoc("id", "1")).commit(cloudClient, collectionName);
    waitForNumDocsInAllActiveReplicas(1);
    new UpdateRequest().deleteById("1").process(cloudClient, collectionName);
    boolean successs = false;
    try {
      checkRTG(1, 1, cluster.getJettySolrRunners());
      successs = true;
    } catch (AssertionError e) {
      // expected
    }
    assertFalse("Doc1 is deleted but it's still exist", successs);
  }

  public void testBasicLeaderElection() throws Exception {
    createAndWaitForCollection(1, 0, 2, 0);
    CloudSolrClient cloudClient = cluster.getSolrClient();
    new UpdateRequest().deleteByQuery("*:*").commit(cluster.getSolrClient(), collectionName);
    new UpdateRequest()
        .add(sdoc("id", "1"))
        .add(sdoc("id", "2"))
        .process(cloudClient, collectionName);
    JettySolrRunner oldLeaderJetty = getSolrRunner(true).get(0);
    oldLeaderJetty.stop();
    waitForState("Replica not removed", collectionName, activeReplicaCount(0, 1, 0));

    // Even after the replica is gone, a leader may not be elected yet. Wait for it.
    waitForLeaderChange(oldLeaderJetty, "shard1");

    new UpdateRequest()
        .add(sdoc("id", "3"))
        .add(sdoc("id", "4"))
        .process(cloudClient, collectionName);
    oldLeaderJetty.start();
    waitForState("Replica not added", collectionName, activeReplicaCount(0, 2, 0));
    checkRTG(1, 4, cluster.getJettySolrRunners());
    new UpdateRequest().commit(cloudClient, collectionName);
    waitForNumDocsInAllActiveReplicas(4, 0);
  }

  @Nightly
  public void testRebalanceLeaders() throws Exception {
    createAndWaitForCollection(1, 0, 2, 0);
    CloudSolrClient cloudClient = cluster.getSolrClient();
    new UpdateRequest().deleteByQuery("*:*").commit(cluster.getSolrClient(), collectionName);

    // Find a replica which isn't leader
    DocCollection docCollection = cloudClient.getClusterState().getCollection(collectionName);
    Slice slice = docCollection.getSlices().iterator().next();
    Replica newLeader = null;
    for (Replica replica : slice.getReplicas()) {
      if (Objects.equals(slice.getLeader(), replica)) continue;
      newLeader = replica;
      break;
    }
    assertNotNull("Failed to find a candidate of new leader", newLeader);

    // Set preferredLeader flag to the replica
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("action", CollectionParams.CollectionAction.ADDREPLICAPROP.toString());
    params.set("collection", collectionName);
    params.set("shard", slice.getName());
    params.set("replica", newLeader.getName());
    params.set("property", "preferredLeader");
    params.set("property.value", "true");
    var request =
        new GenericSolrRequest(
            SolrRequest.METHOD.POST,
            "/admin/collections",
            SolrRequest.SolrRequestType.ADMIN,
            params);
    cloudClient.request(request);

    // Wait until a preferredleader flag is set to the new leader candidate
    String newLeaderName = newLeader.getName();
    waitForState(
        "Waiting for setting preferredleader flag",
        collectionName,
        10,
        TimeUnit.SECONDS,
        c -> {
          Map<String, Slice> slices = c.getSlicesMap();
          Replica me = slices.get(slice.getName()).getReplica(newLeaderName);
          return me.getBool("property.preferredleader", false);
        });

    // Rebalance leaders
    params = new ModifiableSolrParams();
    params.set("action", CollectionParams.CollectionAction.REBALANCELEADERS.toString());
    params.set("collection", collectionName);
    params.set("maxAtOnce", "10");
    request =
        new GenericSolrRequest(
            SolrRequest.METHOD.POST,
            "/admin/collections",
            SolrRequest.SolrRequestType.ADMIN,
            params);
    cloudClient.request(request);

    // Wait until a new leader is elected
    waitForState(
        "Waiting for a new leader to be elected",
        collectionName,
        30,
        TimeUnit.SECONDS,
        c -> {
          Replica leader = c.getSlice(slice.getName()).getLeader();
          return leader != null
              && leader.getName().equals(newLeaderName)
              && leader.isActive(cloudClient.getClusterState().getLiveNodes());
        });

    new UpdateRequest()
        .add(sdoc("id", "1"))
        .add(sdoc("id", "2"))
        .add(sdoc("id", "3"))
        .add(sdoc("id", "4"))
        .process(cloudClient, collectionName);
    checkRTG(1, 4, cluster.getJettySolrRunners());
    new UpdateRequest().commit(cloudClient, collectionName);
    waitForNumDocsInAllActiveReplicas(4);
  }

  private void waitForLeaderChange(JettySolrRunner oldLeaderJetty, String shardName) {
    waitForState(
        "Expect new leader",
        collectionName,
        collectionState -> {
          Replica leader = collectionState.getLeader(shardName);
          if (leader == null
              || !leader.isActive(cluster.getSolrClient().getClusterState().getLiveNodes())) {
            return false;
          }
          return oldLeaderJetty == null
              || !leader.getNodeName().equals(oldLeaderJetty.getNodeName());
        });
  }

  public void testOutOfOrderDBQWithInPlaceUpdates() throws Exception {
    createAndWaitForCollection(1, 0, 2, 0);
    assertFalse(
        getSolrCore(true).get(0).getLatestSchema().getField("inplace_updatable_int").indexed());
    assertFalse(
        getSolrCore(true).get(0).getLatestSchema().getField("inplace_updatable_int").stored());
    assertTrue(
        getSolrCore(true)
            .get(0)
            .getLatestSchema()
            .getField("inplace_updatable_int")
            .hasDocValues());
    List<UpdateRequest> updates = new ArrayList<>();
    updates.add(
        simulatedUpdateRequest(
            null,
            "id",
            1,
            "title_s",
            "title0_new",
            "inplace_updatable_int",
            5,
            "_version_",
            1L)); // full update
    updates.add(simulatedDBQ("inplace_updatable_int:5", 3L));
    updates.add(simulatedUpdateRequest(1L, "id", 1, "inplace_updatable_int", 6, "_version_", 2L));
    for (JettySolrRunner solrRunner : getSolrRunner(false)) {
      try (SolrClient client = solrRunner.newClient()) {
        for (UpdateRequest up : updates) {
          up.process(client, collectionName);
        }
      }
    }
    JettySolrRunner oldLeaderJetty = getSolrRunner(true).get(0);
    oldLeaderJetty.stop();
    waitForState("Replica not removed", collectionName, activeReplicaCount(0, 1, 0));
    waitForLeaderChange(oldLeaderJetty, "shard1");
    oldLeaderJetty.start();
    waitForState("Replica not added", collectionName, activeReplicaCount(0, 2, 0));
    checkRTG(1, 1, cluster.getJettySolrRunners());
    SolrDocument doc = cluster.getSolrClient().getById(collectionName, "1");
    assertNotNull(doc.get("title_s"));
  }

  private UpdateRequest simulatedUpdateRequest(Long prevVersion, Object... fields) {
    SolrInputDocument doc = sdoc(fields);

    // get baseUrl of the leader
    String baseUrl = getBaseUrl();

    UpdateRequest ur = new UpdateRequest();
    ur.add(doc);
    ur.setParam("update.distrib", "FROMLEADER");
    if (prevVersion != null) {
      ur.setParam("distrib.inplace.prevversion", String.valueOf(prevVersion));
      ur.setParam("distrib.inplace.update", "true");
    }
    ur.setParam("distrib.from", baseUrl);
    return ur;
  }

  private UpdateRequest simulatedDBQ(String query, long version) {
    String baseUrl = getBaseUrl();

    UpdateRequest ur = new UpdateRequest();
    ur.deleteByQuery(query);
    ur.setParam("_version_", "" + version);
    ur.setParam("update.distrib", "FROMLEADER");
    ur.setParam("distrib.from", baseUrl);
    return ur;
  }

  private String getBaseUrl() {
    DocCollection collection =
        cluster.getSolrClient().getClusterState().getCollection(collectionName);
    Slice slice = collection.getSlice("shard1");
    return slice.getLeader().getCoreUrl();
  }

  private DocCollection createAndWaitForCollection(
      int numShards, int numNrtReplicas, int numTlogReplicas, int numPullReplicas)
      throws SolrServerException, IOException, KeeperException, InterruptedException {
    CollectionAdminRequest.createCollection(
            collectionName, "conf", numShards, numNrtReplicas, numTlogReplicas, numPullReplicas)
        .process(cluster.getSolrClient());
    int numReplicasPerShard = numNrtReplicas + numTlogReplicas + numPullReplicas;
    waitForState(
        "Expected collection to be created with "
            + numShards
            + " shards and  "
            + numReplicasPerShard
            + " replicas",
        collectionName,
        clusterShape(numShards, numShards * numReplicasPerShard));
    return assertNumberOfReplicas(
        numNrtReplicas * numShards,
        numTlogReplicas * numShards,
        numPullReplicas * numShards,
        false,
        true);
  }

  private void waitForNumDocsInAllActiveReplicas(int numDocs)
      throws IOException, SolrServerException, InterruptedException {
    waitForNumDocsInAllActiveReplicas(numDocs, REPLICATION_TIMEOUT_SECS);
  }

  private void waitForNumDocsInAllActiveReplicas(int numDocs, int timeout)
      throws IOException, SolrServerException, InterruptedException {
    DocCollection docCollection = getCollectionState(collectionName);
    waitForNumDocsInAllReplicas(
        numDocs,
        docCollection.getReplicas().stream()
            .filter(r -> r.getState() == Replica.State.ACTIVE)
            .collect(Collectors.toList()),
        timeout);
  }

  private void waitForNumDocsInAllReplicas(int numDocs, Collection<Replica> replicas, int timeout)
      throws IOException, SolrServerException, InterruptedException {
    waitForNumDocsInAllReplicas(numDocs, replicas, "*:*", timeout);
  }

  private void waitForNumDocsInAllReplicas(
      int numDocs, Collection<Replica> replicas, String query, int timeout)
      throws IOException, SolrServerException, InterruptedException {
    TimeOut t = new TimeOut(timeout, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    for (Replica r : replicas) {
      if (!r.isActive(cluster.getSolrClient().getClusterState().getLiveNodes())) {
        continue;
      }
      try (SolrClient replicaClient = getHttpSolrClient(r)) {
        while (true) {
          try {
            assertEquals(
                "Replica " + r.getName() + " not up to date after " + timeout + " seconds",
                numDocs,
                replicaClient.query(new SolrQuery(query)).getResults().getNumFound());
            break;
          } catch (AssertionError e) {
            if (t.hasTimedOut()) {
              throw e;
            } else {
              Thread.sleep(100);
            }
          }
        }
      }
    }
  }

  private void waitForDeletion(String collection) {
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
    if (updateCollection) {
      cluster.getZkStateReader().forceUpdateCollection(collectionName);
    }
    DocCollection docCollection = getCollectionState(collectionName);
    assertNotNull(docCollection);
    assertEquals(
        "Unexpected number of nrt replicas: " + docCollection,
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
        "Unexpected number of tlog replicas: " + docCollection,
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

  private List<SolrCore> getSolrCore(boolean isLeader) {
    List<SolrCore> rs = new ArrayList<>();

    CloudSolrClient cloudClient = cluster.getSolrClient();
    DocCollection docCollection = cloudClient.getClusterState().getCollection(collectionName);

    for (JettySolrRunner solrRunner : cluster.getJettySolrRunners()) {
      if (solrRunner.getCoreContainer() == null) continue;
      for (SolrCore solrCore : solrRunner.getCoreContainer().getCores()) {
        CloudDescriptor cloudDescriptor = solrCore.getCoreDescriptor().getCloudDescriptor();
        Slice slice = docCollection.getSlice(cloudDescriptor.getShardId());
        Replica replica = docCollection.getReplica(cloudDescriptor.getCoreNodeName());
        if (Objects.equals(slice.getLeader(), replica) && isLeader) {
          rs.add(solrCore);
        } else if (!Objects.equals(slice.getLeader(), replica) && !isLeader) {
          rs.add(solrCore);
        }
      }
    }
    return rs;
  }

  private void checkRTG(int from, int to, List<JettySolrRunner> solrRunners) throws Exception {
    for (JettySolrRunner solrRunner : solrRunners) {
      try (SolrClient client = solrRunner.newClient()) {
        for (int i = from; i <= to; i++) {
          SolrQuery query = new SolrQuery();
          query.set("distrib", false);
          query.setRequestHandler("/get");
          query.set("id", i);
          QueryResponse res = client.query(collectionName, query);
          assertNotNull(
              "Can not find doc " + i + " in " + solrRunner.getBaseUrl(),
              res.getResponse().get("doc"));
        }
      }
    }
  }

  private List<JettySolrRunner> getSolrRunner(boolean isLeader) {
    List<JettySolrRunner> rs = new ArrayList<>();
    CloudSolrClient cloudClient = cluster.getSolrClient();
    DocCollection docCollection = cloudClient.getClusterState().getCollection(collectionName);
    for (JettySolrRunner solrRunner : cluster.getJettySolrRunners()) {
      if (solrRunner.getCoreContainer() == null) continue;
      for (SolrCore solrCore : solrRunner.getCoreContainer().getCores()) {
        CloudDescriptor cloudDescriptor = solrCore.getCoreDescriptor().getCloudDescriptor();
        Slice slice = docCollection.getSlice(cloudDescriptor.getShardId());
        Replica replica = docCollection.getReplica(cloudDescriptor.getCoreNodeName());
        if (Objects.equals(slice.getLeader(), replica) && isLeader) {
          rs.add(solrRunner);
        } else if (!Objects.equals(slice.getLeader(), replica) && !isLeader) {
          rs.add(solrRunner);
        }
      }
    }
    return rs;
  }

  private void waitForReplicasCatchUp(int numTry) throws IOException, InterruptedException {
    String leaderTimeCommit =
        getSolrCore(true)
            .get(0)
            .getDeletionPolicy()
            .getLatestCommit()
            .getUserData()
            .get(SolrIndexWriter.COMMIT_TIME_MSEC_KEY);
    if (leaderTimeCommit == null) return;
    for (int i = 0; i < numTry; i++) {
      boolean inSync = true;
      for (SolrCore solrCore : getSolrCore(false)) {
        String replicateTimeCommit =
            solrCore
                .getDeletionPolicy()
                .getLatestCommit()
                .getUserData()
                .get(SolrIndexWriter.COMMIT_TIME_MSEC_KEY);
        if (!leaderTimeCommit.equals(replicateTimeCommit)) {
          inSync = false;
          Thread.sleep(500);
          break;
        }
      }
      if (inSync) return;
    }

    fail("Some replicas are not in sync with leader");
  }

  private boolean assertCopyOverOldUpdates(long delta, Map<SolrCore, Long> timesPerCore) {
    for (SolrCore core : timesPerCore.keySet()) {
      if (timesPerCore.get(core) + delta != getTimesCopyOverOldUpdates(core)) return false;
    }
    return true;
  }

  private Map<SolrCore, Long> getTimesCopyOverOldUpdates(List<SolrCore> cores) {
    Map<SolrCore, Long> timesPerCore = new HashMap<>();
    for (SolrCore core : cores) {
      long times = getTimesCopyOverOldUpdates(core);
      timesPerCore.put(core, times);
    }
    return timesPerCore;
  }

  private long getTimesCopyOverOldUpdates(SolrCore core) {
    return ((Meter)
            core.getSolrMetricsContext()
                .getMetricRegistry()
                .getMetrics()
                .get("TLOG.copyOverOldUpdates.ops"))
        .getCount();
  }
}
