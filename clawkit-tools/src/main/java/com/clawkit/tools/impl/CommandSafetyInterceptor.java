package com.clawkit.tools.impl;

import com.clawkit.tools.SafetyInterceptor;
import com.clawkit.tools.schema.ToolCall;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 高危命令拦截器 — 阻断危险 bash 命令，所有权限模式下生效。
 */
public class CommandSafetyInterceptor implements SafetyInterceptor {

    private static final List<Pattern> DANGEROUS = List.of(
        Pattern.compile("rm\\s+(-[rRf]+\\s+)*[/~]"),          // rm -rf / 或 rm -rf ~
        Pattern.compile("rm\\s+(-[rRf]+\\s+)*\\*"),           // rm -rf *
        Pattern.compile("\\b(sudo|pkexec|doas)\\s"),           // 提权命令
        Pattern.compile("chmod\\s+(-R\\s+)?0*777"),           // chmod 777 / chmod -R 777 / chmod 0777
        Pattern.compile(">\\s*/dev/sd"),                       // 覆盖磁盘
        Pattern.compile("mkfs\\.\\w+"),                        // 格式化
        Pattern.compile("dd\\s+if="),                          // dd
        Pattern.compile("\\b(shutdown|reboot|halt|poweroff|init\\s+[06])\\b"), // 关机/重启
        Pattern.compile(Pattern.quote(":(){ :|:& };:"))       // fork bomb
    );

    @Override
    public String check(ToolCall call) {
        if (!"bash".equals(call.name())) return null;
        if (call.arguments() == null) return null;
        String cmd = call.arguments().has("command")
            ? call.arguments().get("command").asText()
            : "";
        for (Pattern p : DANGEROUS) {
            if (p.matcher(cmd).find()) {
                return "高危命令已被安全策略拦截: " + cmd;
            }
        }
        return null;
    }
}
