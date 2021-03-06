/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.solr.factory.impl;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.zookeeper.KeeperException;
import org.codice.solr.factory.SolrClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

public class SolrCloudClientFactory implements SolrClientFactory {

    protected static final Logger LOGGER = LoggerFactory.getLogger(SolrCloudClientFactory.class);

    private static final int SHARD_COUNT = parseSystemProperty("solr.cloud.shardCount", 2);

    private static final int REPLICATION_FACTOR = parseSystemProperty("solr.cloud.replicationFactor", 2);

    private static final int MAXIMUM_SHARDS_PER_NODE = parseSystemProperty("solr.cloud.maxShardPerNode", 2);

    private static final int THREAD_POOL_DEFAULT_SIZE = 128;

    private static final ScheduledExecutorService EXECUTOR_SERVICE = createExecutorService();

    private static ScheduledExecutorService createExecutorService() throws NumberFormatException {
        Integer threadPoolSize = parseSystemProperty("org.codice.ddf.system.threadPoolSize",
                THREAD_POOL_DEFAULT_SIZE);
        return Executors.newScheduledThreadPool(threadPoolSize);
    }

    private static int parseSystemProperty(String propertyKey, int defaultValue) {
        int value = defaultValue;
        String propertyValue = System.getProperty(propertyKey);
        if (propertyValue != null) {
            try {
                value = Integer.parseInt(propertyValue);
            } catch (NumberFormatException e) {
                LOGGER.warn("Unable to parse number from system property [{}] with value [{}]. Using default value [{}] instead.",
                        propertyKey,
                        System.getProperty(propertyKey),
                        defaultValue);
            }
        }
        return value;
    }

    @Override
    public Future<SolrClient> newClient(String core) {
        String zookeeperHosts = System.getProperty("solr.cloud.zookeeper");
        if (zookeeperHosts == null) {
            LOGGER.warn(
                    "Cannot create Solr Cloud client without Zookeeper host list system property [solr.cloud.zookeeper] being set.");
            return null;
        }
        return getClient(zookeeperHosts, core);
    }

    public static Future<SolrClient> getClient(String zookeeperHosts, String collection) {
        RetryPolicy retryPolicy = new RetryPolicy()
                .retryWhen(null)
                .withBackoff(10,
                TimeUnit.MINUTES.toMillis(1),
                TimeUnit.MILLISECONDS);
        return Failsafe.with(retryPolicy)
                .with(EXECUTOR_SERVICE)
                .onRetry((c, failure, ctx) -> LOGGER.debug(
                        "Attempt {} failed to create Solr Cloud client ({}). Retrying again.",
                        ctx.getExecutions(),
                        collection))
                .onFailedAttempt(failure -> LOGGER.debug(
                        "Attempt failed to create Solr Cloud client (" + collection + ")",
                        failure))
                .onSuccess(client -> LOGGER.debug("Successfully created Solr Cloud client ({})",
                        collection))
                .onFailure(failure -> LOGGER.warn(
                        "All attempts failed to create Solr Cloud client (" + collection + ")",
                        failure))
                .get(() -> createSolrCloudClient(zookeeperHosts, collection));
    }

    private static SolrClient createSolrCloudClient(String zookeeperHosts, String collection) {

        CloudSolrClient client = new CloudSolrClient(zookeeperHosts);
        client.connect();

        try {
            uploadCoreConfiguration(collection, client);
        } catch (SolrFactoryException e) {
            LOGGER.debug("Unable to upload configuration to Solr Cloud", e);
            return null;
        }

        try {
            createCollection(collection, client);
        } catch (SolrFactoryException e) {
            LOGGER.debug("Unable to create collection on Solr Cloud", e);
            return null;
        }

        client.setDefaultCollection(collection);
        return client;
    }

    private static void createCollection(String collection, CloudSolrClient client)
            throws SolrFactoryException {
        try {
            CollectionAdminResponse response = new CollectionAdminRequest.List().process(client);

            if (response == null || response.getResponse() == null || response.getResponse()
                    .get("collections") == null) {
                throw new SolrFactoryException("Failed to get a list of existing collections");
            }

            List<String> collections = (List<String>) response.getResponse()
                    .get("collections");
            if (!collections.contains(collection)) {
                response = new CollectionAdminRequest.Create().setNumShards(SHARD_COUNT)
                        .setMaxShardsPerNode(MAXIMUM_SHARDS_PER_NODE)
                        .setReplicationFactor(REPLICATION_FACTOR)
                        .setCollectionName(collection)
                        .process(client);
                if (!response.isSuccess()) {
                    throw new SolrFactoryException(
                            "Failed to create collection [" + collection + "]: "
                                    + response.getErrorMessages());
                }
                if (!isCollectionReady(client, collection)) {
                    throw new SolrFactoryException(
                            "Solr collection [" + collection + "] was not ready in time.");
                }
            } else {
                LOGGER.debug("Collection already exists: " + collection);
            }
        } catch (SolrServerException | IOException e) {
            throw new SolrFactoryException("Failed to create collection: " + collection, e);
        }
    }

    private static void uploadCoreConfiguration(String collection, CloudSolrClient client)
            throws SolrFactoryException {
        boolean configExistsInZk;

        try {
            configExistsInZk = client.getZkStateReader()
                    .getZkClient()
                    .exists("/configs/" + collection, true);
        } catch (KeeperException | InterruptedException e) {
            throw new SolrFactoryException("Failed to check config status with Zookeeper for collection: " + collection,
                    e);
        }

        if (!configExistsInZk) {
            ConfigurationFileProxy configProxy =
                    new ConfigurationFileProxy(ConfigurationStore.getInstance());
            configProxy.writeSolrConfiguration(collection);
            Path configPath = Paths.get(configProxy.getDataDirectory()
                    .getAbsolutePath(), collection, "conf");

            try {
                client.uploadConfig(configPath, collection);
            } catch (IOException e) {
                throw new SolrFactoryException("Failed to upload configurations for collection: " + collection,
                        e);
            }
        }
    }

    private static boolean isCollectionReady(CloudSolrClient client, String collection) {
        RetryPolicy retryPolicy = new RetryPolicy().retryWhen(false)
                .withMaxRetries(30)
                .withDelay(1, TimeUnit.SECONDS);

        boolean collectionCreated = Failsafe.with(retryPolicy)
                .onFailure(failure -> LOGGER.debug(
                        "All attempts failed to read Zookeeper state for collection existence ("
                                + collection + ")",
                        failure))
                .get(() -> client.getZkStateReader()
                        .getClusterState()
                        .hasCollection(collection));

        if (!collectionCreated) {
            LOGGER.debug("Timeout while waiting for collection to be created: " + collection);
            return false;
        }

        boolean shardsStarted = Failsafe.with(retryPolicy)
                .onFailure(failure -> LOGGER.debug(
                        "All attempts failed to read Zookeeper state for collection's shard count ("
                                + collection + ")",
                        failure))
                .get(() -> client.getZkStateReader()
                        .getClusterState()
                        .getSlices(collection)
                        .size() == SHARD_COUNT);

        if (!shardsStarted) {
            LOGGER.debug("Timeout while waiting for collection shards to start: " + collection);
        }

        return shardsStarted;
    }
}
