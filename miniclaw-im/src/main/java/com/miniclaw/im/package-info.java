/**
 * IM channel abstraction layer — unified interface for messaging platforms
 * (Feishu, WeChat, etc.) to interact with the AgentEngine.
 *
 * <p>Each channel handles its own transport (webhook, long-polling, etc.)
 * but shares the engine-bridge orchestration via {@link AbstractImChannel}.
 */
package com.miniclaw.im;
