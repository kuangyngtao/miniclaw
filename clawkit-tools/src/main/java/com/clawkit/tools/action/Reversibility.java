package com.clawkit.tools.action;

/** 动作可逆性。 */
public enum Reversibility {

    /** 可完整回滚（如恢复文件旧内容）。 */
    REVERSIBLE,

    /** 不可回滚但可补偿（如重启服务后再次重启）。 */
    COMPENSATABLE,

    /** 不可逆（如发送消息、删除无备份数据）。 */
    IRREVERSIBLE
}
