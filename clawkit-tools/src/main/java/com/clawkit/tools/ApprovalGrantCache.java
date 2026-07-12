package com.clawkit.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * 审批缓存。
 * 替代旧的 {@code Set<String> autoApprovedTools}（仅按工具名缓存），
 * 支持工具名 + 风险等级 + 副作用 + 参数骨架的组合缓存键。
 *
 * <p>HIGH / destructive / openWorld 工具默认不可缓存。
 * 缓存仅在当前 run 内有效。
 */
public interface ApprovalGrantCache {

    /**
     * 检查是否已有有效缓存。
     */
    boolean isGranted(String toolName, ToolRiskLevel riskLevel,
                      JsonNode arguments, Set<ToolSideEffect> sideEffects);

    /**
     * 添加缓存授权。
     */
    void grant(String toolName, ToolRiskLevel riskLevel,
               JsonNode arguments, Set<ToolSideEffect> sideEffects);

    /** 清空所有缓存 */
    void clear();

    /** 空实现（无缓存） */
    static ApprovalGrantCache noop() {
        return NoopCache.INSTANCE;
    }

    // ── 默认实现 ──────────────────────────────────────────────────

    /**
     * 默认实现：HIGH / destructive / openWorld 工具不可缓存。
     * 缓存键 = 工具名 + 风险等级。
     * 同工具名、同风险等级的后续调用自动通过。
     */
    final class NoopCache implements ApprovalGrantCache {
        static final NoopCache INSTANCE = new NoopCache();
        @Override public boolean isGranted(String tn, ToolRiskLevel rl, JsonNode args, Set<ToolSideEffect> fx) { return false; }
        @Override public void grant(String tn, ToolRiskLevel rl, JsonNode args, Set<ToolSideEffect> fx) {}
        @Override public void clear() {}
    }
}
