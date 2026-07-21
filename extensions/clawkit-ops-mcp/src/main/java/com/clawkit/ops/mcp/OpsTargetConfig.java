package com.clawkit.ops.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public record OpsTargetConfig(
    Path composeFile,
    String projectName,
    Set<String> allowedServices,
    Map<String, Set<Integer>> allowedPorts,
    Map<String, URI> allowedEndpoints,
    Duration commandTimeout,
    Duration maxLogWindow,
    int maxOutputBytes,
    int maxLogLines
) {
    public OpsTargetConfig {
        composeFile = composeFile.toAbsolutePath().normalize();
        projectName = requireToken(projectName, "projectName");
        allowedServices = Set.copyOf(allowedServices);
        allowedPorts = copyPorts(allowedPorts);
        allowedEndpoints = Map.copyOf(allowedEndpoints);
        if (allowedServices.isEmpty()) {
            throw new IllegalArgumentException("allowedServices must not be empty");
        }
        if (commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        if (maxLogWindow.isNegative() || maxLogWindow.isZero()) {
            throw new IllegalArgumentException("maxLogWindow must be positive");
        }
        if (maxOutputBytes < 256 || maxLogLines < 1) {
            throw new IllegalArgumentException("output limits are too small");
        }
        allowedServices.forEach(s -> requireToken(s, "service"));
        allowedEndpoints.keySet().forEach(k -> requireToken(k, "endpoint"));
    }

    public static OpsTargetConfig fromEnvironment(Map<String, String> env) {
        String compose = required(env, "CLAWKIT_OPS_COMPOSE_FILE");
        String project = required(env, "CLAWKIT_OPS_PROJECT");
        Set<String> services = csv(env.getOrDefault(
            "CLAWKIT_OPS_SERVICES", "gateway,demo-api"));
        Map<String, Set<Integer>> ports = parsePorts(
            env.getOrDefault("CLAWKIT_OPS_PORTS", "gateway:80,demo-api:80"));
        Map<String, URI> endpoints = parseEndpoints(required(env, "CLAWKIT_OPS_ENDPOINTS"));
        return new OpsTargetConfig(Path.of(compose), project, services, ports, endpoints,
            Duration.ofSeconds(10), Duration.ofMinutes(15), 32_768, 200);
    }

    public void requireService(String service) {
        if (!allowedServices.contains(service)) {
            throw new IllegalArgumentException("service is not allowlisted: " + service);
        }
    }

    public void requirePort(String service, int port) {
        requireService(service);
        if (!allowedPorts.getOrDefault(service, Set.of()).contains(port)) {
            throw new IllegalArgumentException(
                "port is not allowlisted for service " + service + ": " + port);
        }
    }

    public URI endpoint(String name) {
        URI endpoint = allowedEndpoints.get(name);
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint is not allowlisted: " + name);
        }
        return endpoint;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing environment variable: " + name);
        }
        return value;
    }

    private static Set<String> csv(String value) {
        Set<String> values = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) values.add(item.trim());
        }
        return values;
    }

    private static Map<String, Set<Integer>> parsePorts(String value) {
        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        for (String item : value.split(",")) {
            String[] pair = item.trim().split(":", 2);
            if (pair.length != 2) {
                throw new IllegalArgumentException("invalid service port entry: " + item);
            }
            result.computeIfAbsent(pair[0], ignored -> new LinkedHashSet<>())
                .add(Integer.parseInt(pair[1]));
        }
        return result;
    }

    private static Map<String, URI> parseEndpoints(String value) {
        Map<String, URI> result = new LinkedHashMap<>();
        for (String item : value.split(",")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length != 2) {
                throw new IllegalArgumentException("invalid endpoint entry: " + item);
            }
            URI uri = URI.create(pair[1]);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("endpoint must use http or https: " + pair[0]);
            }
            result.put(pair[0], uri);
        }
        return result;
    }

    private static Map<String, Set<Integer>> copyPorts(Map<String, Set<Integer>> ports) {
        Map<String, Set<Integer>> copy = new LinkedHashMap<>();
        ports.forEach((service, values) -> copy.put(service, Set.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static String requireToken(String value, String field) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return value;
    }
}
