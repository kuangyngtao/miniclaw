package com.clawkit.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ApprovalGrantCache 的默认实现。
 *
 * <p>缓存规则：
 * <ul>
 *   <li>HIGH 风险工具默认不可缓存</li>
 *   <li>destructive 工具默认不可缓存</li>
 *   <li>openWorld 工具默认不可缓存</li>
 *   <li>缓存键 = 工具名 + 风险等级（参数骨架不同时重新审批）</li>
 * </ul>
 */
public final class DefaultApprovalGrantCache implements ApprovalGrantCache {

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public boolean isGranted(String toolName, ToolRiskLevel riskLevel,
                              JsonNode arguments, Set<ToolSideEffect> sideEffects) {
        if (riskLevel == ToolRiskLevel.HIGH) return false;
        CacheEntry entry = cache.get(toolName);
        if (entry == null) return false;
        // 风险等级必须一致
        if (entry.riskLevel != riskLevel) return false;
        // 参数骨架一致才命中
        String argSkeleton = skeleton(arguments, sideEffects);
        return argSkeleton.equals(entry.argSkeleton);
    }

    @Override
    public void grant(String toolName, ToolRiskLevel riskLevel,
                       JsonNode arguments, Set<ToolSideEffect> sideEffects) {
        if (riskLevel == ToolRiskLevel.HIGH) return;
        cache.put(toolName, new CacheEntry(riskLevel, skeleton(arguments, sideEffects)));
    }

    @Override
    public void clear() {
        cache.clear();
    }

    /**
     * 参数骨架：提取顶层 key 集合 + 关键 key 的值作为结构指纹。
     * P1-G4：声明了副作用的工具按全参数内容绑定（等价 action fingerprint）——
     * 参数漂移必须重新门禁，same-type 授权不得跨参数复用。
     */
    private static String skeleton(JsonNode args, Set<ToolSideEffect> sideEffects) {
        if (args == null || !args.isObject()) return "{}";
        if (sideEffects != null && !sideEffects.isEmpty()) {
            return com.clawkit.tools.action.Digests.sha256Hex(args.toString());
        }
        StringBuilder sb = new StringBuilder();
        TreeSet<String> keys = new TreeSet<>();
        var it = args.fieldNames();
        while (it.hasNext()) keys.add(it.next());
        sb.append(String.join(",", keys));
        for (String key : List.of("path", "file_path", "command")) {
            if (args.has(key)) {
                sb.append(':').append(key).append('=').append(args.get(key).asText());
            }
        }
        return sb.toString();
    }

    private record CacheEntry(ToolRiskLevel riskLevel, String argSkeleton) {}
}
