/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.admin.impl;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;
import org.apache.pulsar.common.api.proto.PulsarApi;
import static org.apache.pulsar.common.util.Codec.decode;

import com.github.zafarkhaja.semver.Version;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;

import org.apache.bookkeeper.mledger.AsyncCallbacks.ManagedLedgerInfoCallback;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedLedgerConfig;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerInfo;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerFactoryImpl;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerOfflineBacklog;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.admin.ZkAdminPaths;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.service.BrokerServiceException.AlreadyRunningException;
import org.apache.pulsar.broker.service.BrokerServiceException.NotAllowedException;
import org.apache.pulsar.broker.service.BrokerServiceException.SubscriptionBusyException;
import org.apache.pulsar.broker.service.BrokerServiceException.SubscriptionInvalidCursorPosition;
import org.apache.pulsar.broker.service.BrokerServiceException.TopicBusyException;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.persistent.PersistentReplicator;
import org.apache.pulsar.broker.service.persistent.PersistentSubscription;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.client.admin.LongRunningProcessStatus;
import org.apache.pulsar.client.admin.OffloadProcessStatus;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.PulsarAdminException.NotFoundException;
import org.apache.pulsar.client.admin.PulsarAdminException.PreconditionFailedException;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.common.allocator.PulsarByteBufAllocator;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.InitialPosition;
import org.apache.pulsar.common.api.proto.PulsarApi.KeyValue;
import org.apache.pulsar.common.api.proto.PulsarApi.MessageMetadata;
import org.apache.pulsar.common.compression.CompressionCodec;
import org.apache.pulsar.common.compression.CompressionCodecProvider;
import org.apache.pulsar.common.naming.TopicDomain;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.AuthPolicies;
import org.apache.pulsar.common.policies.data.PartitionedTopicInternalStats;
import org.apache.pulsar.common.policies.data.PartitionedTopicStats;
import org.apache.pulsar.common.policies.data.PersistentOfflineTopicStats;
import org.apache.pulsar.common.policies.data.PersistentTopicInternalStats;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.apache.pulsar.common.util.DateFormatter;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public class PersistentTopicsBase extends AdminResource {
    private static final Logger log = LoggerFactory.getLogger(PersistentTopicsBase.class);

    private static final int OFFLINE_TOPIC_STAT_TTL_MINS = 10;
    private static final String DEPRECATED_CLIENT_VERSION_PREFIX = "Pulsar-CPP-v";
    private static final Version LEAST_SUPPORTED_CLIENT_VERSION_PREFIX = Version.forIntegers(1, 21);

    protected List<String> internalGetList() {
        validateAdminAccessForTenant(namespaceName.getTenant());

        // Validate that namespace exists, throws 404 if it doesn't exist
        try {
            policiesCache().get(path(POLICIES, namespaceName.toString()));
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to get topic list {}: Namespace does not exist", clientAppId(), namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (Exception e) {
            log.error("[{}] Failed to get topic list {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }

        List<String> topics = Lists.newArrayList();

        try {
            String path = String.format("/managed-ledgers/%s/%s", namespaceName.toString(), domain());
            for (String topic : managedLedgerListCache().get(path)) {
                if (domain().equals(TopicDomain.persistent.toString())) {
                    topics.add(TopicName.get(domain(), namespaceName, decode(topic)).toString());
                }
            }
        } catch (KeeperException.NoNodeException e) {
            // NoNode means there are no topics in this domain for this namespace
        } catch (Exception e) {
            log.error("[{}] Failed to get topics list for namespace {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }

        topics.sort(null);
        return topics;
    }

    protected List<String> internalGetPartitionedTopicList() {
        validateAdminAccessForTenant(namespaceName.getTenant());

        // Validate that namespace exists, throws 404 if it doesn't exist
        try {
            policiesCache().get(path(POLICIES, namespaceName.toString()));
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to get partitioned topic list {}: Namespace does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (Exception e) {
            log.error("[{}] Failed to get partitioned topic list for namespace {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }
        return getPartitionedTopicList(TopicDomain.getEnum(domain()));
    }

    protected Map<String, Set<AuthAction>> internalGetPermissionsOnTopic() {
        // This operation should be reading from zookeeper and it should be allowed without having admin privileges
        validateAdminAccessForTenant(namespaceName.getTenant());

        String topicUri = topicName.toString();

        try {
            Policies policies = policiesCache().get(path(POLICIES, namespaceName.toString()))
                    .orElseThrow(() -> new RestException(Status.NOT_FOUND, "Namespace does not exist"));

            Map<String, Set<AuthAction>> permissions = Maps.newTreeMap();
            AuthPolicies auth = policies.auth_policies;

            // First add namespace level permissions
            for (String role : auth.namespace_auth.keySet()) {
                permissions.put(role, auth.namespace_auth.get(role));
            }

            // Then add topic level permissions
            if (auth.destination_auth.containsKey(topicUri)) {
                for (Map.Entry<String, Set<AuthAction>> entry : auth.destination_auth.get(topicUri).entrySet()) {
                    String role = entry.getKey();
                    Set<AuthAction> topicPermissions = entry.getValue();

                    if (!permissions.containsKey(role)) {
                        permissions.put(role, topicPermissions);
                    } else {
                        // Do the union between namespace and topic level
                        Set<AuthAction> union = Sets.union(permissions.get(role), topicPermissions);
                        permissions.put(role, union);
                    }
                }
            }

            return permissions;
        } catch (Exception e) {
            log.error("[{}] Failed to get permissions for topic {}", clientAppId(), topicUri, e);
            throw new RestException(e);
        }
    }

    protected void validateAdminAndClientPermission() {
        try {
            validateAdminAccessForTenant(topicName.getTenant());
        } catch (Exception ve) {
            try {
                checkAuthorization(pulsar(), topicName, clientAppId(), clientAuthData());
            } catch (RestException re) {
                throw re;
            } catch (Exception e) {
                // unknown error marked as internal server error
                log.warn("Unexpected error while authorizing request. topic={}, role={}. Error: {}",
                        topicName, clientAppId(), e.getMessage(), e);
                throw new RestException(e);
            }
        }
    }

    public void validateAdminOperationOnTopic(boolean authoritative) {
        validateAdminAccessForTenant(topicName.getTenant());
        validateTopicOwnership(topicName, authoritative);
    }

    protected void validateAdminAccessForSubscriber(String subscriptionName, boolean authoritative) {
        validateTopicOwnership(topicName, authoritative);
        try {
            validateAdminAccessForTenant(topicName.getTenant());
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] failed to validate admin access for {}", topicName, clientAppId());
            }
            validateAdminAccessForSubscriber(subscriptionName);
        }
    }

    private void validateAdminAccessForSubscriber(String subscriptionName) {
        try {
            if (!pulsar().getBrokerService().getAuthorizationService().canConsume(topicName, clientAppId(),
                    clientAuthData(), subscriptionName)) {
                log.warn("[{}} Subscriber {} is not authorized to access api", topicName, clientAppId());
                throw new RestException(Status.UNAUTHORIZED,
                        String.format("Subscriber %s is not authorized to access this operation", clientAppId()));
            }
        } catch (RestException re) {
            throw re;
        } catch (Exception e) {
            // unknown error marked as internal server error
            log.warn("Unexpected error while authorizing request. topic={}, role={}. Error: {}", topicName,
                    clientAppId(), e.getMessage(), e);
            throw new RestException(e);
        }
    }

    private void grantPermissions(String topicUri, String role, Set<AuthAction> actions) {
        try {
            Stat nodeStat = new Stat();
            byte[] content = globalZk().getData(path(POLICIES, namespaceName.toString()), null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);

            if (!policies.auth_policies.destination_auth.containsKey(topicUri)) {
                policies.auth_policies.destination_auth.put(topicUri, new TreeMap<String, Set<AuthAction>>());
            }

            policies.auth_policies.destination_auth.get(topicUri).put(role, actions);

            // Write the new policies to zookeeper
            globalZk().setData(path(POLICIES, namespaceName.toString()), jsonMapper().writeValueAsBytes(policies),
                    nodeStat.getVersion());

            // invalidate the local cache to force update
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));

            log.info("[{}] Successfully granted access for role {}: {} - topic {}", clientAppId(), role, actions,
                    topicUri);

        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to grant permissions on topic {}: Namespace does not exist", clientAppId(),
                    topicUri);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to grant permissions on topic {}: concurrent modification", clientAppId(),
                    topicUri);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to grant permissions for topic {}", clientAppId(), topicUri, e);
            throw new RestException(e);
        }
    }

    protected void internalGrantPermissionsOnTopic(String role, Set<AuthAction> actions) {
        // This operation should be reading from zookeeper and it should be allowed without having admin privileges
        validateAdminAccessForTenant(namespaceName.getTenant());
        validatePoliciesReadOnlyAccess();

        PartitionedTopicMetadata meta = getPartitionedTopicMetadata(topicName, true, false);
        int numPartitions = meta.partitions;
        if (numPartitions > 0) {
            for (int i = 0; i < numPartitions; i++) {
                TopicName topicNamePartition = topicName.getPartition(i);
                grantPermissions(topicNamePartition.toString(), role, actions);
            }
        }
        grantPermissions(topicName.toString(), role, actions);
    }

    protected void internalDeleteTopicForcefully(boolean authoritative) {
        validateAdminOperationOnTopic(authoritative);
        Topic topic = getTopicReference(topicName);
        try {
            topic.deleteForcefully().get();
        } catch (Exception e) {
            log.error("[{}] Failed to delete topic forcefully {}", clientAppId(), topicName, e);
            throw new RestException(e);
        }
    }

    private void revokePermissions(String topicUri, String role) {
        Stat nodeStat = new Stat();
        Policies policies;

        try {
            byte[] content = globalZk().getData(path(POLICIES, namespaceName.toString()), null, nodeStat);
            policies = jsonMapper().readValue(content, Policies.class);
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to revoke permissions on topic {}: Namespace does not exist", clientAppId(),
                    topicUri);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to revoke permissions on topic {}: concurrent modification", clientAppId(),
                    topicUri);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to revoke permissions for topic {}", clientAppId(), topicUri, e);
            throw new RestException(e);
        }

        if (!policies.auth_policies.destination_auth.containsKey(topicUri)
                || !policies.auth_policies.destination_auth.get(topicUri).containsKey(role)) {
            log.warn("[{}] Failed to revoke permission from role {} on topic: Not set at topic level",
                    clientAppId(), role, topicUri);
            throw new RestException(Status.PRECONDITION_FAILED, "Permissions are not set at the topic level");
        }

        policies.auth_policies.destination_auth.get(topicUri).remove(role);

        try {
            // Write the new policies to zookeeper
            String namespacePath = path(POLICIES, namespaceName.toString());
            globalZk().setData(namespacePath, jsonMapper().writeValueAsBytes(policies), nodeStat.getVersion());

            // invalidate the local cache to force update
            policiesCache().invalidate(namespacePath);
            globalZkCache().invalidate(namespacePath);

            log.info("[{}] Successfully revoke access for role {} - topic {}", clientAppId(), role,
                    topicUri);
        } catch (Exception e) {
            log.error("[{}] Failed to revoke permissions for topic {}", clientAppId(), topicUri, e);
            throw new RestException(e);
        }

    }

    protected void internalRevokePermissionsOnTopic(String role) {
        // This operation should be reading from zookeeper and it should be allowed without having admin privileges
        validateAdminAccessForTenant(namespaceName.getTenant());
        validatePoliciesReadOnlyAccess();

        PartitionedTopicMetadata meta = getPartitionedTopicMetadata(topicName, true, false);
        int numPartitions = meta.partitions;
        if (numPartitions > 0) {
            for (int i = 0; i < numPartitions; i++) {
                TopicName topicNamePartition = topicName.getPartition(i);
                revokePermissions(topicNamePartition.toString(), role);
            }
        }
        revokePermissions(topicName.toString(), role);
    }

    protected void internalCreatePartitionedTopic(int numPartitions) {
        validateAdminAccessForTenant(topicName.getTenant());
        if (numPartitions <= 0) {
            throw new RestException(Status.NOT_ACCEPTABLE, "Number of partitions should be more than 0");
        }
        validatePartitionTopicName(topicName.getLocalName());
        try {
            boolean topicExist = pulsar().getNamespaceService()
                    .getListOfTopics(topicName.getNamespaceObject(), PulsarApi.CommandGetTopicsOfNamespace.Mode.ALL)
                    .join()
                    .contains(topicName.toString());
            if (topicExist) {
                log.warn("[{}] Failed to create already existing topic {}", clientAppId(), topicName);
                throw new RestException(Status.CONFLICT, "This topic already exists");
            }
        } catch (Exception e) {
            log.error("[{}] Failed to create partitioned topic {}", clientAppId(), topicName, e);
            throw new RestException(e);
        }
        try {
            String path = ZkAdminPaths.partitionedTopicPath(topicName);
            byte[] data = jsonMapper().writeValueAsBytes(new PartitionedTopicMetadata(numPartitions));
            zkCreateOptimistic(path, data);
            tryCreatePartitionsAsync(numPartitions);
            // Sync data to all quorums and the observers
            zkSync(path);
            log.info("[{}] Successfully created partitioned topic {}", clientAppId(), topicName);
        } catch (KeeperException.NodeExistsException e) {
            log.warn("[{}] Failed to create already existing partitioned topic {}", clientAppId(), topicName);
            throw new RestException(Status.CONFLICT, "Partitioned topic already exists");
        } catch (KeeperException.BadVersionException e) {
                log.warn("[{}] Failed to create partitioned topic {}: concurrent modification", clientAppId(),
                        topicName);
                throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to create partitioned topic {}", clientAppId(), topicName, e);
            throw new RestException(e);
        }
    }

    protected void internalCreateNonPartitionedTopic(boolean authoritative) {
        validateAdminAccessForTenant(topicName.getTenant());
        validateNonPartitionTopicName(topicName.getLocalName());
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }

        validateTopicOwnership(topicName, authoritative);

        PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
        if (partitionMetadata.partitions > 0) {
            log.warn("[{}] Partitioned topic with the same name already exists {}", clientAppId(), topicName);
            throw new RestException(Status.CONFLICT, "This topic already exists");
        }

        try {
            Topic createdTopic = getOrCreateTopic(topicName);
            log.info("[{}] Successfully created non-partitioned topic {}", clientAppId(), createdTopic);
        } catch (Exception e) {
            log.error("[{}] Failed to create non-partitioned topic {}", clientAppId(), topicName, e);
            throw new RestException(e);
        }
    }

    /**
     * It updates number of partitions of an existing partitioned topic. It requires partitioned-topic to
     * already exist and number of new partitions must be greater than existing number of partitions. Decrementing
     * number of partitions requires deletion of topic which is not supported.
     *
     * Already created partitioned producers and consumers can't see newly created partitions and it requires to
     * recreate them at application so, newly created producers and consumers can connect to newly added partitions as
     * well. Therefore, it can violate partition ordering at producers until all producers are restarted at application.
     *
     * @param numPartitions
     */
    protected void internalUpdatePartitionedTopic(int numPartitions, boolean updateLocalTopicOnly) {
        validateAdminAccessForTenant(topicName.getTenant());
        // Only do the validation if it's the first hop.
        if (!updateLocalTopicOnly) {
            validatePartitionTopicUpdate(topicName.getLocalName(), numPartitions);
        }

        if (topicName.isGlobal() && isNamespaceReplicated(topicName.getNamespaceObject())) {
            Set<String> clusters = getNamespaceReplicatedClusters(topicName.getNamespaceObject());
            if (!clusters.contains(pulsar().getConfig().getClusterName())) {
                log.error("[{}] local cluster is not part of replicated cluster for namespace {}", clientAppId(),
                        topicName);
                throw new RestException(Status.FORBIDDEN, "Local cluster is not part of replicate cluster list");
            }
            try {
                createSubscriptions(topicName, numPartitions).get();
            } catch (Exception e) {
                if (e.getCause() instanceof RestException) {
                    throw (RestException) e.getCause();
                }
                log.error("[{}] Failed to update partitioned topic {}", clientAppId(), topicName, e);
                throw new RestException(e);
            }
            // if this cluster is the first hop which needs to coordinate with other clusters then update partitions in
            // other clusters and then update number of partitions.
            if (!updateLocalTopicOnly) {
                CompletableFuture<Void> updatePartition = new CompletableFuture<>();
                final String path = ZkAdminPaths.partitionedTopicPath(topicName);
                updatePartitionInOtherCluster(numPartitions, clusters).thenAccept((res) -> {
                    try {
                        byte[] data = jsonMapper().writeValueAsBytes(new PartitionedTopicMetadata(numPartitions));
                        globalZk().setData(path, data, -1, (rc, path1, ctx, stat) -> {
                            if (rc == KeeperException.Code.OK.intValue()) {
                                updatePartition.complete(null);
                            } else {
                                updatePartition.completeExceptionally(KeeperException
                                        .create(KeeperException.Code.get(rc), "failed to create update partitions"));
                            }
                        }, null);
                    } catch (Exception e) {
                        updatePartition.completeExceptionally(e);
                    }

                }).exceptionally(ex -> {
                    updatePartition.completeExceptionally(ex);
                    return null;
                });
                try {
                    updatePartition.get();
                } catch (Exception e) {
                    log.error("{} Failed to update number of partitions in zk for topic {} and partitions {}",
                            clientAppId(), topicName, numPartitions, e);
                    if (e.getCause() instanceof RestException) {
                        throw (RestException) e.getCause();
                    }
                    throw new RestException(e);
                }
            }
            return;
        }
        
        if (numPartitions <= 0) {
            throw new RestException(Status.NOT_ACCEPTABLE, "Number of partitions should be more than 0");
        }
        try {
            updatePartitionedTopic(topicName, numPartitions).get();
        } catch (Exception e) {
            if (e.getCause() instanceof RestException) {
                throw (RestException) e.getCause();
            }
            log.error("[{}] Failed to update partitioned topic {}", clientAppId(), topicName, e);
            throw new RestException(e);
        }
    }

    protected void internalCreateMissedPartitions() {
        PartitionedTopicMetadata metadata = getPartitionedTopicMetadata(topicName, false, false);
        if (metadata != null) {
            tryCreatePartitionsAsync(metadata.partitions);
        }
    }

    private CompletableFuture<Void> updatePartitionInOtherCluster(int numPartitions, Set<String> clusters) {
        List<CompletableFuture<Void>> results = new ArrayList<>(clusters.size() -1);
        clusters.forEach(cluster -> {
            if (cluster.equals(pulsar().getConfig().getClusterName())) {
                return;
            }
            results.add(pulsar().getBrokerService().getClusterPulsarAdmin(cluster).topics()
                    .updatePartitionedTopicAsync(topicName.toString(), numPartitions, true));
        });
        return FutureUtil.waitForAll(results);
    }

    protected PartitionedTopicMetadata internalGetPartitionedMetadata(boolean authoritative, boolean checkAllowAutoCreation) {
        PartitionedTopicMetadata metadata = getPartitionedTopicMetadata(topicName, authoritative, checkAllowAutoCreation);
        if (metadata.partitions > 1) {
            validateClientVersion();
        }
        return metadata;
    }

    protected void internalDeletePartitionedTopic(AsyncResponse asyncResponse, boolean authoritative, boolean force) {
        validateAdminAccessForTenant(topicName.getTenant());

        final CompletableFuture<Void> future = new CompletableFuture<>();

        PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
        final int numPartitions = partitionMetadata.partitions;
        if (numPartitions > 0) {
            final AtomicInteger count = new AtomicInteger(numPartitions);
            for (int i = 0; i < numPartitions; i++) {
                TopicName topicNamePartition = topicName.getPartition(i);
                try {
                    pulsar().getAdminClient().topics().deleteAsync(topicNamePartition.toString(), force)
                            .whenComplete((r, ex) -> {
                                if (ex != null) {
                                    if (ex instanceof NotFoundException) {
                                        // if the sub-topic is not found, the client might not have called create
                                        // producer or it might have been deleted earlier, so we ignore the 404 error.
                                        // For all other exception, we fail the delete partition method even if a single
                                        // partition is failed to be deleted
                                        if (log.isDebugEnabled()) {
                                            log.debug("[{}] Partition not found: {}", clientAppId(),
                                                    topicNamePartition);
                                        }
                                    } else {
                                        log.error("[{}] Failed to delete partition {}", clientAppId(),
                                                topicNamePartition, ex);
                                        future.completeExceptionally(ex);
                                        return;
                                    }
                                } else {
                                    log.info("[{}] Deleted partition {}", clientAppId(), topicNamePartition);
                                }
                                if (count.decrementAndGet() == 0) {
                                    future.complete(null);
                                }
                            });
                } catch (Exception e) {
                    log.error("[{}] Failed to delete partition {}", clientAppId(), topicNamePartition, e);
                    future.completeExceptionally(e);
                }
            }
        } else {
            future.complete(null);
        }

        future.whenComplete((r, ex) -> {
            if (ex != null) {
                if (ex instanceof PreconditionFailedException) {
                    asyncResponse.resume(
                            new RestException(Status.PRECONDITION_FAILED, "Topic has active producers/subscriptions"));
                    return;
                } else if (ex instanceof PulsarAdminException) {
                    asyncResponse.resume(new RestException((PulsarAdminException) ex));
                    return;
                } else {
                    asyncResponse.resume(new RestException(ex));
                    return;
                }
            }

            // Only tries to delete the znode for partitioned topic when all its partitions are successfully deleted
            String path = path(PARTITIONED_TOPIC_PATH_ZNODE, namespaceName.toString(), domain(),
                    topicName.getEncodedLocalName());
            try {
                globalZk().delete(path, -1);
                globalZkCache().invalidate(path);
                // Sync data to all quorums and the observers
                zkSync(path);
                log.info("[{}] Deleted partitioned topic {}", clientAppId(), topicName);
                asyncResponse.resume(Response.noContent().build());
            } catch (KeeperException.NoNodeException nne) {
                asyncResponse.resume(new RestException(Status.NOT_FOUND, "Partitioned topic does not exist"));
            } catch (KeeperException.BadVersionException e) {
                log.warn("[{}] Failed to delete partitioned topic {}: concurrent modification", clientAppId(),
                        topicName);
                asyncResponse.resume(new RestException(Status.CONFLICT, "Concurrent modification"));
            } catch (Exception e) {
                log.error("[{}] Failed to delete partitioned topic {}", clientAppId(), topicName, e);
                asyncResponse.resume(new RestException(e));
            }
        });
    }

    protected void internalUnloadTopic(AsyncResponse asyncResponse, boolean authoritative) {
        log.info("[{}] Unloading topic {}", clientAppId(), topicName);
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (topicName.isPartitioned()) {
            internalUnloadNonPartitionedTopic(asyncResponse, authoritative);
        } else {
            getPartitionedTopicMetadataAsync(topicName, authoritative, false).whenComplete((meta, t) -> {
                if (meta.partitions > 0) {
                    final List<CompletableFuture<Void>> futures = Lists.newArrayList();

                    for (int i = 0; i < meta.partitions; i++) {
                        TopicName topicNamePartition = topicName.getPartition(i);
                        try {
                            futures.add(pulsar().getAdminClient().topics().unloadAsync(topicNamePartition.toString()));
                        } catch (Exception e) {
                            log.error("[{}] Failed to unload topic {}", clientAppId(), topicNamePartition, e);
                            asyncResponse.resume(new RestException(e));
                            return;
                        }
                    }

                    FutureUtil.waitForAll(futures).handle((result, exception) -> {
                        if (exception != null) {
                            Throwable th = exception.getCause();
                            if (th instanceof NotFoundException) {
                                asyncResponse.resume(new RestException(Status.NOT_FOUND, th.getMessage()));
                            } else {
                                log.error("[{}] Failed to unload topic {}", clientAppId(), topicName, exception);
                                asyncResponse.resume(new RestException(exception));
                            }
                            return null;
                        }

                        asyncResponse.resume(Response.noContent().build());
                        return null;
                    });
                } else {
                    internalUnloadNonPartitionedTopic(asyncResponse, authoritative);
                }
            }).exceptionally(t -> {
                Throwable th = t.getCause();
                asyncResponse.resume(new RestException(th));
                return null;
            });
        }
    }

    private void internalUnloadNonPartitionedTopic(AsyncResponse asyncResponse, boolean authoritative) {
        validateAdminAccessForTenant(topicName.getTenant());
        validateTopicOwnership(topicName, authoritative);

        Topic topic = getTopicReference(topicName);
        topic.close(false).whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("[{}] Failed to unload topic {}, {}", clientAppId(), topicName, ex.getMessage(), ex);
                asyncResponse.resume(new RestException(ex));

            } else {
                log.info("[{}] Successfully unloaded topic {}", clientAppId(), topicName);
                asyncResponse.resume(Response.noContent().build());
            }
        });
    }

    protected void internalDeleteTopic(boolean authoritative, boolean force) {
        if (force) {
            internalDeleteTopicForcefully(authoritative);
        } else {
            internalDeleteTopic(authoritative);
        }
    }

    protected void internalDeleteTopic(boolean authoritative) {
        validateAdminOperationOnTopic(authoritative);
        Topic topic = getTopicReference(topicName);

        // v2 topics have a global name so check if the topic is replicated.
        if (topic.isReplicated()) {
            // Delete is disallowed on global topic
            final List<String> clusters = topic.getReplicators().keys();
            log.error("[{}] Delete forbidden topic {} is replicated on clusters {}",
                    clientAppId(), topicName, clusters);
            throw new RestException(Status.FORBIDDEN, "Delete forbidden topic is replicated on clusters " + clusters);
        }

        try {
            topic.delete().get();
            log.info("[{}] Successfully removed topic {}", clientAppId(), topicName);
        } catch (Exception e) {
            Throwable t = e.getCause();
            log.error("[{}] Failed to delete topic {}", clientAppId(), topicName, t);
            if (t instanceof TopicBusyException) {
                throw new RestException(Status.PRECONDITION_FAILED, "Topic has active producers/subscriptions");
            } else {
                throw new RestException(t);
            }
        }
    }

    protected void internalGetSubscriptions(AsyncResponse asyncResponse, boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (topicName.isPartitioned()) {
            internalGetSubscriptionsForNonPartitionedTopic(asyncResponse, authoritative);
        } else {
            PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
            if (partitionMetadata.partitions > 0) {
                try {
                    // get the subscriptions only from the 1st partition since all the other partitions will have the same
                    // subscriptions
                    pulsar().getAdminClient().topics().getSubscriptionsAsync(topicName.getPartition(0).toString())
                        .whenComplete((r, ex) -> {
                            if (ex != null) {
                                log.warn("[{}] Failed to get list of subscriptions for {}: {}", clientAppId(),
                                    topicName, ex.getMessage());

                                if (ex instanceof PulsarAdminException) {
                                    PulsarAdminException pae = (PulsarAdminException) ex;
                                    if (pae.getStatusCode() == Status.NOT_FOUND.getStatusCode()) {
                                        asyncResponse.resume(new RestException(Status.NOT_FOUND,
                                            "Internal topics have not been generated yet"));
                                        return;
                                    } else {
                                        asyncResponse.resume(new RestException(pae));
                                        return;
                                    }
                                } else {
                                    asyncResponse.resume(new RestException(ex));
                                    return;
                                }
                            }
                            final List<String> subscriptions = Lists.newArrayList();
                            subscriptions.addAll(r);
                            asyncResponse.resume(subscriptions);
                            return;
                        });
                } catch (Exception e) {
                    log.error("[{}] Failed to get list of subscriptions for {}", clientAppId(), topicName, e);
                    asyncResponse.resume(e);
                    return;
                }
            } else {
                internalGetSubscriptionsForNonPartitionedTopic(asyncResponse, authoritative);
            }
        }
    }

    private void internalGetSubscriptionsForNonPartitionedTopic(AsyncResponse asyncResponse, boolean authoritative) {
        validateAdminOperationOnTopic(authoritative);
        Topic topic = getTopicReference(topicName);
        try {
            final List<String> subscriptions = Lists.newArrayList();
            topic.getSubscriptions().forEach((subName, sub) -> subscriptions.add(subName));
            asyncResponse.resume(subscriptions);
            return;
        } catch (Exception e) {
            log.error("[{}] Failed to get list of subscriptions for {}", clientAppId(), topicName, e);
            asyncResponse.resume(new RestException(e));
            return;
        }
    }

    protected TopicStats internalGetStats(boolean authoritative, boolean getPreciseBacklog) {
        validateAdminAndClientPermission();
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        validateTopicOwnership(topicName, authoritative);
        Topic topic = getTopicReference(topicName);
        return topic.getStats(getPreciseBacklog);
    }

    protected PersistentTopicInternalStats internalGetInternalStats(boolean authoritative) {
        validateAdminAndClientPermission();
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        validateTopicOwnership(topicName, authoritative);
        Topic topic = getTopicReference(topicName);
        return topic.getInternalStats();
    }

    protected void internalGetManagedLedgerInfo(AsyncResponse asyncResponse) {
        validateAdminAccessForTenant(topicName.getTenant());
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        String managedLedger = topicName.getPersistenceNamingEncoding();
        pulsar().getManagedLedgerFactory().asyncGetManagedLedgerInfo(managedLedger, new ManagedLedgerInfoCallback() {
            @Override
            public void getInfoComplete(ManagedLedgerInfo info, Object ctx) {
                asyncResponse.resume((StreamingOutput) output -> {
                    jsonMapper().writer().writeValue(output, info);
                });
            }

            @Override
            public void getInfoFailed(ManagedLedgerException exception, Object ctx) {
                asyncResponse.resume(exception);
            }
        }, null);
    }

    protected void internalGetPartitionedStats(AsyncResponse asyncResponse, boolean authoritative,
            boolean perPartition, boolean getPreciseBacklog) {
        PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
        if (partitionMetadata.partitions == 0) {
            throw new RestException(Status.NOT_FOUND, "Partitioned Topic not found");
        }
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        PartitionedTopicStats stats = new PartitionedTopicStats(partitionMetadata);

        List<CompletableFuture<TopicStats>> topicStatsFutureList = Lists.newArrayList();
        for (int i = 0; i < partitionMetadata.partitions; i++) {
            try {
                topicStatsFutureList
                        .add(pulsar().getAdminClient().topics().getStatsAsync((topicName.getPartition(i).toString()), getPreciseBacklog));
            } catch (PulsarServerException e) {
                asyncResponse.resume(new RestException(e));
                return;
            }
        }

        FutureUtil.waitForAll(topicStatsFutureList).handle((result, exception) -> {
            CompletableFuture<TopicStats> statFuture = null;
            for (int i = 0; i < topicStatsFutureList.size(); i++) {
                statFuture = topicStatsFutureList.get(i);
                if (statFuture.isDone() && !statFuture.isCompletedExceptionally()) {
                    try {
                        stats.add(statFuture.get());
                        if (perPartition) {
                            stats.partitions.put(topicName.getPartition(i).toString(), statFuture.get());
                        }
                    } catch (Exception e) {
                        asyncResponse.resume(new RestException(e));
                        return null;
                    }
                }
            }
            if (perPartition && stats.partitions.isEmpty()) {
                String path = ZkAdminPaths.partitionedTopicPath(topicName);
                try {
                    boolean zkPathExists = zkPathExists(path);
                    if (zkPathExists) {
                        stats.partitions.put(topicName.toString(), new TopicStats());
                    } else {
                        asyncResponse.resume(
                                new RestException(Status.NOT_FOUND, "Internal topics have not been generated yet"));
                        return null;
                    }
                } catch (KeeperException | InterruptedException e) {
                    asyncResponse.resume(new RestException(e));
                    return null;
                }
            }
            asyncResponse.resume(stats);
            return null;
        });
    }

    protected void internalGetPartitionedStatsInternal(AsyncResponse asyncResponse, boolean authoritative) {
        PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
        if (partitionMetadata.partitions == 0) {
            throw new RestException(Status.NOT_FOUND, "Partitioned Topic not found");
        }
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        PartitionedTopicInternalStats stats = new PartitionedTopicInternalStats(partitionMetadata);

        List<CompletableFuture<PersistentTopicInternalStats>> topicStatsFutureList = Lists.newArrayList();
        for (int i = 0; i < partitionMetadata.partitions; i++) {
            try {
                topicStatsFutureList.add(pulsar().getAdminClient().topics()
                        .getInternalStatsAsync((topicName.getPartition(i).toString())));
            } catch (PulsarServerException e) {
                asyncResponse.resume(new RestException(e));
                return;
            }
        }

        FutureUtil.waitForAll(topicStatsFutureList).handle((result, exception) -> {
            CompletableFuture<PersistentTopicInternalStats> statFuture = null;
            for (int i = 0; i < topicStatsFutureList.size(); i++) {
                statFuture = topicStatsFutureList.get(i);
                if (statFuture.isDone() && !statFuture.isCompletedExceptionally()) {
                    try {
                        stats.partitions.put(topicName.getPartition(i).toString(), statFuture.get());
                    } catch (Exception e) {
                        asyncResponse.resume(new RestException(e));
                        return null;
                    }
                }
            }
            asyncResponse.resume(!stats.partitions.isEmpty() ? stats
                    : new RestException(Status.NOT_FOUND, "Internal topics have not been generated yet"));
            return null;
        });
    }

    protected void internalDeleteSubscription(AsyncResponse asyncResponse, String subName, boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (topicName.isPartitioned()) {
            internalDeleteSubscriptionForNonPartitionedTopic(asyncResponse, subName, authoritative);
        } else {
            PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
            if (partitionMetadata.partitions > 0) {
                final List<CompletableFuture<Void>> futures = Lists.newArrayList();

                for (int i = 0; i < partitionMetadata.partitions; i++) {
                    TopicName topicNamePartition = topicName.getPartition(i);
                    try {
                        futures.add(pulsar().getAdminClient().topics()
                            .deleteSubscriptionAsync(topicNamePartition.toString(), subName));
                    } catch (Exception e) {
                        log.error("[{}] Failed to delete subscription {} {}", clientAppId(), topicNamePartition, subName,
                            e);
                        asyncResponse.resume(new RestException(e));
                        return;
                    }
                }

                FutureUtil.waitForAll(futures).handle((result, exception) -> {
                    if (exception != null) {
                        Throwable t = exception.getCause();
                        if (t instanceof NotFoundException) {
                            asyncResponse.resume(new RestException(Status.NOT_FOUND, "Subscription not found"));
                            return null;
                        } else if (t instanceof PreconditionFailedException) {
                            asyncResponse.resume(new RestException(Status.PRECONDITION_FAILED,
                                "Subscription has active connected consumers"));
                            return null;
                        } else {
                            log.error("[{}] Failed to delete subscription {} {}", clientAppId(), topicName, subName, t);
                            asyncResponse.resume(new RestException(t));
                            return null;
                        }
                    }

                    asyncResponse.resume(Response.noContent().build());
                    return null;
                });
            } else {
                internalDeleteSubscriptionForNonPartitionedTopic(asyncResponse, subName, authoritative);
            }
        }
    }

    private void internalDeleteSubscriptionForNonPartitionedTopic(AsyncResponse asyncResponse, String subName, boolean authoritative) {
        validateAdminAccessForSubscriber(subName, authoritative);
        Topic topic = getTopicReference(topicName);
        try {
            Subscription sub = topic.getSubscription(subName);
            checkNotNull(sub);
            sub.delete().get();
            log.info("[{}][{}] Deleted subscription {}", clientAppId(), topicName, subName);
            asyncResponse.resume(Response.noContent().build());
        } catch (Exception e) {
            Throwable t = e.getCause();
            if (e instanceof NullPointerException) {
                asyncResponse.resume(new RestException(Status.NOT_FOUND, "Subscription not found"));
            } else if (t instanceof SubscriptionBusyException) {
                asyncResponse.resume(new RestException(Status.PRECONDITION_FAILED,
                    "Subscription has active connected consumers"));
            } else {
                log.error("[{}] Failed to delete subscription {} {}", clientAppId(), topicName, subName, e);
                asyncResponse.resume(new RestException(t));
            }
        }
    }

    protected void internalSkipAllMessages(AsyncResponse asyncResponse, String subName, boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (topicName.isPartitioned()) {
            internalSkipAllMessagesForNonPartitionedTopic(asyncResponse, subName, authoritative);
        } else {
            PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
            if (partitionMetadata.partitions > 0) {
                final List<CompletableFuture<Void>> futures = Lists.newArrayList();

                for (int i = 0; i < partitionMetadata.partitions; i++) {
                    TopicName topicNamePartition = topicName.getPartition(i);
                    try {
                        futures.add(pulsar().getAdminClient().topics().skipAllMessagesAsync(topicNamePartition.toString(),
                            subName));
                    } catch (Exception e) {
                        log.error("[{}] Failed to skip all messages {} {}", clientAppId(), topicNamePartition, subName, e);
                        asyncResponse.resume(new RestException(e));
                        return;
                    }
                }

                FutureUtil.waitForAll(futures).handle((result, exception) -> {
                    if (exception != null) {
                        Throwable t = exception.getCause();
                        if (t instanceof NotFoundException) {
                            asyncResponse.resume(new RestException(Status.NOT_FOUND, "Subscription not found"));
                            return null;
                        } else {
                            log.error("[{}] Failed to skip all messages {} {}", clientAppId(), topicName, subName, t);
                            asyncResponse.resume(new RestException(t));
                            return null;
                        }
                    }

                    asyncResponse.resume(Response.noContent().build());
                    return null;
                });
            } else {
                internalSkipAllMessagesForNonPartitionedTopic(asyncResponse, subName, authoritative);
            }
        }
    }

    private void internalSkipAllMessagesForNonPartitionedTopic(AsyncResponse asyncResponse, String subName, boolean authoritative) {
        validateAdminAccessForSubscriber(subName, authoritative);
        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        BiConsumer<Void, Throwable> biConsumer = (v, ex) -> {
            if (ex != null) {
                asyncResponse.resume(new RestException(ex));
                log.error("[{}] Failed to skip all messages {} {}", clientAppId(), topicName, subName, ex);
            } else {
                asyncResponse.resume(Response.noContent().build());
                log.info("[{}] Cleared backlog on {} {}", clientAppId(), topicName, subName);
            }
        };
        try {
            if (subName.startsWith(topic.getReplicatorPrefix())) {
                String remoteCluster = PersistentReplicator.getRemoteCluster(subName);
                PersistentReplicator repl = (PersistentReplicator) topic.getPersistentReplicator(remoteCluster);
                checkNotNull(repl);
                repl.clearBacklog().whenComplete(biConsumer);
            } else {
                PersistentSubscription sub = topic.getSubscription(subName);
                checkNotNull(sub);
                sub.clearBacklog().whenComplete(biConsumer);
            }
        } catch (Exception e) {
            if (e instanceof NullPointerException) {
                asyncResponse.resume(new RestException(Status.NOT_FOUND, "Subscription not found"));
            } else {
                asyncResponse.resume(new RestException(e));
            }
        }
    }

    protected void internalSkipMessages(String subName, int numMessages, boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
        if (partitionMetadata.partitions > 0) {
            throw new RestException(Status.METHOD_NOT_ALLOWED, "Skip messages on a partitioned topic is not allowed");
        }
        validateAdminAccessForSubscriber(subName, authoritative);
        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        try {
            if (subName.startsWith(topic.getReplicatorPrefix())) {
                String remoteCluster = PersistentReplicator.getRemoteCluster(subName);
                PersistentReplicator repl = (PersistentReplicator) topic.getPersistentReplicator(remoteCluster);
                checkNotNull(repl);
                repl.skipMessages(numMessages).get();
            } else {
                PersistentSubscription sub = topic.getSubscription(subName);
                checkNotNull(sub);
                sub.skipMessages(numMessages).get();
            }
            log.info("[{}] Skipped {} messages on {} {}", clientAppId(), numMessages, topicName, subName);
        } catch (NullPointerException npe) {
            throw new RestException(Status.NOT_FOUND, "Subscription not found");
        } catch (Exception exception) {
            log.error("[{}] Failed to skip {} messages {} {}", clientAppId(), numMessages, topicName, subName,
                    exception);
            throw new RestException(exception);
        }
    }

    protected void internalExpireMessagesForAllSubscriptions(AsyncResponse asyncResponse, int expireTimeInSeconds,
            boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (topicName.isPartitioned()) {
            internalExpireMessagesForAllSubscriptionsForNonPartitionedTopic(asyncResponse, expireTimeInSeconds, authoritative);
        } else {
            PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
            if (partitionMetadata.partitions > 0) {
                final List<CompletableFuture<Void>> futures = Lists.newArrayList();

                // expire messages for each partition topic
                for (int i = 0; i < partitionMetadata.partitions; i++) {
                    TopicName topicNamePartition = topicName.getPartition(i);
                    try {
                        futures.add(pulsar().getAdminClient().topics().expireMessagesForAllSubscriptionsAsync(
                            topicNamePartition.toString(), expireTimeInSeconds));
                    } catch (Exception e) {
                        log.error("[{}] Failed to expire messages up to {} on {}", clientAppId(), expireTimeInSeconds,
                            topicNamePartition, e);
                        asyncResponse.resume(new RestException(e));
                        return;
                    }
                }

                FutureUtil.waitForAll(futures).handle((result, exception) -> {
                    if (exception != null) {
                        Throwable t = exception.getCause();
                        log.error("[{}] Failed to expire messages up to {} on {}", clientAppId(), expireTimeInSeconds,
                            topicName, t);
                        asyncResponse.resume(new RestException(t));
                        return null;
                    }

                    asyncResponse.resume(Response.noContent().build());
                    return null;
                });
            } else {
                internalExpireMessagesForAllSubscriptionsForNonPartitionedTopic(asyncResponse, expireTimeInSeconds, authoritative);
            }
        }
    }

    private void internalExpireMessagesForAllSubscriptionsForNonPartitionedTopic(AsyncResponse asyncResponse, int expireTimeInSeconds,
            boolean authoritative) {
        // validate ownership and redirect if current broker is not owner
        validateAdminOperationOnTopic(authoritative);

        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        final AtomicReference<Throwable> exception = new AtomicReference<>();

        topic.getReplicators().forEach((subName, replicator) -> {
            try {
                internalExpireMessagesForSinglePartition(subName, expireTimeInSeconds, authoritative);
            } catch (Throwable t) {
                exception.set(t);
            }
        });

        topic.getSubscriptions().forEach((subName, subscriber) -> {
            try {
                internalExpireMessagesForSinglePartition(subName, expireTimeInSeconds, authoritative);
            } catch (Throwable t) {
                exception.set(t);
            }
        });

        if (exception.get() != null) {
            if (exception.get() instanceof WebApplicationException) {
                WebApplicationException wae = (WebApplicationException) exception.get();
                asyncResponse.resume(wae);
                return;
            } else {
                asyncResponse.resume(new RestException(exception.get()));
                return;
            }
        }

        asyncResponse.resume(Response.noContent().build());
    }

    protected void internalResetCursor(AsyncResponse asyncResponse, String subName, long timestamp,
            boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (topicName.isPartitioned()) {
            internalResetCursorForNonPartitionedTopic(asyncResponse, subName, timestamp, authoritative);
        } else {
            PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
            final int numPartitions = partitionMetadata.partitions;
            if (numPartitions > 0) {
                final CompletableFuture<Void> future = new CompletableFuture<>();
                final AtomicInteger count = new AtomicInteger(numPartitions);
                final AtomicInteger failureCount = new AtomicInteger(0);
                final AtomicReference<Throwable> partitionException = new AtomicReference<>();

                for (int i = 0; i < numPartitions; i++) {
                    TopicName topicNamePartition = topicName.getPartition(i);
                    try {
                        pulsar().getAdminClient().topics()
                            .resetCursorAsync(topicNamePartition.toString(), subName, timestamp).handle((r, ex) -> {
                            if (ex != null) {
                                if (ex instanceof PreconditionFailedException) {
                                    // throw the last exception if all partitions get this error
                                    // any other exception on partition is reported back to user
                                    failureCount.incrementAndGet();
                                    partitionException.set(ex);
                                } else {
                                    log.warn("[{}] [{}] Failed to reset cursor on subscription {} to time {}",
                                        clientAppId(), topicNamePartition, subName, timestamp, ex);
                                    future.completeExceptionally(ex);
                                    return null;
                                }
                            }

                            if (count.decrementAndGet() == 0) {
                                future.complete(null);
                            }

                            return null;
                        });
                    } catch (Exception e) {
                        log.warn("[{}] [{}] Failed to reset cursor on subscription {} to time {}", clientAppId(),
                            topicNamePartition, subName, timestamp, e);
                        future.completeExceptionally(e);
                    }
                }

                future.whenComplete((r, ex) -> {
                    if (ex != null) {
                        if (ex instanceof PulsarAdminException) {
                            asyncResponse.resume(new RestException((PulsarAdminException) ex));
                            return;
                        } else {
                            asyncResponse.resume(new RestException(ex));
                            return;
                        }
                    }

                    // report an error to user if unable to reset for all partitions
                    if (failureCount.get() == numPartitions) {
                        log.warn("[{}] [{}] Failed to reset cursor on subscription {} to time {}", clientAppId(), topicName,
                            subName, timestamp, partitionException.get());
                        asyncResponse.resume(
                            new RestException(Status.PRECONDITION_FAILED, partitionException.get().getMessage()));
                        return;
                    } else if (failureCount.get() > 0) {
                        log.warn("[{}] [{}] Partial errors for reset cursor on subscription {} to time {}", clientAppId(),
                            topicName, subName, timestamp, partitionException.get());
                    }

                    asyncResponse.resume(Response.noContent().build());
                });
            } else {
                internalResetCursorForNonPartitionedTopic(asyncResponse, subName, timestamp, authoritative);
            }
        }
    }

    private void internalResetCursorForNonPartitionedTopic(AsyncResponse asyncResponse, String subName, long timestamp,
                                       boolean authoritative) {
        validateAdminAccessForSubscriber(subName, authoritative);
        log.info("[{}] [{}] Received reset cursor on subscription {} to time {}", clientAppId(), topicName, subName,
            timestamp);
        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        if (topic == null) {
            asyncResponse.resume(new RestException(Status.NOT_FOUND, "Topic not found"));
            return;
        }
        try {
            PersistentSubscription sub = topic.getSubscription(subName);
            checkNotNull(sub);
            sub.resetCursor(timestamp).get();
            log.info("[{}] [{}] Reset cursor on subscription {} to time {}", clientAppId(), topicName, subName,
                timestamp);
            asyncResponse.resume(Response.noContent().build());
        } catch (Exception e) {
            Throwable t = e.getCause();
            log.warn("[{}] [{}] Failed to reset cursor on subscription {} to time {}", clientAppId(), topicName,
                subName, timestamp, e);
            if (e instanceof NullPointerException) {
                asyncResponse.resume(new RestException(Status.NOT_FOUND, "Subscription not found"));
            } else if (e instanceof NotAllowedException) {
                asyncResponse.resume(new RestException(Status.METHOD_NOT_ALLOWED, e.getMessage()));
            } else if (t instanceof SubscriptionInvalidCursorPosition) {
                asyncResponse.resume(new RestException(Status.PRECONDITION_FAILED,
                    "Unable to find position for timestamp specified -" + t.getMessage()));
            } else {
                asyncResponse.resume(new RestException(e));
            }
        }
    }

    protected void internalCreateSubscription(AsyncResponse asyncResponse, String subscriptionName,
            MessageIdImpl messageId, boolean authoritative, boolean replicated) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        final MessageIdImpl targetMessageId = messageId == null ? (MessageIdImpl) MessageId.earliest : messageId;
        log.info("[{}][{}] Creating subscription {} at message id {}", clientAppId(), topicName, subscriptionName,
                targetMessageId);
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (topicName.isPartitioned()) {
            internalCreateSubscriptionForNonPartitionedTopic(asyncResponse, subscriptionName, targetMessageId, authoritative, replicated);
        } else {
            PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
            final int numPartitions = partitionMetadata.partitions;
            if (numPartitions > 0) {
                final CompletableFuture<Void> future = new CompletableFuture<>();
                final AtomicInteger count = new AtomicInteger(numPartitions);
                final AtomicInteger failureCount = new AtomicInteger(0);
                final AtomicReference<Throwable> partitionException = new AtomicReference<>();

                // Create the subscription on each partition
                for (int i = 0; i < numPartitions; i++) {
                    TopicName topicNamePartition = topicName.getPartition(i);
                    try {
                        pulsar().getAdminClient().topics()
                            .createSubscriptionAsync(topicNamePartition.toString(), subscriptionName, targetMessageId)
                            .handle((r, ex) -> {
                                if (ex != null) {
                                    // fail the operation on unknown exception or if all the partitioned failed due to
                                    // subscription-already-exist
                                    if (failureCount.incrementAndGet() == numPartitions
                                        || !(ex instanceof PulsarAdminException.ConflictException)) {
                                        partitionException.set(ex);
                                    }
                                }

                                if (count.decrementAndGet() == 0) {
                                    future.complete(null);
                                }

                                return null;
                            });
                    } catch (Exception e) {
                        log.warn("[{}] [{}] Failed to create subscription {} at message id {}", clientAppId(),
                            topicNamePartition, subscriptionName, targetMessageId, e);
                        future.completeExceptionally(e);
                    }
                }

                future.whenComplete((r, ex) -> {
                    if (ex != null) {
                        if (ex instanceof PulsarAdminException) {
                            asyncResponse.resume(new RestException((PulsarAdminException) ex));
                            return;
                        } else {
                            asyncResponse.resume(new RestException(ex));
                            return;
                        }
                    }

                    if (partitionException.get() != null) {
                        log.warn("[{}] [{}] Failed to create subscription {} at message id {}", clientAppId(), topicName,
                            subscriptionName, targetMessageId, partitionException.get());
                        if (partitionException.get() instanceof PulsarAdminException) {
                            asyncResponse.resume(new RestException((PulsarAdminException) partitionException.get()));
                            return;
                        } else {
                            asyncResponse.resume(new RestException(partitionException.get()));
                            return;
                        }
                    }

                    asyncResponse.resume(Response.noContent().build());
                });
            } else {
                internalCreateSubscriptionForNonPartitionedTopic(asyncResponse, subscriptionName, targetMessageId, authoritative, replicated);
            }
        }
    }

    private void internalCreateSubscriptionForNonPartitionedTopic(AsyncResponse asyncResponse, String subscriptionName,
              MessageIdImpl targetMessageId, boolean authoritative, boolean replicated) {
        validateAdminAccessForSubscriber(subscriptionName, authoritative);

        PersistentTopic topic = (PersistentTopic) getOrCreateTopic(topicName);

        if (topic.getSubscriptions().containsKey(subscriptionName)) {
            asyncResponse.resume(new RestException(Status.CONFLICT, "Subscription already exists for topic"));
            return;
        }

        try {
            PersistentSubscription subscription = (PersistentSubscription) topic
                .createSubscription(subscriptionName, InitialPosition.Latest, replicated).get();
            // Mark the cursor as "inactive" as it was created without a real consumer connected
            subscription.deactivateCursor();
            subscription.resetCursor(PositionImpl.get(targetMessageId.getLedgerId(), targetMessageId.getEntryId()))
                .get();
        } catch (Throwable e) {
            Throwable t = e.getCause();
            log.warn("[{}] [{}] Failed to create subscription {} at message id {}", clientAppId(), topicName,
                subscriptionName, targetMessageId, e);
            if (t instanceof SubscriptionInvalidCursorPosition) {
                asyncResponse.resume(new RestException(Status.PRECONDITION_FAILED,
                    "Unable to find position for position specified: " + t.getMessage()));
                return;
            } else {
                asyncResponse.resume(new RestException(e));
                return;
            }
        }

        log.info("[{}][{}] Successfully created subscription {} at message id {}", clientAppId(), topicName,
            subscriptionName, targetMessageId);
        asyncResponse.resume(Response.noContent().build());
    }

    protected void internalResetCursorOnPosition(String subName, boolean authoritative, MessageIdImpl messageId) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        log.info("[{}][{}] received reset cursor on subscription {} to position {}", clientAppId(), topicName,
                subName, messageId);
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (!topicName.isPartitioned() && getPartitionedTopicMetadata(topicName, authoritative, false).partitions > 0) {
            log.warn("[{}] Not supported operation on partitioned-topic {} {}", clientAppId(), topicName,
                    subName);
            throw new RestException(Status.METHOD_NOT_ALLOWED,
                    "Reset-cursor at position is not allowed for partitioned-topic");
        } else {
            validateAdminAccessForSubscriber(subName, authoritative);
            PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
            if (topic == null) {
                throw new RestException(Status.NOT_FOUND, "Topic not found");
            }
            try {
                PersistentSubscription sub = topic.getSubscription(subName);
                checkNotNull(sub);
                sub.resetCursor(PositionImpl.get(messageId.getLedgerId(), messageId.getEntryId())).get();
                log.info("[{}][{}] successfully reset cursor on subscription {} to position {}", clientAppId(),
                        topicName, subName, messageId);
            } catch (Exception e) {
                Throwable t = e.getCause();
                log.warn("[{}] [{}] Failed to reset cursor on subscription {} to position {}", clientAppId(),
                        topicName, subName, messageId, e);
                if (e instanceof NullPointerException) {
                    throw new RestException(Status.NOT_FOUND, "Subscription not found");
                } else if (t instanceof SubscriptionInvalidCursorPosition) {
                    throw new RestException(Status.PRECONDITION_FAILED,
                            "Unable to find position for position specified: " + t.getMessage());
                } else {
                    throw new RestException(e);
                }
            }
        }
    }

    protected Response internalPeekNthMessage(String subName, int messagePosition, boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (!topicName.isPartitioned() && getPartitionedTopicMetadata(topicName, authoritative, false).partitions > 0) {
            throw new RestException(Status.METHOD_NOT_ALLOWED, "Peek messages on a partitioned topic is not allowed");
        }
        validateAdminAccessForSubscriber(subName, authoritative);
        if (!(getTopicReference(topicName) instanceof PersistentTopic)) {
            log.error("[{}] Not supported operation of non-persistent topic {} {}", clientAppId(), topicName,
                    subName);
            throw new RestException(Status.METHOD_NOT_ALLOWED,
                    "Skip messages on a non-persistent topic is not allowed");
        }
        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        PersistentReplicator repl = null;
        PersistentSubscription sub = null;
        Entry entry = null;
        if (subName.startsWith(topic.getReplicatorPrefix())) {
            repl = getReplicatorReference(subName, topic);
        } else {
            sub = (PersistentSubscription) getSubscriptionReference(subName, topic);
        }
        try {
            if (subName.startsWith(topic.getReplicatorPrefix())) {
                entry = repl.peekNthMessage(messagePosition).get();
            } else {
                entry = sub.peekNthMessage(messagePosition).get();
            }
            checkNotNull(entry);
            PositionImpl pos = (PositionImpl) entry.getPosition();
            ByteBuf metadataAndPayload = entry.getDataBuffer();

            // moves the readerIndex to the payload
            MessageMetadata metadata = Commands.parseMessageMetadata(metadataAndPayload);

            ResponseBuilder responseBuilder = Response.ok();
            responseBuilder.header("X-Pulsar-Message-ID", pos.toString());
            for (KeyValue keyValue : metadata.getPropertiesList()) {
                responseBuilder.header("X-Pulsar-PROPERTY-" + keyValue.getKey(), keyValue.getValue());
            }
            if (metadata.hasPublishTime()) {
                responseBuilder.header("X-Pulsar-publish-time", DateFormatter.format(metadata.getPublishTime()));
            }
            if (metadata.hasEventTime()) {
                responseBuilder.header("X-Pulsar-event-time", DateFormatter.format(metadata.getEventTime()));
            }
            if (metadata.hasNumMessagesInBatch()) {
                responseBuilder.header("X-Pulsar-num-batch-message", metadata.getNumMessagesInBatch());
            }

            // Decode if needed
            CompressionCodec codec = CompressionCodecProvider.getCompressionCodec(metadata.getCompression());
            ByteBuf uncompressedPayload = codec.decode(metadataAndPayload, metadata.getUncompressedSize());

            // Copy into a heap buffer for output stream compatibility
            ByteBuf data = PulsarByteBufAllocator.DEFAULT.heapBuffer(uncompressedPayload.readableBytes(),
                    uncompressedPayload.readableBytes());
            data.writeBytes(uncompressedPayload);
            uncompressedPayload.release();

            StreamingOutput stream = new StreamingOutput() {

                @Override
                public void write(OutputStream output) throws IOException, WebApplicationException {
                    output.write(data.array(), data.arrayOffset(), data.readableBytes());
                    data.release();
                }
            };

            return responseBuilder.entity(stream).build();
        } catch (NullPointerException npe) {
            throw new RestException(Status.NOT_FOUND, "Message not found");
        } catch (Exception exception) {
            log.error("[{}] Failed to get message at position {} from {} {}", clientAppId(), messagePosition,
                    topicName, subName, exception);
            throw new RestException(exception);
        } finally {
            if (entry != null) {
                entry.release();
            }
        }
    }

    protected PersistentOfflineTopicStats internalGetBacklog(boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // Validate that namespace exists, throw 404 if it doesn't exist
        // note that we do not want to load the topic and hence skip validateAdminOperationOnTopic()
        try {
            policiesCache().get(path(POLICIES, namespaceName.toString()));
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to get topic backlog {}: Namespace does not exist", clientAppId(), namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (Exception e) {
            log.error("[{}] Failed to get topic backlog {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }

        PersistentOfflineTopicStats offlineTopicStats = null;
        try {

            offlineTopicStats = pulsar().getBrokerService().getOfflineTopicStat(topicName);
            if (offlineTopicStats != null) {
                // offline topic stat has a cost - so use cached value until TTL
                long elapsedMs = System.currentTimeMillis() - offlineTopicStats.statGeneratedAt.getTime();
                if (TimeUnit.MINUTES.convert(elapsedMs, TimeUnit.MILLISECONDS) < OFFLINE_TOPIC_STAT_TTL_MINS) {
                    return offlineTopicStats;
                }
            }
            final ManagedLedgerConfig config = pulsar().getBrokerService().getManagedLedgerConfig(topicName)
                    .get();
            ManagedLedgerOfflineBacklog offlineTopicBacklog = new ManagedLedgerOfflineBacklog(config.getDigestType(),
                    config.getPassword(), pulsar().getAdvertisedAddress(), false);
            offlineTopicStats = offlineTopicBacklog.estimateUnloadedTopicBacklog(
                    (ManagedLedgerFactoryImpl) pulsar().getManagedLedgerFactory(), topicName);
            pulsar().getBrokerService().cacheOfflineTopicStats(topicName, offlineTopicStats);
        } catch (Exception exception) {
            throw new RestException(exception);
        }
        return offlineTopicStats;
    }

    protected MessageId internalTerminate(boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
        if (partitionMetadata.partitions > 0) {
            throw new RestException(Status.METHOD_NOT_ALLOWED, "Termination of a partitioned topic is not allowed");
        }
        validateAdminOperationOnTopic(authoritative);
        Topic topic = getTopicReference(topicName);
        try {
            return ((PersistentTopic) topic).terminate().get();
        } catch (Exception exception) {
            log.error("[{}] Failed to terminated topic {}", clientAppId(), topicName, exception);
            throw new RestException(exception);
        }
    }

    protected void internalExpireMessages(AsyncResponse asyncResponse, String subName, int expireTimeInSeconds,
            boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (topicName.isPartitioned()) {
            try {
                internalExpireMessagesForSinglePartition(subName, expireTimeInSeconds, authoritative);
            } catch (WebApplicationException wae) {
                asyncResponse.resume(wae);
                return;
            } catch (Exception e) {
                asyncResponse.resume(new RestException(e));
                return;
            }
            asyncResponse.resume(Response.noContent().build());
        } else {
            PartitionedTopicMetadata partitionMetadata = getPartitionedTopicMetadata(topicName, authoritative, false);
            if (partitionMetadata.partitions > 0) {
                final List<CompletableFuture<Void>> futures = Lists.newArrayList();

                // expire messages for each partition topic
                for (int i = 0; i < partitionMetadata.partitions; i++) {
                    TopicName topicNamePartition = topicName.getPartition(i);
                    try {
                        futures.add(pulsar().getAdminClient().topics().expireMessagesAsync(topicNamePartition.toString(),
                            subName, expireTimeInSeconds));
                    } catch (Exception e) {
                        log.error("[{}] Failed to expire messages up to {} on {}", clientAppId(), expireTimeInSeconds,
                            topicNamePartition, e);
                        asyncResponse.resume(new RestException(e));
                        return;
                    }
                }

                FutureUtil.waitForAll(futures).handle((result, exception) -> {
                    if (exception != null) {
                        Throwable t = exception.getCause();
                        if (t instanceof NotFoundException) {
                            asyncResponse.resume(new RestException(Status.NOT_FOUND, "Subscription not found"));
                            return null;
                        } else {
                            log.error("[{}] Failed to expire messages up to {} on {}", clientAppId(), expireTimeInSeconds,
                                topicName, t);
                            asyncResponse.resume(new RestException(t));
                            return null;
                        }
                    }

                    asyncResponse.resume(Response.noContent().build());
                    return null;
                });
            } else {
                try {
                    internalExpireMessagesForSinglePartition(subName, expireTimeInSeconds, authoritative);
                } catch (WebApplicationException wae) {
                    asyncResponse.resume(wae);
                    return;
                } catch (Exception e) {
                    asyncResponse.resume(new RestException(e));
                    return;
                }
                asyncResponse.resume(Response.noContent().build());
            }
        }
    }

    private void internalExpireMessagesForSinglePartition(String subName, int expireTimeInSeconds,
            boolean authoritative) {
        if (topicName.isGlobal()) {
            validateGlobalNamespaceOwnership(namespaceName);
        }
        // If the topic name is a partition name, no need to get partition topic metadata again
        if (!topicName.isPartitioned() && getPartitionedTopicMetadata(topicName, authoritative, false).partitions > 0) {
            String msg = "This method should not be called for partitioned topic";
            log.error("[{}] {} {} {}", clientAppId(), msg, topicName, subName);
            throw new IllegalStateException(msg);
        }

        // validate ownership and redirect if current broker is not owner
        validateAdminAccessForSubscriber(subName, authoritative);

        if (!(getTopicReference(topicName) instanceof PersistentTopic)) {
            log.error("[{}] Not supported operation of non-persistent topic {} {}", clientAppId(), topicName, subName);
            throw new RestException(Status.METHOD_NOT_ALLOWED,
                    "Expire messages on a non-persistent topic is not allowed");
        }

        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        try {
            if (subName.startsWith(topic.getReplicatorPrefix())) {
                String remoteCluster = PersistentReplicator.getRemoteCluster(subName);
                PersistentReplicator repl = (PersistentReplicator) topic.getPersistentReplicator(remoteCluster);
                checkNotNull(repl);
                repl.expireMessages(expireTimeInSeconds);
            } else {
                PersistentSubscription sub = topic.getSubscription(subName);
                checkNotNull(sub);
                sub.expireMessages(expireTimeInSeconds);
            }
            log.info("[{}] Message expire started up to {} on {} {}", clientAppId(), expireTimeInSeconds, topicName,
                    subName);
        } catch (NullPointerException npe) {
            throw new RestException(Status.NOT_FOUND, "Subscription not found");
        } catch (Exception exception) {
            log.error("[{}] Failed to expire messages up to {} on {} with subscription {} {}", clientAppId(),
                    expireTimeInSeconds, topicName, subName, exception);
            throw new RestException(exception);
        }
    }

    protected void internalTriggerCompaction(boolean authoritative) {
        validateAdminOperationOnTopic(authoritative);

        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        try {
            topic.triggerCompaction();
        } catch (AlreadyRunningException e) {
            throw new RestException(Status.CONFLICT, e.getMessage());
        } catch (Exception e) {
            throw new RestException(e);
        }
    }

    protected LongRunningProcessStatus internalCompactionStatus(boolean authoritative) {
        validateAdminOperationOnTopic(authoritative);
        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        return topic.compactionStatus();
    }

    protected void internalTriggerOffload(boolean authoritative, MessageIdImpl messageId) {
        validateAdminOperationOnTopic(authoritative);
        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        try {
            topic.triggerOffload(messageId);
        } catch (AlreadyRunningException e) {
            throw new RestException(Status.CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error triggering offload", e);
            throw new RestException(e);
        }
    }

    protected OffloadProcessStatus internalOffloadStatus(boolean authoritative) {
        validateAdminOperationOnTopic(authoritative);
        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        return topic.offloadStatus();
    }

    public static CompletableFuture<PartitionedTopicMetadata> getPartitionedTopicMetadata(PulsarService pulsar,
            String clientAppId, String originalPrincipal, AuthenticationDataSource authenticationData, TopicName topicName) {
        CompletableFuture<PartitionedTopicMetadata> metadataFuture = new CompletableFuture<>();
        try {
            // (1) authorize client
            try {
                checkAuthorization(pulsar, topicName, clientAppId, authenticationData);
            } catch (RestException e) {
                try {
                    validateAdminAccessForTenant(pulsar, clientAppId, originalPrincipal, topicName.getTenant());
                } catch (RestException authException) {
                    log.warn("Failed to authorize {} on cluster {}", clientAppId, topicName.toString());
                    throw new PulsarClientException(String.format("Authorization failed %s on topic %s with error %s",
                            clientAppId, topicName.toString(), authException.getMessage()));
                }
            } catch (Exception ex) {
                // throw without wrapping to PulsarClientException that considers: unknown error marked as internal
                // server error
                log.warn("Failed to authorize {} on cluster {} with unexpected exception {}", clientAppId,
                        topicName.toString(), ex.getMessage(), ex);
                throw ex;
            }

            String path = path(PARTITIONED_TOPIC_PATH_ZNODE, topicName.getNamespace(), topicName.getDomain().toString(),
                    topicName.getEncodedLocalName());

            // validates global-namespace contains local/peer cluster: if peer/local cluster present then lookup can
            // serve/redirect request else fail partitioned-metadata-request so, client fails while creating
            // producer/consumer
            checkLocalOrGetPeerReplicationCluster(pulsar, topicName.getNamespaceObject())
                    .thenCompose(res -> pulsar.getBrokerService()
                            .fetchPartitionedTopicMetadataCheckAllowAutoCreationAsync(topicName))
                    .thenAccept(metadata -> {
                        if (log.isDebugEnabled()) {
                            log.debug("[{}] Total number of partitions for topic {} is {}", clientAppId, topicName,
                                    metadata.partitions);
                        }
                        metadataFuture.complete(metadata);
                    }).exceptionally(ex -> {
                        metadataFuture.completeExceptionally(ex.getCause());
                        return null;
                    });
        } catch (Exception ex) {
            metadataFuture.completeExceptionally(ex);
        }
        return metadataFuture;
    }

    /**
     * Get the Topic object reference from the Pulsar broker
     */
    private Topic getTopicReference(TopicName topicName) {
        try {
            return pulsar().getBrokerService().getTopicIfExists(topicName.toString())
                    .get(pulsar().getConfiguration().getZooKeeperOperationTimeoutSeconds(), TimeUnit.SECONDS)
                    .orElseThrow(() -> topicNotFoundReason(topicName));
        } catch (RestException e) {
            throw e;
        } catch (Exception e) {
            throw new RestException(e);
        }
    }

    private RestException topicNotFoundReason(TopicName topicName) {
        if (!topicName.isPartitioned()) {
            return new RestException(Status.NOT_FOUND, "Topic not found");
        }

        PartitionedTopicMetadata partitionedTopicMetadata = getPartitionedTopicMetadata(
                TopicName.get(topicName.getPartitionedTopicName()), false, false);
        if (partitionedTopicMetadata == null || partitionedTopicMetadata.partitions == 0) {
            final String topicErrorType = partitionedTopicMetadata == null ?
                    "has no metadata" : "has zero partitions";
            return new RestException(Status.NOT_FOUND, String.format(
                    "Partitioned Topic not found: %s %s", topicName.toString(), topicErrorType));
        } else if (!internalGetList().contains(topicName.toString())) {
            return new RestException(Status.NOT_FOUND, "Topic partitions were not yet created");
        }
        return new RestException(Status.NOT_FOUND, "Partitioned Topic not found");
    }

    private Topic getOrCreateTopic(TopicName topicName) {
        return pulsar().getBrokerService().getTopic(topicName.toString(), true).thenApply(Optional::get).join();
    }

    /**
     * Get the Subscription object reference from the Topic reference
     */
    private Subscription getSubscriptionReference(String subName, PersistentTopic topic) {
        try {
            Subscription sub = topic.getSubscription(subName);
            return checkNotNull(sub);
        } catch (Exception e) {
            throw new RestException(Status.NOT_FOUND, "Subscription not found");
        }
    }

    /**
     * Get the Replicator object reference from the Topic reference
     */
    private PersistentReplicator getReplicatorReference(String replName, PersistentTopic topic) {
        try {
            String remoteCluster = PersistentReplicator.getRemoteCluster(replName);
            PersistentReplicator repl = (PersistentReplicator) topic.getPersistentReplicator(remoteCluster);
            return checkNotNull(repl);
        } catch (Exception e) {
            throw new RestException(Status.NOT_FOUND, "Replicator not found");
        }
    }

    private CompletableFuture<Void> updatePartitionedTopic(TopicName topicName, int numPartitions) {
        final String path = ZkAdminPaths.partitionedTopicPath(topicName);

        CompletableFuture<Void> updatePartition = new CompletableFuture<>();
        createSubscriptions(topicName, numPartitions).thenAccept(res -> {
            try {
                byte[] data = jsonMapper().writeValueAsBytes(new PartitionedTopicMetadata(numPartitions));
                globalZk().setData(path, data, -1, (rc, path1, ctx, stat) -> {
                    if (rc == KeeperException.Code.OK.intValue()) {
                        updatePartition.complete(null);
                    } else {
                        updatePartition.completeExceptionally(KeeperException.create(KeeperException.Code.get(rc),
                                "failed to create update partitions"));
                    }
                }, null);
            } catch (Exception e) {
                updatePartition.completeExceptionally(e);
            }
        }).exceptionally(ex -> {
            updatePartition.completeExceptionally(ex);
            return null;
        });

        return updatePartition;
    }

    /**
     * It creates subscriptions for new partitions of existing partitioned-topics
     *
     * @param topicName
     *            : topic-name: persistent://prop/cluster/ns/topic
     * @param numPartitions
     *            : number partitions for the topics
     */
    private CompletableFuture<Void> createSubscriptions(TopicName topicName, int numPartitions) {
        String path = path(PARTITIONED_TOPIC_PATH_ZNODE, topicName.getPersistenceNamingEncoding());
        CompletableFuture<Void> result = new CompletableFuture<>();
        pulsar().getBrokerService().fetchPartitionedTopicMetadataAsync(topicName).thenAccept(partitionMetadata -> {
            if (partitionMetadata.partitions <= 1) {
                result.completeExceptionally(new RestException(Status.CONFLICT, "Topic is not partitioned topic"));
                return;
            }

            if (partitionMetadata.partitions >= numPartitions) {
                result.completeExceptionally(new RestException(Status.CONFLICT,
                        "number of partitions must be more than existing " + partitionMetadata.partitions));
                return;
            }

            PulsarAdmin admin;
            try {
                admin = pulsar().getAdminClient();
            } catch (PulsarServerException e1) {
                result.completeExceptionally(e1);
                return;
            }

            admin.topics().getStatsAsync(topicName.getPartition(0).toString()).thenAccept(stats -> {
                if (stats.subscriptions.size() == 0) {
                    result.complete(null);
                } else {
                    stats.subscriptions.keySet().forEach(subscription -> {
                        List<CompletableFuture<Void>> subscriptionFutures = new ArrayList<>();
                        for (int i = partitionMetadata.partitions; i < numPartitions; i++) {
                            final String topicNamePartition = topicName.getPartition(i).toString();

                            subscriptionFutures.add(admin.topics().createSubscriptionAsync(topicNamePartition,
                                    subscription, MessageId.latest));
                        }

                        FutureUtil.waitForAll(subscriptionFutures).thenRun(() -> {
                            log.info("[{}] Successfully created new partitions {}", clientAppId(), topicName);
                            result.complete(null);
                        }).exceptionally(ex -> {
                            log.warn("[{}] Failed to create subscriptions on new partitions for {}", clientAppId(), topicName, ex);
                            result.completeExceptionally(ex);
                            return null;
                        });
                    });
                }
            }).exceptionally(ex -> {
                if (ex.getCause() instanceof PulsarAdminException.NotFoundException) {
                    // The first partition doesn't exist, so there are currently to subscriptions to recreate
                    result.complete(null);
                } else {
                    log.warn("[{}] Failed to get list of subscriptions of {}", clientAppId(), topicName.getPartition(0), ex);
                    result.completeExceptionally(ex);
                }
                return null;
            });
        }).exceptionally(ex -> {
            log.warn("[{}] Failed to get partition metadata for {}", clientAppId(), topicName.toString());
            result.completeExceptionally(ex);
            return null;
        });
        return result;
    }

    // as described at : (PR: #836) CPP-client old client lib should not be allowed to connect on partitioned-topic.
    // So, all requests from old-cpp-client (< v1.21) must be rejected.
    // Pulsar client-java lib always passes user-agent as X-Java-$version.
    // However, cpp-client older than v1.20 (PR #765) never used to pass it.
    // So, request without user-agent and Pulsar-CPP-vX (X < 1.21) must be rejected
    private void validateClientVersion() {
        if (!pulsar().getConfiguration().isClientLibraryVersionCheckEnabled()) {
            return;
        }
        final String userAgent = httpRequest.getHeader("User-Agent");
        if (StringUtils.isBlank(userAgent)) {
            throw new RestException(Status.METHOD_NOT_ALLOWED,
                    "Client lib is not compatible to access partitioned metadata: version in user-agent is not present");
        }
        // Version < 1.20 for cpp-client is not allowed
        if (userAgent.contains(DEPRECATED_CLIENT_VERSION_PREFIX)) {
            try {
                // Version < 1.20 for cpp-client is not allowed
                String[] tokens = userAgent.split(DEPRECATED_CLIENT_VERSION_PREFIX);
                String[] splits = tokens.length > 1 ? tokens[1].split("-")[0].trim().split("\\.") : null;
                if (splits != null && splits.length > 1) {
                    if (LEAST_SUPPORTED_CLIENT_VERSION_PREFIX.getMajorVersion() > Integer.parseInt(splits[0])
                            || LEAST_SUPPORTED_CLIENT_VERSION_PREFIX.getMinorVersion() > Integer.parseInt(splits[1])) {
                        throw new RestException(Status.METHOD_NOT_ALLOWED,
                                "Client lib is not compatible to access partitioned metadata: version " + userAgent
                                        + " is not supported");
                    }
                }
            } catch (RestException re) {
                throw re;
            } catch (Exception e) {
                log.warn("[{}] Failed to parse version {} ", clientAppId(), userAgent);
            }
        }
        return;
    }

    /**
     * Validate update of number of partition for partitioned topic.
     * If there's already non partition topic with same name and contains partition suffix "-partition-"
     * followed by numeric value X then the new number of partition of that partitioned topic can not be greater
     * than that X else that non partition topic will essentially be overwritten and cause unexpected consequence.
     *
     * @param topicName
     */
    private void validatePartitionTopicUpdate(String topicName, int numberOfPartition) {
        List<String> existingTopicList = internalGetList();
        TopicName partitionTopicName = TopicName.get(domain(), namespaceName, topicName);
        PartitionedTopicMetadata metadata = getPartitionedTopicMetadata(partitionTopicName, false, false);
        int oldPartition = metadata.partitions;
        String prefix = topicName + TopicName.PARTITIONED_TOPIC_SUFFIX;
        for (String exsitingTopicName : existingTopicList) {
            if (exsitingTopicName.contains(prefix)) {
                try {
                    long suffix = Long.parseLong(exsitingTopicName.substring(
                            exsitingTopicName.indexOf(TopicName.PARTITIONED_TOPIC_SUFFIX)
                                    + TopicName.PARTITIONED_TOPIC_SUFFIX.length()));
                    // Skip partition of partitioned topic by making sure the numeric suffix greater than old partition number.
                    if (suffix >= oldPartition && suffix <= (long) numberOfPartition) {
                        log.warn("[{}] Already have non partition topic {} which contains partition " +
                                "suffix '-partition-' and end with numeric value smaller than the new number of partition. " +
                                "Update of partitioned topic {} could cause conflict.", clientAppId(), exsitingTopicName, topicName);
                        throw new RestException(Status.PRECONDITION_FAILED,
                                "Already have non partition topic" + exsitingTopicName + " which contains partition suffix '-partition-' " +
                                        "and end with numeric value and end with numeric value smaller than the new " +
                                        "number of partition. Update of partitioned topic " + topicName + " could cause conflict.");
                    }
                } catch (NumberFormatException e) {
                    // Do nothing, if value after partition suffix is not pure numeric value,
                    // as it can't conflict with internal created partitioned topic's name.
                }
            }
        }
    }

    /**
     * Validate partitioned topic name.
     * Validation will fail and throw RestException if
     * 1) There's already a partitioned topic with same topic name and have some of its partition created.
     * 2) There's already non partition topic with same name and contains partition suffix "-partition-"
     * followed by numeric value. In this case internal created partition of partitioned topic could override
     * the existing non partition topic.
     *
     * @param topicName
     */
    private void validatePartitionTopicName(String topicName) {
        List<String> existingTopicList = internalGetList();
        String prefix = topicName + TopicName.PARTITIONED_TOPIC_SUFFIX;
        for (String existingTopicName : existingTopicList) {
            if (existingTopicName.contains(prefix)) {
                try {
                    Long.parseLong(existingTopicName.substring(
                            existingTopicName.indexOf(TopicName.PARTITIONED_TOPIC_SUFFIX)
                                    + TopicName.PARTITIONED_TOPIC_SUFFIX.length()));
                    log.warn("[{}] Already have topic {} which contains partition " +
                            "suffix '-partition-' and end with numeric value. Creation of partitioned topic {}"
                            + "could cause conflict.", clientAppId(), existingTopicName, topicName);
                    throw new RestException(Status.PRECONDITION_FAILED,
                            "Already have topic " + existingTopicName + " which contains partition suffix '-partition-' " +
                                    "and end with numeric value, Creation of partitioned topic " + topicName +
                                    " could cause conflict.");
                } catch (NumberFormatException e) {
                    // Do nothing, if value after partition suffix is not pure numeric value,
                    // as it can't conflict with internal created partitioned topic's name.
                }
            }
        }
    }

    /**
     * Validate non partition topic name,
     * Validation will fail and throw RestException if
     * 1) Topic name contains partition suffix "-partition-" and the remaining part follow the partition
     * suffix is numeric value larger than the number of partition if there's already a partition topic with same
     * name(the part before suffix "-partition-").
     * 2)Topic name contains partition suffix "-partition-" and the remaining part follow the partition
     * suffix is numeric value but there isn't a partitioned topic with same name.
     *
     * @param topicName
     */
    private void validateNonPartitionTopicName(String topicName) {
        if (topicName.contains(TopicName.PARTITIONED_TOPIC_SUFFIX)) {
            try {
                // First check if what's after suffix "-partition-" is number or not, if not number then can create.
                int partitionIndex = topicName.indexOf(TopicName.PARTITIONED_TOPIC_SUFFIX);
                long suffix = Long.parseLong(topicName.substring(partitionIndex
                        + TopicName.PARTITIONED_TOPIC_SUFFIX.length()));
                TopicName partitionTopicName = TopicName.get(domain(), namespaceName, topicName.substring(0, partitionIndex));
                PartitionedTopicMetadata metadata = getPartitionedTopicMetadata(partitionTopicName, false, false);

                // Partition topic index is 0 to (number of partition - 1)
                if (metadata.partitions > 0 && suffix >= (long) metadata.partitions) {
                    log.warn("[{}] Can't create topic {} with \"-partition-\" followed by" +
                            " a number smaller then number of partition of partitioned topic {}.",
                            clientAppId(), topicName, partitionTopicName.getLocalName());
                    throw new RestException(Status.PRECONDITION_FAILED,
                            "Can't create topic " + topicName + " with \"-partition-\" followed by" +
                            " a number smaller then number of partition of partitioned topic " +
                                    partitionTopicName.getLocalName());
                } else if (metadata.partitions == 0) {
                    log.warn("[{}] Can't create topic {} with \"-partition-\" followed by" +
                                    " numeric value if there isn't a partitioned topic {} created.",
                            clientAppId(), topicName, partitionTopicName.getLocalName());
                    throw new RestException(Status.PRECONDITION_FAILED,
                            "Can't create topic " + topicName + " with \"-partition-\" followed by" +
                                    " numeric value if there isn't a partitioned topic " +
                                    partitionTopicName.getLocalName() + " created.");
                }
                // If there is a  partitioned topic with the same name and numeric suffix is smaller than the
                // number of partition for that partitioned topic, validation will pass.
            } catch (NumberFormatException e) {
                // Do nothing, if value after partition suffix is not pure numeric value,
                // as it can't conflict if user want to create partitioned topic with same
                // topic name prefix in the future.
            }
        }
    }

    protected MessageId internalGetLastMessageId(boolean authoritative) {
        validateAdminOperationOnTopic(authoritative);

        if (!(getTopicReference(topicName) instanceof PersistentTopic)) {
            log.error("[{}] Not supported operation of non-persistent topic {}", clientAppId(), topicName);
            throw new RestException(Status.METHOD_NOT_ALLOWED,
                    "GetLastMessageId on a non-persistent topic is not allowed");
        }
        PersistentTopic topic = (PersistentTopic) getTopicReference(topicName);
        Position position = topic.getLastMessageId();
        int partitionIndex = TopicName.getPartitionIndex(topic.getName());

        MessageId messageId = new MessageIdImpl(((PositionImpl)position).getLedgerId(), ((PositionImpl)position).getEntryId(), partitionIndex);

        return messageId;
    }
}
