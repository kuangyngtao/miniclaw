package com.clawkit.tools.action;

/**
 * 验证策略：每个副作用动作必须声明如何被独立验证。
 */
public enum VerificationMode {

    /** 确定性断言可验证（文件 hash、原子替换结果）。 */
    DETERMINISTIC,

    /** 需要工作流级验证（服务、端口、HTTP、业务事务）——独立 Verification Run。 */
    WORKFLOW,

    /** 无法自动验证（任意 Bash 等）；可记录动作，但不得自动进入 VERIFIED_SUCCESS。 */
    MANUAL_REQUIRED
}
