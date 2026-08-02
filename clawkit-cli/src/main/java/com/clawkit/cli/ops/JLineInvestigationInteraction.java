package com.clawkit.cli.ops;

import com.clawkit.ops.delivery.ApprovalDecision;
import com.clawkit.ops.delivery.ApprovalPrompt;
import com.clawkit.ops.delivery.InvestigationInteraction;
import com.clawkit.ops.delivery.InvestigationProgress;
import com.clawkit.ops.delivery.InvestigationView;
import com.clawkit.ops.delivery.UserIncidentStatus;
import com.clawkit.cli.ConsoleRenderer;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

/**
 * JLine3 implementation of {@link InvestigationInteraction}.
 * Uses the existing REPL {@link LineReader} — no Scanner/System.in threads.
 *
 * <p>OPS-PRODUCT-LOOP-1 §9.
 */
public final class JLineInvestigationInteraction implements InvestigationInteraction {

    private final LineReader reader;

    public JLineInvestigationInteraction(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public void onProgress(InvestigationProgress progress) {
        String prefix = "  [" + progress.step() + "/" + progress.totalSteps() + "]";
        System.out.println(ConsoleRenderer.GRAY + prefix + " " + progress.message()
            + ConsoleRenderer.RESET);
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════╗");
        System.out.println("  ║              修复审批                             ║");
        System.out.println("  ╚══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("  ▸ 发现：" + prompt.finding());
        if (prompt.whyAppDown() != null && !prompt.whyAppDown().isEmpty()) {
            System.out.println("  ▸ 判断依据：" + prompt.whyAppDown());
        }
        System.out.println("  ▸ 建议执行：" + prompt.suggestedAction());
        System.out.println("  ▸ 影响对象：" + prompt.impact());
        if (prompt.riskLevel() != null && !prompt.riskLevel().isEmpty()) {
            System.out.println("  ▸ 风险等级：" + prompt.riskLevel());
        }
        System.out.println("  ▸ 执行前保护：" + prompt.preExecutionProtection());
        System.out.println("  ▸ 执行后保护：" + prompt.postExecutionProtection());
        if (prompt.wontDo() != null && !prompt.wontDo().isEmpty()) {
            System.out.println("  ▸ 不会执行：" + prompt.wontDo());
        }
        System.out.println("  ▸ 批准有效期：" + prompt.approvalValidity());
        System.out.println();

        if (prompt.evidenceSummary() != null && !prompt.evidenceSummary().isEmpty()) {
            System.out.println("  关键证据：");
            for (String ev : prompt.evidenceSummary()) {
                System.out.println("    · " + ev);
            }
            System.out.println();
        }

        System.out.print("  请输入 [approve / reject / cancel]: ");

        try {
            String line = reader.readLine("");
            if (line == null) {
                System.out.println("\n  审批：EOF → CANCELLED\n");
                return ApprovalDecision.EOF;
            }
            String input = line.strip().toLowerCase();
            return switch (input) {
                case "approve", "a", "y", "yes" -> {
                    System.out.println("\n  ✓ 已批准\n");
                    yield ApprovalDecision.APPROVE;
                }
                case "reject", "r", "n", "no" -> {
                    System.out.println("\n  ✗ 已拒绝。未执行任何写操作。\n");
                    yield ApprovalDecision.REJECT;
                }
                case "cancel", "c" -> {
                    System.out.println("\n  — 已取消\n");
                    yield ApprovalDecision.CANCEL;
                }
                default -> {
                    System.out.println("\n  无法识别的输入，视为取消。\n");
                    yield ApprovalDecision.CANCEL;
                }
            };
        } catch (UserInterruptException e) {
            System.out.println("\n  审批：Ctrl+C → INTERRUPTED\n");
            return ApprovalDecision.INTERRUPTED;
        } catch (EndOfFileException e) {
            System.out.println("\n  审批：EOF → CANCELLED\n");
            return ApprovalDecision.EOF;
        }
    }

    @Override
    public void onFinalResult(InvestigationView result) {
        System.out.println();
        if (result.status() == UserIncidentStatus.RESOLVED) {
            System.out.println("  ✓ 问题已恢复");
        } else if (result.status() == UserIncidentStatus.INCONCLUSIVE) {
            System.out.println("  ? 无法确定原因");
        } else if (result.status() == UserIncidentStatus.AWAITING_APPROVAL) {
            System.out.println("  ⚠ 需要审批修复操作");
        } else {
            System.out.println("  状态：" + result.status());
        }

        if (result.diagnosis() != null && !result.diagnosis().isBlank()
            && !"未完成诊断".equals(result.diagnosis())) {
            System.out.println("  诊断：" + result.diagnosis());
        }
        if (result.recommendation() != null && !result.recommendation().isBlank()) {
            System.out.println("  建议：" + result.recommendation());
        }
        if (result.actionExecuted() != null && !result.actionExecuted().isBlank()) {
            System.out.println("  执行：" + result.actionExecuted());
        }
        if (result.verificationSummary() != null && !result.verificationSummary().isBlank()) {
            System.out.println("  验证：" + result.verificationSummary());
        }
        if (result.nextAction() != null && !result.nextAction().isBlank()) {
            System.out.println();
            System.out.println("  → " + result.nextAction());
        }
        System.out.println();
        System.out.println(ConsoleRenderer.GRAY + "  Incident：" + result.incidentId()
            + "  |  详情：/ops inspect " + result.incidentId() + ConsoleRenderer.RESET);
        System.out.println();
    }
}
