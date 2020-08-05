package org.whispersystems.textsecuregcm.storage;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.InvalidProtocolBufferException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.SlotHash;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.entities.MessageProtos;
import org.whispersystems.textsecuregcm.entities.OutgoingMessageEntity;
import org.whispersystems.textsecuregcm.redis.ClusterLuaScript;
import org.whispersystems.textsecuregcm.redis.FaultTolerantRedisCluster;
import org.whispersystems.textsecuregcm.util.RedisClusterUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.codahale.metrics.MetricRegistry.name;

public class RedisClusterMessagesCache implements UserMessagesCache {

    private final FaultTolerantRedisCluster redisCluster;

    private final ClusterLuaScript insertScript;
    private final ClusterLuaScript removeByIdScript;
    private final ClusterLuaScript removeBySenderScript;
    private final ClusterLuaScript removeByGuidScript;
    private final ClusterLuaScript getItemsScript;
    private final ClusterLuaScript removeQueueScript;
    private final ClusterLuaScript getQueuesToPersistScript;

    static final         String NEXT_SLOT_TO_PERSIST_KEY  = "user_queue_persist_slot";
    private static final byte[] LOCK_VALUE                = "1".getBytes(StandardCharsets.UTF_8);

    private static final String INSERT_TIMER_NAME = name(RedisClusterMessagesCache.class, "insert");
    private static final String REMOVE_TIMER_NAME = name(RedisClusterMessagesCache.class, "remove");
    private static final String GET_TIMER_NAME    = name(RedisClusterMessagesCache.class, "get");
    private static final String CLEAR_TIMER_NAME  = name(RedisClusterMessagesCache.class, "clear");

    private static final String REMOVE_METHOD_TAG    = "method";
    private static final String REMOVE_METHOD_ID     = "id";
    private static final String REMOVE_METHOD_SENDER = "sender";
    private static final String REMOVE_METHOD_UUID   = "uuid";

    private static final Logger logger = LoggerFactory.getLogger(RedisClusterMessagesCache.class);

    public RedisClusterMessagesCache(final FaultTolerantRedisCluster redisCluster) throws IOException {

        this.redisCluster = redisCluster;

        this.insertScript             = ClusterLuaScript.fromResource(redisCluster, "lua/insert_item.lua",           ScriptOutputType.INTEGER);
        this.removeByIdScript         = ClusterLuaScript.fromResource(redisCluster, "lua/remove_item_by_id.lua",     ScriptOutputType.VALUE);
        this.removeBySenderScript     = ClusterLuaScript.fromResource(redisCluster, "lua/remove_item_by_sender.lua", ScriptOutputType.VALUE);
        this.removeByGuidScript       = ClusterLuaScript.fromResource(redisCluster, "lua/remove_item_by_guid.lua",   ScriptOutputType.VALUE);
        this.getItemsScript           = ClusterLuaScript.fromResource(redisCluster, "lua/get_items.lua",             ScriptOutputType.MULTI);
        this.removeQueueScript        = ClusterLuaScript.fromResource(redisCluster, "lua/remove_queue.lua",          ScriptOutputType.STATUS);
        this.getQueuesToPersistScript = ClusterLuaScript.fromResource(redisCluster, "lua/get_queues_to_persist.lua", ScriptOutputType.MULTI);
    }

    @Override
    public long insert(final UUID guid, final String destination, final UUID destinationUuid, final long destinationDevice, final MessageProtos.Envelope message) {
        final MessageProtos.Envelope messageWithGuid = message.toBuilder().setServerGuid(guid.toString()).build();
        final String                 sender          = message.hasSource() ? (message.getSource() + "::" + message.getTimestamp()) : "nil";

        return (long)Metrics.timer(INSERT_TIMER_NAME).record(() ->
                insertScript.executeBinary(List.of(getMessageQueueKey(destinationUuid, destinationDevice),
                                                   getMessageQueueMetadataKey(destinationUuid, destinationDevice),
                                                   getQueueIndexKey(destinationUuid, destinationDevice)),
                                           List.of(messageWithGuid.toByteArray(),
                                                   String.valueOf(message.getTimestamp()).getBytes(StandardCharsets.UTF_8),
                                                   sender.getBytes(StandardCharsets.UTF_8),
                                                   guid.toString().getBytes(StandardCharsets.UTF_8))));
    }

    public long insert(final UUID guid, final String destination, final UUID destinationUuid, final long destinationDevice, final MessageProtos.Envelope message, final long messageId) {
        final MessageProtos.Envelope messageWithGuid = message.toBuilder().setServerGuid(guid.toString()).build();
        final String                 sender          = message.hasSource() ? (message.getSource() + "::" + message.getTimestamp()) : "nil";

        return (long)Metrics.timer(INSERT_TIMER_NAME).record(() ->
                insertScript.executeBinary(List.of(getMessageQueueKey(destinationUuid, destinationDevice),
                                                   getMessageQueueMetadataKey(destinationUuid, destinationDevice),
                                                   getQueueIndexKey(destinationUuid, destinationDevice)),
                                           List.of(messageWithGuid.toByteArray(),
                                                   String.valueOf(message.getTimestamp()).getBytes(StandardCharsets.UTF_8),
                                                   sender.getBytes(StandardCharsets.UTF_8),
                                                   guid.toString().getBytes(StandardCharsets.UTF_8),
                                                   String.valueOf(messageId).getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public Optional<OutgoingMessageEntity> remove(final String destination, final UUID destinationUuid, final long destinationDevice, final long id) {
        try {
            final byte[] serialized = (byte[])Metrics.timer(REMOVE_TIMER_NAME, REMOVE_METHOD_TAG, REMOVE_METHOD_ID).record(() ->
                    removeByIdScript.executeBinary(List.of(getMessageQueueKey(destinationUuid, destinationDevice),
                                                           getMessageQueueMetadataKey(destinationUuid, destinationDevice),
                                                           getQueueIndexKey(destinationUuid, destinationDevice)),
                                                   List.of(String.valueOf(id).getBytes(StandardCharsets.UTF_8))));


            if (serialized != null) {
                return Optional.of(UserMessagesCache.constructEntityFromEnvelope(id, MessageProtos.Envelope.parseFrom(serialized)));
            }
        } catch (final InvalidProtocolBufferException e) {
            logger.warn("Failed to parse envelope", e);
        }

        return Optional.empty();
    }

    @Override
    public Optional<OutgoingMessageEntity> remove(final String destination, final UUID destinationUuid, final long destinationDevice, final String sender, final long timestamp) {
        try {
            final byte[] serialized = (byte[])Metrics.timer(REMOVE_TIMER_NAME, REMOVE_METHOD_TAG, REMOVE_METHOD_SENDER).record(() ->
                    removeBySenderScript.executeBinary(List.of(getMessageQueueKey(destinationUuid, destinationDevice),
                                                               getMessageQueueMetadataKey(destinationUuid, destinationDevice),
                                                               getQueueIndexKey(destinationUuid, destinationDevice)),
                                                       List.of((sender + "::" + timestamp).getBytes(StandardCharsets.UTF_8))));

            if (serialized != null) {
                return Optional.of(UserMessagesCache.constructEntityFromEnvelope(0, MessageProtos.Envelope.parseFrom(serialized)));
            }
        } catch (final InvalidProtocolBufferException e) {
            logger.warn("Failed to parse envelope", e);
        }

        return Optional.empty();
    }

    @Override
    public Optional<OutgoingMessageEntity> remove(final String destination, final UUID destinationUuid, final long destinationDevice, final UUID messageGuid) {
        try {
            final byte[] serialized = (byte[])Metrics.timer(REMOVE_TIMER_NAME, REMOVE_METHOD_TAG, REMOVE_METHOD_UUID).record(() ->
                    removeByGuidScript.executeBinary(List.of(getMessageQueueKey(destinationUuid, destinationDevice),
                                                             getMessageQueueMetadataKey(destinationUuid, destinationDevice),
                                                             getQueueIndexKey(destinationUuid, destinationDevice)),
                                                     List.of(messageGuid.toString().getBytes(StandardCharsets.UTF_8))));

            if (serialized != null) {
                return Optional.of(UserMessagesCache.constructEntityFromEnvelope(0, MessageProtos.Envelope.parseFrom(serialized)));
            }
        } catch (final InvalidProtocolBufferException e) {
            logger.warn("Failed to parse envelope", e);
        }

        return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<OutgoingMessageEntity> get(final String destination, final UUID destinationUuid, final long destinationDevice, final int limit) {
        return Metrics.timer(GET_TIMER_NAME).record(() -> {
            final List<byte[]> queueItems = (List<byte[]>)getItemsScript.executeBinary(List.of(getMessageQueueKey(destinationUuid, destinationDevice),
                                                                                               getPersistInProgressKey(destinationUuid, destinationDevice)),
                                                                                       List.of(String.valueOf(limit).getBytes(StandardCharsets.UTF_8)));

            final List<OutgoingMessageEntity> messageEntities;

            if (queueItems.size() % 2 == 0) {
                messageEntities = new ArrayList<>(queueItems.size() / 2);

                for (int i = 0; i < queueItems.size() - 1; i += 2) {
                    try {
                        final MessageProtos.Envelope message = MessageProtos.Envelope.parseFrom(queueItems.get(i));
                        final long id = Long.parseLong(new String(queueItems.get(i + 1), StandardCharsets.UTF_8));

                        messageEntities.add(UserMessagesCache.constructEntityFromEnvelope(id, message));
                    } catch (InvalidProtocolBufferException e) {
                        logger.warn("Failed to parse envelope", e);
                    }
                }
            } else {
                logger.error("\"Get messages\" operation returned a list with a non-even number of elements.");
                messageEntities = Collections.emptyList();
            }

            return messageEntities;
        });
    }

    @SuppressWarnings("unchecked")
    @VisibleForTesting
    List<MessageProtos.Envelope> getMessagesToPersist(final UUID accountUuid, final long destinationDevice, final int limit) {
        return Metrics.timer(GET_TIMER_NAME).record(() -> {
            final List<byte[]> queueItems = (List<byte[]>)getItemsScript.executeBinary(List.of(getMessageQueueKey(accountUuid, destinationDevice),
                                                                                               getPersistInProgressKey(accountUuid, destinationDevice)),
                                                                                       List.of(String.valueOf(limit).getBytes(StandardCharsets.UTF_8)));

            final List<MessageProtos.Envelope> envelopes;

            if (queueItems.size() % 2 == 0) {
                envelopes = new ArrayList<>(queueItems.size() / 2);

                for (int i = 0; i < queueItems.size(); i += 2) {
                    try {
                        envelopes.add(MessageProtos.Envelope.parseFrom(queueItems.get(i)));
                    } catch (InvalidProtocolBufferException e) {
                        logger.warn("Failed to parse envelope", e);
                    }
                }
            } else {
                logger.error("\"Get messages\" operation returned a list with a non-even number of elements.");
                envelopes = Collections.emptyList();
            }

            return envelopes;
        });
    }

    @Override
    public void clear(final String destination, final UUID destinationUuid) {
        // TODO Remove null check in a fully UUID-based world
        if (destinationUuid != null) {
            for (int i = 1; i < 256; i++) {
                clear(destination, destinationUuid, i);
            }
        }
    }

    @Override
    public void clear(final String destination, final UUID destinationUuid, final long deviceId) {
        Metrics.timer(CLEAR_TIMER_NAME).record(() ->
                removeQueueScript.executeBinary(List.of(getMessageQueueKey(destinationUuid, deviceId),
                                                        getMessageQueueMetadataKey(destinationUuid, deviceId),
                                                        getQueueIndexKey(destinationUuid, deviceId)),
                                                Collections.emptyList()));
    }

    int getNextSlotToPersist() {
        return (int)(redisCluster.withWriteCluster(connection -> connection.sync().incr(NEXT_SLOT_TO_PERSIST_KEY)) % SlotHash.SLOT_COUNT);
    }

    List<String> getQueuesToPersist(final int slot, final Instant maxTime, final int limit) {
        //noinspection unchecked
        return (List<String>)getQueuesToPersistScript.execute(List.of(new String(getQueueIndexKey(slot), StandardCharsets.UTF_8)),
                                                              List.of(String.valueOf(maxTime.toEpochMilli()),
                                                                      String.valueOf(limit)));
    }

    void lockQueueForPersistence(final String queue) {
        redisCluster.useBinaryWriteCluster(connection -> connection.sync().setex(getPersistInProgressKey(queue), 30, LOCK_VALUE));
    }

    void unlockQueueForPersistence(final String queue) {
        redisCluster.useBinaryWriteCluster(connection -> connection.sync().del(getPersistInProgressKey(queue)));
    }

    @VisibleForTesting
    static byte[] getMessageQueueKey(final UUID accountUuid, final long deviceId) {
        return ("user_queue::{" + accountUuid.toString() + "::" + deviceId + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] getMessageQueueMetadataKey(final UUID accountUuid, final long deviceId) {
        return ("user_queue_metadata::{" + accountUuid.toString() + "::" + deviceId + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] getQueueIndexKey(final UUID accountUuid, final long deviceId) {
        return getQueueIndexKey(SlotHash.getSlot(accountUuid.toString() + "::" + deviceId));
    }

    private static byte[] getQueueIndexKey(final int slot) {
        return ("user_queue_index::{" + RedisClusterUtil.getMinimalHashTag(slot) + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] getPersistInProgressKey(final UUID accountUuid, final long deviceId) {
        return getPersistInProgressKey(accountUuid + "::" + deviceId);
    }

    private static byte[] getPersistInProgressKey(final String queueName) {
        return ("user_queue_persisting::{" + queueName + "}").getBytes(StandardCharsets.UTF_8);
    }

    static UUID getAccountUuidFromQueueName(final String queueName) {
        final int startOfHashTag = queueName.indexOf('{');

        return UUID.fromString(queueName.substring(startOfHashTag + 1, queueName.indexOf("::", startOfHashTag)));
    }

    static long getDeviceIdFromQueueName(final String queueName) {
        return Long.parseLong(queueName.substring(queueName.lastIndexOf("::") + 2, queueName.lastIndexOf('}')));
    }
}