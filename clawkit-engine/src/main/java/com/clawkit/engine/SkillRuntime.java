package com.clawkit.engine;

import com.clawkit.context.SkillCatalog;
import com.clawkit.tools.schema.Message;
import java.util.List;

/**
 * Skill 运行时：管理 Skill 的加载、卸载和上下文生成。
 *
 * <p>内部工具只通过此接口访问 Skill，不直接操作 AgentEngine.activeSkills。
 * CLI /skill 命令通过此接口操作，不污染 Session。
 */
public interface SkillRuntime {

    record SkillLoadResult(String name, boolean loaded, String error) {
        public static SkillLoadResult success(String name) {
            return new SkillLoadResult(name, true, null);
        }
        public static SkillLoadResult failed(String name, String error) {
            return new SkillLoadResult(name, false, error);
        }
    }

    record SkillUnloadResult(String name, boolean unloaded) {}

    SkillCatalog catalog();

    SkillLoadResult load(String name);

    SkillUnloadResult unload(String name);

    /** 当前激活的 Skill 上下文消息 */
    List<Message> activeContext();

    boolean isLoaded(String name);

    void rebuildCatalog();
}
