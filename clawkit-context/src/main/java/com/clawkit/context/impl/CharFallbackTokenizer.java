package com.clawkit.context.impl;

import com.clawkit.context.Tokenizer;

/**
 * 字符级 token 估算（降级方案）。
 * CJK 字符：~1.5 chars/token；非 CJK：4 chars/token。
 * 在 JTokkit 不可用时使用。
 */
public class CharFallbackTokenizer implements Tokenizer {

    private static final int CHARS_PER_TOKEN_EN = 4;

    @Override
    public int countTokens(String text) {
        int cjk = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        // CJK: ~1.5 chars/token → cjk * 2 / 3;  non-CJK: 4 chars/token
        return (cjk * 2 / 3) + (other / CHARS_PER_TOKEN_EN);
    }

    @Override
    public String encodingName() {
        return "char_fallback";
    }
}
