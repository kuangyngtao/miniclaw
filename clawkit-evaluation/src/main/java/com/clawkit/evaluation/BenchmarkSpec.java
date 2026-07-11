package com.clawkit.evaluation;

import com.clawkit.engine.PermissionMode;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.engine.ExecutionMode;
import com.clawkit.evaluation.scorer.BenchmarkScorer;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 类型安全的 benchmark case 定义。
 * 15 个 case 统一使用此 record，在 BenchmarkCatalog 中注册。
 */
public record BenchmarkSpec(
    String id,
    String category,
    Set<String> tags,
    String prompt,
    PermissionMode permissionMode,
    ThinkingMode thinkingMode,
    ExecutionMode executionMode,
    Fixture fixture,
    List<ScriptedStep> script,
    List<BenchmarkScorer> scorers,
    MetricBudget budget,
    Duration timeout
) {
    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static class Builder {
        private final String id;
        private String category = "general";
        private Set<String> tags = Set.of();
        private String prompt;
        private PermissionMode permissionMode = PermissionMode.AUTO;
        private ThinkingMode thinkingMode = ThinkingMode.OFF;
        private ExecutionMode executionMode = ExecutionMode.REACT;
        private Fixture fixture = Fixture.empty();
        private List<ScriptedStep> script = List.of();
        private List<BenchmarkScorer> scorers = List.of();
        private MetricBudget budget = MetricBudget.standard();
        private Duration timeout = Duration.ofSeconds(30);

        Builder(String id) { this.id = id; }

        public Builder category(String v) { category = v; return this; }
        public Builder tags(Set<String> v) { tags = v; return this; }
        public Builder prompt(String v) { prompt = v; return this; }
        public Builder permissionMode(PermissionMode v) { permissionMode = v; return this; }
        public Builder thinkingMode(ThinkingMode v) { thinkingMode = v; return this; }
        public Builder executionMode(ExecutionMode v) { executionMode = v; return this; }
        public Builder fixture(Fixture v) { fixture = v; return this; }
        public Builder script(List<ScriptedStep> v) { script = v; return this; }
        public Builder scorers(List<BenchmarkScorer> v) { scorers = v; return this; }
        public Builder budget(MetricBudget v) { budget = v; return this; }
        public Builder timeout(Duration v) { timeout = v; return this; }

        public BenchmarkSpec build() {
            return new BenchmarkSpec(id, category, tags, prompt,
                permissionMode, thinkingMode, executionMode,
                fixture, List.copyOf(script), List.copyOf(scorers),
                budget, timeout);
        }
    }
}
