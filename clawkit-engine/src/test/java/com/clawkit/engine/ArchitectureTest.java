package com.clawkit.engine;

import com.clawkit.tools.Registry;
import com.clawkit.tools.schema.ToolCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * P0-R 架构护栏：10 条硬规则（无 FreezingArchRule），零违规。
 *
 * <p>只分析 main 源码（排除 test）。
 * <ul>
 *   <li>规则1-2: 工具执行唯一入口 + 模块隔离</li>
 *   <li>规则3-6: Provider/Context/Observability 不反向依赖 engine</li>
 *   <li>规则7: LLMProvider.generate 仅 ObservingProviderGateway</li>
 *   <li>规则8: ContextManager.compact 仅 DefaultContextPipeline</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.clawkit", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    /**
     * 规则1：engine 中除 ToolCallExecutor 外不得调用 Registry.execute(ToolCall)。
     */
    @ArchTest
    static final ArchRule no_direct_registry_execute_in_engine =
        noClasses()
            .that().resideInAnyPackage("com.clawkit.engine..")
            .and().doNotHaveSimpleName("ToolCallExecutor")
            .should().callMethod(Registry.class, "execute", ToolCall.class);

    /**
     * 规则2：tools / memory 模块不依赖 engine。
     * 基础模块必须独立于编排层。
     */
    @ArchTest
    static final ArchRule tools_independent_of_engine =
        noClasses()
            .that().resideInAnyPackage("com.clawkit.tools..", "com.clawkit.memory..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.clawkit.engine..");

    /**
     * 规则3：engine 模块不直接消费 OpenAI 具体 DTO。
     * Provider 协议细节应通过 LLMProvider 接口隔离。
     */
    @ArchTest
    static final ArchRule engine_no_openai_dto =
        noClasses()
            .that().resideInAnyPackage("com.clawkit.engine..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.clawkit.provider.impl.openai..");

    /**
     * 规则4：observability 不反向依赖 engine。
     * 观测模块应只依赖稳定的数据契约。
     */
    @ArchTest
    static final ArchRule observability_independent_of_engine =
        noClasses()
            .that().resideInAnyPackage("com.clawkit.observability..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.clawkit.engine..");

    /**
     * 规则5：context 模块不依赖 engine。
     */
    @ArchTest
    static final ArchRule context_independent_of_engine =
        noClasses()
            .that().resideInAnyPackage("com.clawkit.context..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.clawkit.engine..");

    /**
     * 规则6：provider 模块不依赖 engine / observability。
     */
    @ArchTest
    static final ArchRule provider_independent_of_engine =
        noClasses()
            .that().resideInAnyPackage("com.clawkit.provider..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.clawkit.engine..", "com.clawkit.observability..");

    /**
     * 规则7：engine 中除 ProviderGateway 实现外不得调用 LLMProvider.generate()。
     */
    @ArchTest
    static final ArchRule no_direct_llmprovider_generate =
        noClasses()
            .that().resideInAnyPackage("com.clawkit.engine..")
            .and().doNotHaveSimpleName("ObservingProviderGateway")
            .should().callMethod(com.clawkit.provider.LLMProvider.class, "generate",
                java.util.List.class, java.util.List.class);

    /**
     * 规则8：engine 不得直接调用 ContextManager.compact()（应通过 ContextPipeline）。
     */
    @ArchTest
    static final ArchRule no_direct_context_compact =
        noClasses()
            .that().resideInAnyPackage("com.clawkit.engine..")
            .and().doNotHaveSimpleName("DefaultContextPipeline")
            .should().callMethod(com.clawkit.context.ContextManager.class, "compact",
                java.util.List.class, int.class);

    /** 规则9：AgentEngine 门面不重新吸收 PlanExecutor 执行职责。 */
    @ArchTest
    static final ArchRule agent_engine_does_not_own_plan_execution =
        noClasses()
            .that().haveSimpleName("AgentEngine")
            .should().dependOnClassesThat().haveSimpleName("PlanExecutor");

    /** 规则10：AgentEngine 不重新定义 internal-tool schema。 */
    @ArchTest
    static final ArchRule agent_engine_does_not_own_internal_tool_definitions =
        noClasses()
            .that().haveSimpleName("AgentEngine")
            .should().dependOnClassesThat().haveSimpleName("AgentInternalToolDefinitions");
}
