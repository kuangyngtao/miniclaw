package com.clawkit.reliability.attempt;

import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.Reversibility;
import com.clawkit.tools.action.VerificationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-G6 故障注入：真实双 JVM 目标互斥、journal schema 前向兼容、
 * DISPATCH_INTENT 落盘后强杀的恢复语义。
 */
class FaultInjectionTest {

    @TempDir
    Path dir;

    private static ActionDescriptor descriptor(String target) {
        return new ActionDescriptor("file.write", target, "d1",
            ToolRiskLevel.HIGH, Reversibility.COMPENSATABLE,
            ActionReliability.none(), VerificationMode.MANUAL_REQUIRED,
            List.of(), List.of(), "", "");
    }

    /** 两个真实 JVM 同时修复相同 target：恰好一个 WIN，另一个 BUSY。 */
    @Test
    void twoRealJvmsCannotHoldSameTargetConcurrently() throws Exception {
        Path storeDir = dir.resolve("rel");
        // 本 JVM 先持有目标（DISPATCH_INTENT 持锁状态）
        try (var store = new FileActionAttemptStore(storeDir)) {
            var a = store.create(descriptor("file:/contested"), "act-local", 1, "run-1", null, "ACTION");
            var b = store.transition(a.attemptId(), a.version(), AttemptState.PRECHECKING, "pre");
            var c = store.transition(a.attemptId(), b.version(), AttemptState.READY, "ok");
            store.transition(a.attemptId(), c.version(), AttemptState.DISPATCH_INTENT, "go");

            // 独立 JVM 尝试同一目标
            String out = runProbe(storeDir, "file:/contested", "act-remote");
            assertEquals("BUSY", out, "第二个 JVM 必须被跨进程目标互斥拒绝");

            // 本 JVM 收敛后（确认无副作用），独立 JVM 才能获取
            var current = store.byId(a.attemptId()).orElseThrow();
            store.transition(a.attemptId(), current.version(),
                AttemptState.FAILED_NO_EFFECT, "released");
        }
        String out = runProbe(storeDir, "file:/contested", "act-remote-2");
        assertEquals("WIN", out, "目标释放后独立 JVM 应获取成功");
    }

    private static String runProbe(Path storeDir, String target, String actionId) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // Windows 命令行长度限制：classpath 走 @argfile
        Path argFile = Files.createTempFile("probe-args", ".txt");
        String classpath = System.getProperty("java.class.path").replace("\\", "\\\\");
        Files.writeString(argFile, "-cp \"" + classpath + "\"\n", StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(javaBin, "@" + argFile,
            CrossProcessMutexProbe.class.getName(),
            storeDir.toString(), target, actionId)
            .redirectErrorStream(false)
            .start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "probe JVM must exit");
        Files.deleteIfExists(argFile);
        String line = stdout.strip().lines().reduce((a, b) -> b).orElse("");
        assertFalse(line.startsWith("ERROR"), "probe failed: " + line + "\nstderr: " + stderr);
        return line;
    }

    /** 旧版本进程读到未来 schema 的 journal 行：未知字段忽略，不损坏、不误判。 */
    @Test
    void journalToleratesForwardCompatibleSchemaFields() throws Exception {
        Path storeDir = dir.resolve("rel");
        try (var store = new FileActionAttemptStore(storeDir)) {
            store.create(descriptor("file:/a"), "act-1", 1, "run-1", null, "ACTION");
        }
        // 手工构造带未来字段的合法记录（正确 CRC）
        Path journal = storeDir.resolve("journal.jsonl");
        String lastLine = Files.readAllLines(journal, StandardCharsets.UTF_8)
            .stream().reduce((a, b) -> b).orElseThrow();
        String json = lastLine.substring(lastLine.indexOf(' ') + 1);
        // 提升 seq 并注入未知字段
        String futureJson = json
            .replaceFirst("\"seq\":1", "\"seq\":2")
            .replaceFirst("\\{", "{\"futureSchemaField\":\"v99\",");
        var crc = new java.util.zip.CRC32();
        crc.update(futureJson.getBytes(StandardCharsets.UTF_8));
        Files.writeString(journal, Long.toHexString(crc.getValue()) + " " + futureJson + "\n",
            StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Files.deleteIfExists(storeDir.resolve("snapshot.json"));

        try (var reopened = new FileActionAttemptStore(storeDir)) {
            assertFalse(reopened.damaged(), "未知字段不构成损坏");
            assertFalse(reopened.nonTerminal().isEmpty());
        }
    }

    /** 强杀窗口全谱：intent 落盘后无论远端是否执行，重启一律不自动重发。 */
    @Test
    void killAfterDurableIntentNeverAutoRedispatches() {
        Path storeDir = dir.resolve("rel");
        String attemptId;
        // 进程 1：落盘 intent 后"强杀"（不再有任何后续写入）
        try (var store = new FileActionAttemptStore(storeDir)) {
            var a = store.create(descriptor("file:/kill-window"), "act-1", 1, "run-1", null, "ACTION");
            var b = store.transition(a.attemptId(), a.version(), AttemptState.PRECHECKING, "pre");
            var c = store.transition(a.attemptId(), b.version(), AttemptState.READY, "ok");
            var d = store.transition(a.attemptId(), c.version(), AttemptState.DISPATCH_INTENT, "go");
            attemptId = d.attemptId();
        }
        // 进程 2：重启恢复
        try (var store2 = new FileActionAttemptStore(storeDir)) {
            var report = com.clawkit.reliability.gate.RecoveryScanner.scan(store2);
            assertEquals(1, report.movedToUnknown());
            var recovered = store2.byId(attemptId).orElseThrow();
            // 无 reconcile 能力（MANUAL_REQUIRED bash 型）→ 升级人工，绝不自动重发
            assertEquals(AttemptState.ESCALATED, recovered.state());
            // 且同一逻辑动作在人工接管前后都不会被静默重复派发：
            // ESCALATED 终态不参与幂等重放，新 begin 属于新 occurrence（由人决策后进行）
            assertTrue(recovered.state().isTerminal());
        }
    }
}
