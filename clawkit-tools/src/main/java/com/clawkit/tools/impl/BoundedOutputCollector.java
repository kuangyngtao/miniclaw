package com.clawkit.tools.impl;

import com.clawkit.tools.OutputEnvelope;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 有界流式输出采集器（P1-G2 截断保真）。
 *
 * <p>单次流式采集同时维护：
 * <ul>
 *   <li>有界 head 缓冲；</li>
 *   <li>有界 tail 环形缓冲（错误常在末尾）；</li>
 *   <li>错误行匹配片段（错误在中段也不丢失）；</li>
 *   <li>总字节数与全量 SHA-256（截断不破坏完整性证据）。</li>
 * </ul>
 * 输出进入信封前统一脱敏（Bearer/sk- 凭据掩码）。非线程安全，单采集线程使用。
 */
public final class BoundedOutputCollector {

    private static final Pattern ERROR_LINE = Pattern.compile(
        "(?i)\\b(error|exception|fatal|failed|failure|panic|traceback|caused by)\\b");
    private static final Pattern WARN_LINE = Pattern.compile(
        "(?i)\\b(warn|warning)\\b");
    private static final Pattern SECRET = Pattern.compile(
        "(?i)(bearer\\s+|sk-|api[_-]?key\\s*[=:]\\s*)[A-Za-z0-9._-]{8,}");
    private static final int MAX_ERROR_EXCERPTS = 8;
    private static final int MAX_WARN_EXCERPTS = 8;
    private static final int MAX_EXCERPT_CHARS = 300;
    private static final int MAX_LINE_TRACK_CHARS = 1000;

    private final long headCap;
    private final byte[] tailRing;
    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private final MessageDigest digest;
    private final List<String> errorExcerpts = new ArrayList<>();
    private final List<String> warnExcerpts = new ArrayList<>();     // P1-A5
    private final StringBuilder currentLine = new StringBuilder();

    private long totalBytes;
    private long tailWritten; // 写入 tail ring 的累计字节数
    private long lineCount;    // P1-A5: 已观察的源行数

    public BoundedOutputCollector(long headCap, long tailCap) {
        if (headCap < 0 || tailCap < 0) throw new IllegalArgumentException("caps must be >= 0");
        this.headCap = headCap;
        this.tailRing = new byte[(int) Math.min(tailCap, Integer.MAX_VALUE)];
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 接收一段流式字节。 */
    public void accept(byte[] buf, int off, int len) {
        if (len <= 0) return;
        totalBytes += len;
        digest.update(buf, off, len);

        // head：有界追加
        long headRemaining = headCap - head.size();
        if (headRemaining > 0) {
            head.write(buf, off, (int) Math.min(len, headRemaining));
        }

        // tail：环形缓冲
        if (tailRing.length > 0) {
            for (int i = 0; i < len; i++) {
                tailRing[(int) (tailWritten % tailRing.length)] = buf[off + i];
                tailWritten++;
            }
        }

        // 错误行匹配（按行累积，行长有界防 OOM）
        String chunk = new String(buf, off, len, StandardCharsets.UTF_8);
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\n') {
                captureErrorLine();
                currentLine.setLength(0);
            } else if (currentLine.length() < MAX_LINE_TRACK_CHARS) {
                currentLine.append(c);
            }
        }
    }

    /** P1-A5：按行捕获 ERROR 和 WARN 摘录，同时计数行号。 */
    private void captureErrorLine() {
        lineCount++;
        String line = currentLine.toString();
        if (line.isBlank()) return;
        // 检查 ERROR 模式
        if (errorExcerpts.size() < MAX_ERROR_EXCERPTS && ERROR_LINE.matcher(line).find()) {
            errorExcerpts.add(line.length() > MAX_EXCERPT_CHARS
                ? line.substring(0, MAX_EXCERPT_CHARS) + "…" : line.strip());
        }
        // 检查 WARN 模式（不与 ERROR 重复统计）
        if (warnExcerpts.size() < MAX_WARN_EXCERPTS
            && errorExcerpts.stream().noneMatch(e -> e.equals(line.strip()))
            && WARN_LINE.matcher(line).find()) {
            warnExcerpts.add(line.length() > MAX_EXCERPT_CHARS
                ? line.substring(0, MAX_EXCERPT_CHARS) + "…" : line.strip());
        }
    }

    public long totalBytes() {
        return totalBytes;
    }

    /** P1-A5：已观察的源行数。 */
    public long lineCount() {
        return lineCount;
    }

    /** P1-A5：返回 WARN 摘录列表（测试验证用） */
    public List<String> warnExcerpts() {
        return List.copyOf(warnExcerpts);
    }

    /** head + tail 是否仍无法覆盖全部输出。 */
    public boolean truncated() {
        return totalBytes > headCap + tailRing.length;
    }

    /** 构建输出信封；head/tail/错误片段统一脱敏。 */
    public OutputEnvelope toEnvelope(String truncationReason) {
        captureErrorLine(); // 收尾最后一行

        String headText = decodeTrimIncomplete(head.toByteArray());
        String tailText = "";
        if (totalBytes > headCap && tailRing.length > 0) {
            byte[] tailBytes = snapshotTail();
            // tail 只保留 head 未覆盖的部分
            long overlap = Math.max(0, headCap - (totalBytes - tailBytes.length));
            if (overlap < tailBytes.length) {
                byte[] effective = new byte[(int) (tailBytes.length - overlap)];
                System.arraycopy(tailBytes, (int) overlap, effective, 0, effective.length);
                tailText = decodeSkipLeadingContinuation(effective);
            }
        }

        boolean[] redacted = {false};
        headText = redact(headText, redacted);
        tailText = redact(tailText, redacted);
        List<String> excerpts = errorExcerpts.stream()
            .map(e -> redact(e, redacted))
            .toList();

        long returned = headText.getBytes(StandardCharsets.UTF_8).length
            + tailText.getBytes(StandardCharsets.UTF_8).length;
        long omitted = Math.max(0, totalBytes - returned);
        return new OutputEnvelope(
            headText, tailText, excerpts,
            totalBytes, totalBytes - omitted, omitted,
            omitted > 0 ? truncationReason : null,
            hex(digest.digest()),
            List.of(), redacted[0], "UTF-8");
    }

    private byte[] snapshotTail() {
        if (tailWritten <= tailRing.length) {
            byte[] out = new byte[(int) tailWritten];
            System.arraycopy(tailRing, 0, out, 0, out.length);
            return out;
        }
        byte[] out = new byte[tailRing.length];
        int start = (int) (tailWritten % tailRing.length);
        System.arraycopy(tailRing, start, out, 0, tailRing.length - start);
        System.arraycopy(tailRing, 0, out, tailRing.length - start, start);
        return out;
    }

    /** 去掉末尾不完整的 UTF-8 序列再解码，避免截断产生乱码。 */
    static String decodeTrimIncomplete(byte[] bytes) {
        int len = bytes.length;
        int scan = Math.max(0, len - 4);
        for (int i = len - 1; i >= scan; i--) {
            byte b = bytes[i];
            if ((b & 0x80) == 0) break;              // ASCII，完整
            if ((b & 0xC0) == 0xC0) {                // 多字节序列起始字节
                int expected = (b & 0xF8) == 0xF0 ? 4 : (b & 0xF0) == 0xE0 ? 3 : 2;
                if (i + expected > len) len = i;     // 序列不完整 → 截掉
                break;
            }
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    /** 跳过开头的 UTF-8 续字节（tail 环形缓冲可能从多字节字符中间开始）。 */
    static String decodeSkipLeadingContinuation(byte[] bytes) {
        int start = 0;
        while (start < bytes.length && start < 4 && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private static String redact(String text, boolean[] flag) {
        if (text == null || text.isEmpty()) return text;
        var matcher = SECRET.matcher(text);
        if (!matcher.find()) return text;
        flag[0] = true;
        return matcher.reset().replaceAll("$1***");
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
