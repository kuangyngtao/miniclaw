package com.clawkit.context;

import com.clawkit.tools.schema.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 从消息中提取关键约束。第一版：纯正则，不使用 LLM。
 */
public class ConstraintExtractor {

    // 文件路径（跨平台）
    private static final Pattern FILE_PATH = Pattern.compile(
        "(?:[A-Za-z]:[/\\\\][\\w./\\\\-]+|/[\\w./-]+)", Pattern.MULTILINE);

    // clawkit 错误码格式: [X]-NNN
    private static final Pattern ERROR_CODE = Pattern.compile("\\b[A-Z]-\\d{3}\\b");

    // 未完成 todo
    private static final Pattern UNFINISHED_TODO = Pattern.compile("\\[ \\] .+", Pattern.MULTILINE);

    /** 从消息列表中提取所有关键约束 */
    public List<Constraint> extract(List<Message> messages) {
        var constraints = new ArrayList<Constraint>();
        for (var msg : messages) {
            if (msg.content() == null) continue;
            String content = msg.content();

            FILE_PATH.matcher(content).results()
                .forEach(r -> constraints.add(new Constraint.FilePath(r.group())));
            ERROR_CODE.matcher(content).results()
                .forEach(r -> constraints.add(new Constraint.ErrorCode(r.group())));
            UNFINISHED_TODO.matcher(content).results()
                .forEach(r -> constraints.add(new Constraint.UnfinishedTodo(r.group().trim())));
        }
        return constraints.stream().distinct().toList();
    }

    /** 检查约束是否仍存在于压缩后消息中 */
    public List<Constraint> verify(List<Constraint> extracted, List<Message> compacted) {
        var lost = new ArrayList<Constraint>();
        for (var c : extracted) {
            boolean found = compacted.stream()
                .anyMatch(m -> m.content() != null && m.content().contains(c.text()));
            if (!found) lost.add(c);
        }
        return lost;
    }
}
