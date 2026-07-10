package com.clawkit.context.impl;

import com.clawkit.context.Tokenizer;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 JTokkit 的真实 token 计数器。
 * 支持 OpenAI/DeepSeek 系列模型的 tokenizer 编码。
 */
public class TiktokenTokenizer implements Tokenizer {

    private static final Logger log = LoggerFactory.getLogger(TiktokenTokenizer.class);

    private static final Map<String, EncodingType> ENCODING_MAP = Map.of(
        "cl100k_base", EncodingType.CL100K_BASE,
        "o200k_base", EncodingType.O200K_BASE,
        "r50k_base", EncodingType.R50K_BASE,
        "p50k_base", EncodingType.P50K_BASE,
        "p50k_edit", EncodingType.P50K_EDIT
    );

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding;
    private final String encodingName;

    public TiktokenTokenizer(String encodingName) {
        this.encodingName = encodingName;
        EncodingType type = ENCODING_MAP.getOrDefault(encodingName, EncodingType.CL100K_BASE);
        if (!ENCODING_MAP.containsKey(encodingName)) {
            log.warn("[Tokenizer] 未知编码 '{}'，降级使用 cl100k_base", encodingName);
        }
        this.encoding = REGISTRY.getEncoding(type);
        log.info("[Tokenizer] 使用 JTokkit 编码: {} ({})", encodingName, type);
    }

    @Override
    public int countTokens(String text) {
        return encoding.countTokens(text);
    }

    @Override
    public String encodingName() {
        return encodingName;
    }
}
