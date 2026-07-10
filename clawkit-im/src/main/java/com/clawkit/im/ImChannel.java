package com.clawkit.im;

import com.clawkit.engine.impl.AgentEngine;
import java.io.IOException;
import java.util.function.Consumer;

public interface ImChannel {

    String id();

    String name();

    void start() throws IOException;

    void stop();

    boolean isRunning();

    void setEngine(AgentEngine engine);

    void setOnImInput(Consumer<String> callback);

    void mirrorToIm(String userInput);

    void finalizeMirror(String result);

    default void awaitShutdown() throws InterruptedException {}

    ImChannelStatus status();
}
