package com.clawkit.cli;

import com.clawkit.engine.ApprovalHandler;
import com.clawkit.engine.ApprovalRequest;
import com.clawkit.engine.ApprovalResult;
import java.util.function.Supplier;

/**
 * 控制台审批交互。只做 UI，不判断风险等级。
 *
 * <p>从 ClawkitApp.handleApproval() 提取。
 */
public final class ApprovalConsole {

    private final Supplier<String> lineReader;

    public ApprovalConsole(Supplier<String> lineReader) {
        this.lineReader = lineReader;
    }

    /** 创建为 ApprovalHandler（供 AgentEngine 注入） */
    public ApprovalHandler asHandler() {
        return this::handle;
    }

    private ApprovalResult handle(ApprovalRequest req) {
        System.out.println();
        System.out.println(ConsoleRenderer.GRAY + "  ┌─ 工具确认: " + req.toolName()
            + "  [" + req.riskLevel() + "]" + ConsoleRenderer.RESET);
        if (req.parameters() != null && !req.parameters().isBlank()) {
            System.out.println(ConsoleRenderer.GRAY + "  │ args: "
                + truncate(req.parameters(), 120) + ConsoleRenderer.RESET);
        }
        if (req.llmIntent() != null && !req.llmIntent().isBlank()) {
            System.out.println(ConsoleRenderer.GRAY + "  │ intent: "
                + truncate(req.llmIntent(), 120) + ConsoleRenderer.RESET);
        }
        System.out.println(ConsoleRenderer.GRAY + "  └─────────────────────────────" + ConsoleRenderer.RESET);
        System.out.print("  Approve? [y=approve / a=approve all same type / n=reject / m=modify]: ");

        String input = lineReader.get();
        if (input == null) return new ApprovalResult.Reject("EOF");

        return switch (input.trim()) {
            case "", "y" -> new ApprovalResult.Approve();
            case "a" -> new ApprovalResult.ApproveAllSameType(req.toolName());
            case "n" -> new ApprovalResult.Reject("User declined");
            case "m" -> {
                System.out.print("  Modification guidance: ");
                String guidance = lineReader.get();
                yield new ApprovalResult.ModifyParams(guidance != null ? guidance : "");
            }
            default -> new ApprovalResult.Reject("Invalid input: " + input.trim());
        };
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
