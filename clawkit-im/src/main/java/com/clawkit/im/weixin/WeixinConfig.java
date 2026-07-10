package com.clawkit.im.weixin;

import com.clawkit.im.ConfigHelper;

public record WeixinConfig(
    String workDir,
    boolean debug
) {

    public WeixinConfig() {
        this(System.getProperty("user.home"), false);
    }

    public WeixinConfig(String workDir, boolean debug) {
        this.workDir = workDir;
        this.debug = debug;
    }

    // Backward-compatible: old constructor with appId/appSecret
    @Deprecated
    public WeixinConfig(String appId, String appSecret, String workDir) {
        this(workDir != null ? workDir : System.getProperty("user.home"), false);
    }

    public static WeixinConfig fromEnv() {
        String workDir = System.getProperty("user.home");
        String workDirEnv = System.getenv("WEIXIN_WORKDIR");
        if (workDirEnv != null && !workDirEnv.isBlank()) workDir = workDirEnv;

        boolean debug = "true".equalsIgnoreCase(System.getenv("WEIXIN_DEBUG"))
            || "true".equals(ConfigHelper.readConfig("weixin", "debug"));

        return new WeixinConfig(workDir, debug);
    }
}
