package com.clawkit.provider;

/**
 * Provider 响应元数据（P1-A3 公开）。
 * 记录真实 model/id/retryCount，供 Gateway 写入观测事件。
 */
public record ProviderResponseMetadata(String model, String id, int retryCount) {
    public static final ProviderResponseMetadata EMPTY = new ProviderResponseMetadata("", "", 0);
}
