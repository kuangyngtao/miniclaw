package com.miniclaw.provider.impl.openai;

import java.util.Map;

/**
 * 列出所有包含 "KEY" 或 "API" 或 "MINICLAW" 或 "DS" 的环境变量名。
 */
public class EnvCheck {

    public static void main(String[] args) {
        System.out.println("=== 搜索环境变量 ===");
        Map<String, String> env = System.getenv();
        for (String name : env.keySet()) {
            String upper = name.toUpperCase();
            if (upper.contains("KEY") || upper.contains("API")
                || upper.contains("MINICLAW") || upper.contains("DEEPSEEK")
                || upper.contains("DS_") || upper.contains("-DS")) {
                String val = env.get(name);
                String preview = val.length() > 12 ? val.substring(0, 8) + "..." : val;
                System.out.printf("  %s = %s%n", name, preview);
            }
        }
        System.out.println("\n共 " + env.size() + " 个环境变量");
    }
}
