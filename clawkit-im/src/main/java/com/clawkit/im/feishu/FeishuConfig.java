package com.clawkit.im.feishu;

import com.clawkit.im.ConfigHelper;

public record FeishuConfig(
    String appId,
    String appSecret,
    int port,
    String workDir,
    String publicUrl
) {

    public static FeishuConfig fromEnv() {
        String appId = envOrConfig("FEISHU_APP_ID", "feishu", "appId");
        String appSecret = envOrConfig("FEISHU_APP_SECRET", "feishu", "appSecret");

        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException(
                "[C-003] FEISHU_APP_ID and FEISHU_APP_SECRET must be set (env or ~/.clawkit/config.yaml).");
        }

        int port = 8080;
        String portStr = envOrConfig("FEISHU_PORT", "feishu", "port");
        if (portStr != null && !portStr.isBlank()) {
            try {
                port = Integer.parseInt(portStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                    "[C-003] FEISHU_PORT must be a valid integer, got: " + portStr);
            }
        }

        String workDir = System.getProperty("user.home");
        String workDirEnv = System.getenv("FEISHU_WORKDIR");
        if (workDirEnv != null && !workDirEnv.isBlank()) workDir = workDirEnv;

        String publicUrl = System.getenv("FEISHU_PUBLIC_URL");

        return new FeishuConfig(appId, appSecret, port, workDir, publicUrl);
    }

    private static String envOrConfig(String envKey, String... configPath) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) return envVal;
        return ConfigHelper.readConfig(configPath);
    }
}
