package com.miniclaw.im;

public record ImChannelStatus(
    String id,
    String name,
    boolean running,
    String linkedUser,
    String stateInfo
) {}
