package com.miniclaw.engine;

/**
 * 慢思考模式。
 * OFF: 单阶段 ReAct，与当前 MVP 行为一致。
 * TWO_STAGE: 两阶段循环——先剥离工具做推理规划，再赋予工具执行。
 */
public enum ThinkingMode {
    OFF,
    TWO_STAGE
}
