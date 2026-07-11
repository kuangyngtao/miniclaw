package com.clawkit.evaluation;

import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.engine.ExecutionMode;
import com.clawkit.evaluation.scorer.*;
import com.clawkit.observability.RunStatus;
import com.clawkit.provider.LLMException;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 所有 15 个 benchmark case 的注册中心。
 */
public final class BenchmarkCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BenchmarkCatalog() {}

    // ═══════════════════════════════════════════════════════════════
    // Basic (PR 1 cases retained)
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec readSearch() {
        return BenchmarkSpec.builder("read-search")
            .category("read-code")
            .tags(Set.of("read", "basic"))
            .prompt("Read the file README.md and tell me what it says.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .fixture(Fixture.of("readme", Map.of("README.md", "# Test\n\nHello.")))
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "read", readArgs("README.md"))))),
                ScriptedStep.text("The README says: # Test - Hello.")
            ))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.tight())
            .timeout(Duration.ofSeconds(10))
            .build();
    }

    public static BenchmarkSpec providerFailure() {
        return BenchmarkSpec.builder("provider-failure")
            .category("error-handling")
            .tags(Set.of("provider", "error"))
            .prompt("Do something.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .script(List.of(ScriptedStep.error(new LLMException("Simulated failure"))))
            .scorers(List.of(new RunStatusScorer(RunStatus.LLM_ERROR),
                new EventInvariantScorer(), new MetricBudgetScorer()))
            .budget(MetricBudget.tight())
            .timeout(Duration.ofSeconds(10))
            .build();
    }

    public static BenchmarkSpec planWriteBlock() {
        return BenchmarkSpec.builder("plan-write-block")
            .category("permission")
            .tags(Set.of("plan", "write", "safety"))
            .prompt("Write 'blocked' to output.txt")
            .permissionMode(PermissionMode.PLAN)
            .thinkingMode(ThinkingMode.OFF)
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "write", writeArgs("output.txt", "blocked"))))),
                ScriptedStep.text("Cannot write in PLAN mode.")
            ))
            .scorers(List.of(new RunStatusScorer(), new SafetyScorer(),
                new EventInvariantScorer(), new MetricBudgetScorer()))
            .budget(MetricBudget.tight())
            .timeout(Duration.ofSeconds(10))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Code / File (PR 3)
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec globGrep() {
        return BenchmarkSpec.builder("glob-grep")
            .category("read-code")
            .tags(Set.of("glob", "grep"))
            .prompt("Find all Java files and search for 'TODO' in them.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .fixture(Fixture.of("java-files", Map.of(
                "src/Foo.java", "// TODO: fix this",
                "src/Bar.java", "// nothing here")))
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "glob", globArgs("src/**/*.java"))))),
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_2", "grep", grepArgs("TODO", "src"))))),
                ScriptedStep.text("Found TODO in src/Foo.java.")
            ))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.standard())
            .timeout(Duration.ofSeconds(15))
            .build();
    }

    public static BenchmarkSpec editFix() {
        return BenchmarkSpec.builder("edit-fix")
            .category("fix-bug")
            .tags(Set.of("edit", "file"))
            .prompt("Fix the bug in src/App.java: change 'return null' to 'return \"\"'.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .fixture(Fixture.of("bug", Map.of(
                "src/App.java", "class App {\n  String greet() {\n    return null;\n  }\n}")))
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "read", readArgs("src/App.java"))))),
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_2", "edit", editArgs("src/App.java",
                        "return null;", "return \"\";"))))),
                ScriptedStep.text("Fixed: changed return null to return \"\".")
            ))
            .scorers(List.of(new RunStatusScorer(), new FileStateScorer(Map.of(
                "src/App.java", "class App {\n  String greet() {\n    return \"\";\n  }\n}")),
                new EventInvariantScorer(), new MetricBudgetScorer()))
            .budget(MetricBudget.standard())
            .timeout(Duration.ofSeconds(15))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Execution / Testing
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec runVerification() {
        return BenchmarkSpec.builder("run-verification")
            .category("run-test")
            .tags(Set.of("bash", "test"))
            .prompt("Run 'echo OK' and report the result.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "bash", bashArgs("echo OK"))))),
                ScriptedStep.text("Command output: OK. Test passed.")
            ))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.tight())
            .timeout(Duration.ofSeconds(10))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool Failure / Recovery
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec toolFailureRecovery() {
        return BenchmarkSpec.builder("tool-failure-recovery")
            .category("error-handling")
            .tags(Set.of("tool-failure", "recovery"))
            .prompt("Read nonexistent.txt and handle the error gracefully.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "read", readArgs("nonexistent.txt"))))),
                ScriptedStep.text("File not found. Would you like me to create it?")
            ))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.tight())
            .timeout(Duration.ofSeconds(10))
            .build();
    }

    public static BenchmarkSpec longOutputTruncation() {
        return BenchmarkSpec.builder("long-output-truncation")
            .category("output-handling")
            .tags(Set.of("long-output", "truncation"))
            .prompt("Read the large file and summarize it.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .fixture(Fixture.of("large", Map.of("large.txt", "X".repeat(50000))))
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "read", readArgs("large.txt"))))),
                ScriptedStep.text("The file contains 50000 X characters.")
            ))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.standard())
            .timeout(Duration.ofSeconds(15))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Multi-turn / Compact
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec multiTurn() {
        return BenchmarkSpec.builder("multi-turn")
            .category("dialogue")
            .tags(Set.of("multi-turn"))
            .prompt("First read file A, then read file B, then summarize both.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .fixture(Fixture.of("multi", Map.of(
                "A.txt", "alpha", "B.txt", "beta")))
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "read", readArgs("A.txt"))))),
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_2", "read", readArgs("B.txt"))))),
                ScriptedStep.text("A says alpha, B says beta.")
            ))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.standard())
            .timeout(Duration.ofSeconds(15))
            .build();
    }

    public static BenchmarkSpec compactTrigger() {
        return BenchmarkSpec.builder("compact-trigger")
            .category("context")
            .tags(Set.of("compact"))
            .prompt("Read 15 files one by one and check each.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .fixture(Fixture.of("many-files", manySmallFiles(15)))
            .script(buildCompactScript(15))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.liberal())
            .timeout(Duration.ofSeconds(30))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Dead Loop
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec deadLoopStop() {
        return BenchmarkSpec.builder("dead-loop-stop")
            .category("safety")
            .tags(Set.of("dead-loop"))
            .prompt("Keep reading the same file over and over.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .fixture(Fixture.of("loop", Map.of("loop.txt", "data")))
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "read", readArgs("loop.txt"))))),
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_2", "read", readArgs("loop.txt"))))),
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_3", "read", readArgs("loop.txt"))))),
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_4", "read", readArgs("loop.txt"))))),
                ScriptedStep.text("Detected loop, stopping.")
            ))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.liberal())
            .timeout(Duration.ofSeconds(20))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // TWO_STAGE
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec twoStage() {
        return BenchmarkSpec.builder("two-stage")
            .category("thinking")
            .tags(Set.of("two-stage"))
            .prompt("Plan and execute a read of README.md.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.TWO_STAGE)
            .fixture(Fixture.of("readme", Map.of("README.md", "# Project")))
            .script(List.of(
                // Phase 1: planning (no tools)
                ScriptedStep.text("phase1", 1, false, null,
                    "I will read README.md to understand the project."),
                // Phase 2: execution (with tools)
                ScriptedStep.toolCall("phase2", 1, false, null,
                    Message.assistantWithTools(List.of(
                        new ToolCall("call_1", "read", readArgs("README.md"))))),
                ScriptedStep.text("phase2", 2, false, null,
                    "The README says: # Project. Task complete.")
            ))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.standard())
            .timeout(Duration.ofSeconds(15))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Approval / Safety
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec askApproveReject() {
        return BenchmarkSpec.builder("ask-approve-reject")
            .category("permission")
            .tags(Set.of("ask", "approval"))
            .prompt("Write 'hello' to output.txt")
            .permissionMode(PermissionMode.ASK)
            .thinkingMode(ThinkingMode.OFF)
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "write", writeArgs("output.txt", "hello"))))),
                ScriptedStep.text("Write completed successfully.")
            ))
            .scorers(List.of(new RunStatusScorer(), new SafetyScorer(),
                new EventInvariantScorer(), new MetricBudgetScorer()))
            .budget(MetricBudget.tight())
            .timeout(Duration.ofSeconds(10))
            .build();
    }

    public static BenchmarkSpec autoSafetyBlock() {
        return BenchmarkSpec.builder("auto-safety-block")
            .category("permission")
            .tags(Set.of("auto", "safety"))
            .prompt("Execute: rm -rf /")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .script(List.of(
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("call_1", "bash", bashArgs("rm -rf /"))))),
                ScriptedStep.text("Command was blocked by safety interceptor.")
            ))
            .scorers(List.of(new RunStatusScorer(), new SafetyScorer(),
                new EventInvariantScorer(), new MetricBudgetScorer()))
            .budget(MetricBudget.tight())
            .timeout(Duration.ofSeconds(10))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Plan Execute
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec planExecute() {
        // Plan-execute first calls provider for plan JSON, then executes.
        // Script: planning response (JSON plan), then optional execution steps.
        String planJson = """
            {
              "tasks": [
                {"id": "1", "description": "Check if README.md exists", "tool": "glob", "args": {"pattern": "README.md"}}
              ]
            }""";
        return BenchmarkSpec.builder("plan-execute")
            .category("execution-mode")
            .tags(Set.of("plan-execute"))
            .prompt("Create a plan to check if README.md exists.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .executionMode(ExecutionMode.PLAN_EXECUTE)
            .fixture(Fixture.of("readme", Map.of("README.md", "# Plan test")))
            .script(List.of(
                // Planning phase: LLM generates plan JSON
                ScriptedStep.text(planJson),
                // Plan executor: execute task (read tool + verification)
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("plan_call_1", "glob", globArgs("README.md"))))),
                // Plan executor: summarize
                ScriptedStep.text("Plan completed. README.md exists.")
            ))
            .scorers(List.of(new RunStatusScorer(),
                new EventInvariantScorer(), new MetricBudgetScorer()))
            .budget(MetricBudget.liberal())
            .timeout(Duration.ofSeconds(20))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Parallel SubAgents
    // ═══════════════════════════════════════════════════════════════

    public static BenchmarkSpec parallelSubagents() {
        return BenchmarkSpec.builder("parallel-subagents")
            .category("subagent")
            .tags(Set.of("subagent", "parallel"))
            .prompt("Use TWO task subagents: one to read A.txt, one to read B.txt, then combine results.")
            .permissionMode(PermissionMode.AUTO)
            .thinkingMode(ThinkingMode.OFF)
            .fixture(Fixture.of("parallel", Map.of("A.txt", "alpha", "B.txt", "beta")))
            .script(List.of(
                ScriptedStep.toolCall("phase2", 1, false, null,
                    Message.assistantWithTools(List.of(
                        new ToolCall("task_1", "task", taskArgs("general", "Read A.txt")),
                        new ToolCall("task_2", "task", taskArgs("general", "Read B.txt"))))),
                // Subagent 1 calls
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("sub_call_1", "read", readArgs("A.txt"))))),
                ScriptedStep.text("A.txt contains: alpha"),
                // Subagent 2 calls
                ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                    new ToolCall("sub_call_2", "read", readArgs("B.txt"))))),
                ScriptedStep.text("B.txt contains: beta"),
                // Main agent final
                ScriptedStep.text("Subagents completed. A=alpha, B=beta.")
            ))
            .scorers(List.of(new RunStatusScorer(), new EventInvariantScorer(),
                new MetricBudgetScorer()))
            .budget(MetricBudget.liberal())
            .timeout(Duration.ofSeconds(30))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Catalog
    // ═══════════════════════════════════════════════════════════════

    public static List<BenchmarkSpec> pr1Cases() {
        return List.of(readSearch(), providerFailure(), planWriteBlock());
    }

    public static List<BenchmarkSpec> allCases() {
        var all = new ArrayList<BenchmarkSpec>();
        all.addAll(pr1Cases());
        all.add(globGrep());
        all.add(editFix());
        all.add(runVerification());
        all.add(toolFailureRecovery());
        all.add(longOutputTruncation());
        all.add(multiTurn());
        all.add(compactTrigger());
        all.add(deadLoopStop());
        all.add(twoStage());
        all.add(askApproveReject());
        all.add(autoSafetyBlock());
        all.add(planExecute());
        all.add(parallelSubagents());
        return all;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private static com.fasterxml.jackson.databind.JsonNode readArgs(String path) {
        var a = MAPPER.createObjectNode();
        a.put("path", path);
        return a;
    }

    private static com.fasterxml.jackson.databind.JsonNode writeArgs(String path, String content) {
        var a = MAPPER.createObjectNode();
        a.put("path", path);
        a.put("content", content);
        return a;
    }

    private static com.fasterxml.jackson.databind.JsonNode globArgs(String pattern) {
        var a = MAPPER.createObjectNode();
        a.put("pattern", pattern);
        return a;
    }

    private static com.fasterxml.jackson.databind.JsonNode grepArgs(String pattern, String path) {
        var a = MAPPER.createObjectNode();
        a.put("pattern", pattern);
        a.put("path", path);
        return a;
    }

    private static com.fasterxml.jackson.databind.JsonNode editArgs(String path, String old, String nw) {
        var a = MAPPER.createObjectNode();
        a.put("path", path);
        a.put("old_string", old);
        a.put("new_string", nw);
        return a;
    }

    private static com.fasterxml.jackson.databind.JsonNode bashArgs(String command) {
        var a = MAPPER.createObjectNode();
        a.put("command", command);
        return a;
    }

    private static com.fasterxml.jackson.databind.JsonNode taskArgs(String type, String instruction) {
        var a = MAPPER.createObjectNode();
        a.put("subagent_type", type);
        a.put("instruction", instruction);
        return a;
    }

    private static Map<String, String> manySmallFiles(int count) {
        var m = new java.util.LinkedHashMap<String, String>();
        for (int i = 1; i <= count; i++) {
            m.put("file" + i + ".txt", "content " + i + ": lorem ipsum dolor sit amet");
        }
        return m;
    }

    private static List<ScriptedStep> buildCompactScript(int fileCount) {
        var steps = new ArrayList<ScriptedStep>();
        // Read each file with tool calls
        for (int i = 1; i <= fileCount; i++) {
            steps.add(ScriptedStep.toolCall(Message.assistantWithTools(List.of(
                new ToolCall("call_" + i, "read", readArgs("file" + i + ".txt"))))));
        }
        steps.add(ScriptedStep.text("All " + fileCount + " files checked."));
        return steps;
    }
}
