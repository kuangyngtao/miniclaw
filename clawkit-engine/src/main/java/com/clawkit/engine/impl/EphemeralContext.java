package com.clawkit.engine.impl;

import com.clawkit.tools.schema.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时临时上下文容器。
 * 不持久化到 session，每次 run() 生命周期结束时清空。
 */
public class EphemeralContext {

    private final List<Message> memory = new ArrayList<>();
    private final List<Message> workspace = new ArrayList<>();
    private final List<Message> runtime = new ArrayList<>();

    public List<Message> memory() { return memory; }
    public List<Message> workspace() { return workspace; }
    public List<Message> runtime() { return runtime; }

    public void clear() {
        memory.clear();
        workspace.clear();
        runtime.clear();
    }

    public boolean isEmpty() {
        return memory.isEmpty() && workspace.isEmpty() && runtime.isEmpty();
    }
}
