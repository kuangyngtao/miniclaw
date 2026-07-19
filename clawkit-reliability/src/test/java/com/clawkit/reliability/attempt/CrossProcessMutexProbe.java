package com.clawkit.reliability.attempt;

import com.clawkit.tools.ToolRiskLevel;
import com.clawkit.tools.action.ActionDescriptor;
import com.clawkit.tools.action.ActionReliability;
import com.clawkit.tools.action.Reversibility;
import com.clawkit.tools.action.VerificationMode;

import java.nio.file.Path;
import java.util.List;

/**
 * P1-G6 跨进程互斥探针：在独立 JVM 中尝试获取同一 targetKey。
 * 输出协议：WIN / BUSY / ERROR:&lt;message&gt;（供父测试进程断言）。
 */
public final class CrossProcessMutexProbe {

    private CrossProcessMutexProbe() {}

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("ERROR:usage storeDir targetKey actionId");
            return;
        }
        try (var store = new FileActionAttemptStore(Path.of(args[0]))) {
            var descriptor = new ActionDescriptor(
                "probe.write", args[1], "probe-digest",
                ToolRiskLevel.HIGH, Reversibility.COMPENSATABLE,
                ActionReliability.none(), VerificationMode.MANUAL_REQUIRED,
                List.of(), List.of(), "", "");
            store.create(descriptor, args[2], 1, "probe-run", null, "ACTION");
            System.out.println("WIN");
        } catch (AttemptFailure.TargetBusyException e) {
            System.out.println("BUSY");
        } catch (Exception e) {
            System.out.println("ERROR:" + e.getMessage());
        }
    }
}
