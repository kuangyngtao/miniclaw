package com.clawkit.engine;

/**
 * 权限模式（引擎兼容层）。
 *
 * @deprecated 使用 {@link com.clawkit.tools.PermissionMode} 替代。
 *             引擎端代码应迁移至 tools 模块的 PermissionMode。
 */
@Deprecated
public enum PermissionMode {
    AUTO,
    ASK,
    PLAN;

    /** 转换为 tools 模块的 PermissionMode */
    public com.clawkit.tools.PermissionMode toToolsMode() {
        return switch (this) {
            case AUTO -> com.clawkit.tools.PermissionMode.AUTO;
            case ASK -> com.clawkit.tools.PermissionMode.ASK;
            case PLAN -> com.clawkit.tools.PermissionMode.PLAN;
        };
    }
}
