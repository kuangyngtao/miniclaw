package com.clawkit.engine.impl;

import static org.assertj.core.api.Assertions.assertThat;
import com.clawkit.provider.LLMException;
import com.clawkit.provider.ProviderError;
import org.junit.jupiter.api.Test;

class ProviderFailureMessageTest {
    @Test void rendersAuthenticationWithoutRawException() {
        String result = ProviderFailureMessage.format(new LLMException(
            "HTTP 401: sk-secret", new ProviderError.Authentication("bad key")));
        assertThat(result).contains("[A-002]", "authentication failed", "Impact:", "Next:")
            .doesNotContain("sk-secret", "HTTP 401");
    }

    @Test void rendersContextRecoveryAction() {
        String result = ProviderFailureMessage.format(new LLMException(
            "too long", new ProviderError.ContextLengthExceeded("too long")));
        assertThat(result).contains("context limit", "/compact");
    }
}
