package com.clawkit.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RunEventPayload 与 RunEventEnvelope 之间的编解码器。
 *
 * <p>编码：payload + 元数据 → envelope → JSON 行。
 * <p>解码：JSON 行 → envelope（含类型化 payload 或 UnknownEventPayload）。
 *
 * <p>线程安全：所有方法使用不可变数据，无共享可变状态。
 */
public final class RunEventCodec {

    private static final Map<Class<? extends RunEventPayload>, String> TYPE_BY_CLASS;
    private static final Map<String, Class<? extends RunEventPayload>> CLASS_BY_TYPE;

    static {
        TYPE_BY_CLASS = new LinkedHashMap<>();
        TYPE_BY_CLASS.put(RunStartedPayload.class, RunEventType.RUN_STARTED);
        TYPE_BY_CLASS.put(RunCompletedPayload.class, RunEventType.RUN_COMPLETED);
        TYPE_BY_CLASS.put(TurnStartedPayload.class, RunEventType.TURN_STARTED);
        TYPE_BY_CLASS.put(TurnCompletedPayload.class, RunEventType.TURN_COMPLETED);
        TYPE_BY_CLASS.put(ContextPreparedPayload.class, RunEventType.CONTEXT_PREPARED);
        TYPE_BY_CLASS.put(ProviderCallStartedPayload.class, RunEventType.PROVIDER_CALL_STARTED);
        TYPE_BY_CLASS.put(ProviderCallCompletedPayload.class, RunEventType.PROVIDER_CALL_COMPLETED);
        TYPE_BY_CLASS.put(ToolInvokedPayload.class, RunEventType.TOOL_INVOKED);
        TYPE_BY_CLASS.put(ToolCompletedPayload.class, RunEventType.TOOL_COMPLETED);
        TYPE_BY_CLASS.put(ToolRetryScheduledPayload.class, RunEventType.TOOL_RETRY_SCHEDULED); // P0-1
        TYPE_BY_CLASS.put(ApprovalDecidedPayload.class, RunEventType.APPROVAL_DECIDED);
        TYPE_BY_CLASS.put(CompactTriggeredPayload.class, RunEventType.COMPACT_TRIGGERED);
        TYPE_BY_CLASS.put(CompactCompletedPayload.class, RunEventType.COMPACT_COMPLETED);
        TYPE_BY_CLASS.put(AttemptTransitionPayload.class, RunEventType.ATTEMPT_TRANSITION);

        CLASS_BY_TYPE = new LinkedHashMap<>();
        for (var entry : TYPE_BY_CLASS.entrySet()) {
            CLASS_BY_TYPE.put(entry.getValue(), entry.getKey());
        }
    }

    private final ObjectMapper mapper;

    public RunEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 使用默认 ObjectMapper 构造 */
    public RunEventCodec() {
        this(new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    // ── 编码 ──────────────────────────────────────────────────────────

    /**
     * 将 payload 编码为 envelope。
     *
     * @param payload      事件数据
     * @param runId        所属 run
     * @param parentRunId  父 run（无则 null）
     * @param turnNumber   当前 turn（无则 null）
     * @param sequence     该 run 内的单调序号
     * @param occurredAt   事件发生时间
     */
    public RunEventEnvelope encode(
        RunEventPayload payload,
        String runId,
        String parentRunId,
        Integer turnNumber,
        long sequence,
        Instant occurredAt
    ) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String eventType = TYPE_BY_CLASS.get(payload.getClass());
        if (eventType == null) {
            throw new IllegalArgumentException("Unknown payload type: " + payload.getClass().getName());
        }
        return new RunEventEnvelope(
            RunEventEnvelope.CURRENT_SCHEMA_VERSION,
            "evt-" + UUID.randomUUID().toString().substring(0, 8),
            eventType,
            sequence,
            occurredAt,
            Instant.now(),
            runId,
            parentRunId,
            turnNumber,
            payload
        );
    }

    /** 序列化 envelope 为单行 JSON */
    public String serialize(RunEventEnvelope envelope) throws JsonProcessingException {
        return mapper.writeValueAsString(envelope);
    }

    /** 序列化并追加换行（events.jsonl 格式） */
    public String serializeLine(RunEventEnvelope envelope) throws JsonProcessingException {
        return serialize(envelope) + "\n";
    }

    // ── 解码 ──────────────────────────────────────────────────────────

    /**
     * 从单行 JSON 反序列化 envelope。
     * 未知事件类型保留为 UnknownEventPayload，不抛异常。
     */
    public RunEventEnvelope deserialize(String jsonLine) throws JsonProcessingException {
        JsonNode root = mapper.readTree(jsonLine);

        int schemaVersion = root.has("schemaVersion") ? root.get("schemaVersion").asInt() : 0;
        String eventId = root.has("eventId") ? root.get("eventId").asText() : "";
        String eventType = root.has("eventType") ? root.get("eventType").asText() : "";
        long sequence = root.has("sequence") ? root.get("sequence").asLong() : 0;

        Instant occurredAt = root.has("occurredAt")
            ? Instant.parse(root.get("occurredAt").asText()) : Instant.EPOCH;
        Instant recordedAt = root.has("recordedAt")
            ? Instant.parse(root.get("recordedAt").asText()) : Instant.EPOCH;

        String runId = root.has("runId") ? root.get("runId").asText() : null;
        String parentRunId = root.has("parentRunId") && !root.get("parentRunId").isNull()
            ? root.get("parentRunId").asText() : null;
        Integer turnNumber = root.has("turnNumber") && !root.get("turnNumber").isNull()
            ? root.get("turnNumber").asInt() : null;

        JsonNode payloadNode = root.get("payload");
        RunEventPayload payload = decodePayload(eventType, payloadNode, schemaVersion);

        return new RunEventEnvelope(
            schemaVersion, eventId, eventType, sequence,
            occurredAt, recordedAt, runId, parentRunId, turnNumber, payload
        );
    }

    private RunEventPayload decodePayload(String eventType, JsonNode node, int schemaVersion) {
        if (node == null || node.isNull()) {
            return new UnknownEventPayload(eventType, node);
        }
        Class<? extends RunEventPayload> clazz = CLASS_BY_TYPE.get(eventType);
        if (clazz == null) {
            return new UnknownEventPayload(eventType, node);
        }
        try {
            return mapper.treeToValue(normalizeLegacyPayload(eventType, node), clazz);
        } catch (JsonProcessingException e) {
            return new UnknownEventPayload(eventType, node);
        }
    }

    /**
     * 为 additive event 字段补旧版语义默认值。
     *
     * <p>Jackson 对 record 中缺失的 primitive 字段会使用 0/false，且不会调用兼容
     * 构造器。兼容默认值必须在 codec 边界补齐；显式存在的字段一律不覆盖。
     */
    private JsonNode normalizeLegacyPayload(String eventType, JsonNode node) {
        if (!node.isObject()) {
            return node;
        }

        ObjectNode normalized = ((ObjectNode) node).deepCopy();
        if (RunEventType.PROVIDER_CALL_COMPLETED.equals(eventType)) {
            putIfMissing(normalized, "promptCacheHitTokens", 0);
            putIfMissing(normalized, "promptCacheMissTokens", 0);
            putIfMissing(normalized, "reasoningTokens", 0);
            if (!normalized.has("usageSource")) {
                normalized.put("usageSource", "UNAVAILABLE");
            }
        } else if (RunEventType.TOOL_COMPLETED.equals(eventType)) {
            int outputBytes = normalized.path("outputBytes").asInt(0);
            boolean truncated = normalized.path("truncated").asBoolean(false);

            putIfMissing(normalized, "attemptCount", 1);
            putIfMissing(normalized, "totalSourceBytes", outputBytes);
            putIfMissing(normalized, "retainedSourceBytes", outputBytes);
            putIfMissing(normalized, "returnedOutputBytes", outputBytes);
            putIfMissing(normalized, "totalLines", -1L);
            putIfMissing(normalized, "returnedLines", -1L);
            if (!normalized.has("truncationReason") && truncated) {
                normalized.put("truncationReason", "MAX_OUTPUT_BYTES");
            }
            if (!normalized.has("retentionPolicy")) {
                normalized.put("retentionPolicy", "LEGACY_V0");
            }
            if (!normalized.has("inputComplete")) {
                normalized.put("inputComplete", !truncated);
            }
        } else if (RunEventType.COMPACT_COMPLETED.equals(eventType)) {
            if (!normalized.has("profile")) {
                normalized.put("profile", "GENERAL");
            }
            putEmptyArrayIfMissing(normalized, "retainedAnchorIds");
            putEmptyArrayIfMissing(normalized, "lostRequiredAnchorIds");
            putEmptyArrayIfMissing(normalized, "discardedRangeSummaries");
            if (!normalized.has("level")) {
                normalized.put("level", normalized.path("failed").asBoolean(false)
                    ? "L4_FAILED" : "L0_NONE");
            }
            if (!normalized.has("decisionReason")) {
                if (normalized.hasNonNull("failureCode")) {
                    normalized.put("decisionReason", normalized.path("failureCode").asText());
                } else if (normalized.hasNonNull("errorCode")) {
                    normalized.put("decisionReason", normalized.path("errorCode").asText());
                } else {
                    normalized.put("decisionReason", "legacy-event");
                }
            }
        }
        return normalized;
    }

    private static void putIfMissing(ObjectNode node, String field, int value) {
        if (!node.has(field)) {
            node.put(field, value);
        }
    }

    private static void putIfMissing(ObjectNode node, String field, long value) {
        if (!node.has(field)) {
            node.put(field, value);
        }
    }

    private static void putEmptyArrayIfMissing(ObjectNode node, String field) {
        if (!node.has(field)) {
            node.putArray(field);
        }
    }

    // ── 工厂方法 ─────────────────────────────────────────────────────

    /** 为给定 payload 类型查找事件类型字符串 */
    public static String eventTypeFor(Class<? extends RunEventPayload> payloadClass) {
        return TYPE_BY_CLASS.get(payloadClass);
    }

    /** 为给定事件类型查找 payload 类 */
    public static Class<? extends RunEventPayload> payloadClassFor(String eventType) {
        return CLASS_BY_TYPE.get(eventType);
    }
}
