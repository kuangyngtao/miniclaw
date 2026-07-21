package com.clawkit.ops.mcp;

public final class OpsMcpMain {
    private OpsMcpMain() {}

    public static void main(String[] args) throws Exception {
        OpsTargetConfig config = OpsTargetConfig.fromEnvironment(System.getenv());
        OpsMcpServer server = new OpsMcpServer(new DockerOpsBackend(config));
        server.serve(System.in, System.out);
    }
}
