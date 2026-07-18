package com.clawkit.engine;

import com.clawkit.provider.ModelRequest;
import com.clawkit.provider.ModelResponse;
import com.clawkit.provider.StreamObserver;

/**
 * Provider 调用网关：所有模型调用的唯一入口。
 *
 * <p>负责：
 * <ul>
 *   <li>携带 RunScope 调用 LLMProvider</li>
 *   <li>发射 ProviderCallStarted / ProviderCallCompleted / ProviderCallFailed 事件</li>
 *   <li>记录 duration、retryCount、token usage</li>
 * </ul>
 */
public interface ProviderGateway {

    ModelResponse generate(ModelRequest request, RunScope scope);

    ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer);
}
