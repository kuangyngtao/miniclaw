package com.clawkit.cli;

/**
 * 斜杠命令路由器。将 /xxx 输入映射为规范化的命令名。
 *
 * <p>命令处理逻辑仍在 ClawkitApp 中；后续可独立为 handler 类。
 */
public final class SlashCommandRouter {

    public record Command(String name, String arguments) {}

    private SlashCommandRouter() {}

    /** 解析斜杠命令输入，返回规范化的命令名；非斜杠命令返回 null */
    public static String resolve(String input) {
        if (input == null || input.isBlank()) return null;
        if (!input.startsWith("/")) return null;
        String command = input.strip().split("\\s+", 2)[0];
        return switch (command) {
            case "/" -> "menu";
            case "/h", "/help" -> "help";
            case "/q", "/exit" -> "exit";
            case "/t", "/thinking" -> "thinking";
            case "/p", "/plan" -> "plan";
            case "/a", "/ask" -> "ask";
            case "/auto" -> "auto";
            case "/plan-exec" -> "plan-exec";
            case "/c", "/clear" -> "clear";
            case "/compact" -> "compact";
            case "/context" -> "context";
            case "/config" -> "config";
            case "/runs" -> "runs";
            case "/metrics" -> "metrics";
            case "/trace" -> "trace";
            case "/feishu-on" -> "feishu-on";
            case "/feishu-off" -> "feishu-off";
            case "/im-on" -> "im-on";
            case "/im-off" -> "im-off";
            case "/im-status" -> "im-status";
            case "/skill" -> "skill";
            case "/mcp" -> "mcp";
            case "/memory" -> "memory";
            case "/remember" -> "remember";
            case "/session" -> "session";
            default -> null;
        };
    }

    /** Parse and normalize a command once; callers never need prefix matching. */
    public static Command parse(String input) {
        String name = resolve(input);
        if (name == null) return null;
        String trimmed = input.strip();
        int firstSpace = trimmed.indexOf(' ');
        return new Command(name, firstSpace < 0 ? "" : trimmed.substring(firstSpace + 1).strip());
    }
}
