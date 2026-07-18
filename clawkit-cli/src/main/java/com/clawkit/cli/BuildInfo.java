package com.clawkit.cli;

import picocli.CommandLine.IVersionProvider;

/** Single runtime version source backed by the JAR manifest. */
public final class BuildInfo implements IVersionProvider {
    private static final String DEVELOPMENT = "0.1.0";

    public static String version() {
        String value = ClawkitApp.class.getPackage().getImplementationVersion();
        return value == null || value.isBlank() ? DEVELOPMENT : value;
    }

    @Override public String[] getVersion() {
        return new String[]{"clawkit " + version()};
    }
}
