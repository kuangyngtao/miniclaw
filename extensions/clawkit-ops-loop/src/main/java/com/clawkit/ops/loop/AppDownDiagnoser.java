package com.clawkit.ops.loop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

public final class AppDownDiagnoser {

    public Diagnosis diagnose(EvidenceBundle bundle) {
        Evidence probe = first(bundle, EvidenceType.HTTP_PROBE, "gateway-health");
        Evidence demoContainer = first(bundle, EvidenceType.CONTAINER_STATUS, "demo-api");
        Evidence gatewayContainer = first(bundle, EvidenceType.CONTAINER_STATUS, "gateway");
        Evidence logs = first(bundle, EvidenceType.LOGS, "gateway");

        Integer status = integer(probe, "data", "statusCode");
        Boolean demoRunning = bool(demoContainer, "data", "state", "Running");
        Boolean gatewayRunning = bool(gatewayContainer, "data", "state", "Running");
        String logText = text(logs, "data", "text");

        List<String> supporting = new ArrayList<>();
        List<String> contradicting = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (status != null && status >= 500) supporting.add(probe.evidenceId());
        else if (status == null) missing.add("gateway HTTP status");
        else contradicting.add(probe.evidenceId());

        if (Boolean.FALSE.equals(demoRunning)) supporting.add(demoContainer.evidenceId());
        else if (demoRunning == null) missing.add("demo-api container running state");
        else contradicting.add(demoContainer.evidenceId());

        if (Boolean.TRUE.equals(gatewayRunning)) supporting.add(gatewayContainer.evidenceId());
        else if (gatewayRunning == null) missing.add("gateway container running state");
        else contradicting.add(gatewayContainer.evidenceId());

        if (logText != null && (logText.contains("Connection refused")
            || logText.contains("upstream") || logText.contains("502"))) {
            supporting.add(logs.evidenceId());
        }

        boolean appDown = status != null && status >= 500;
        boolean stopped = Boolean.FALSE.equals(demoRunning);
        if (appDown && stopped && Boolean.TRUE.equals(gatewayRunning)) {
            return new Diagnosis(
                "DEMO_API_CONTAINER_STOPPED", 0.99,
                supporting, contradicting,
                List.of("GATEWAY_UPSTREAM_NETWORK_FAILURE"),
                missing, "ESCALATE_RESTART_DEMO_API", false);
        }
        return new Diagnosis(
            "INCONCLUSIVE", 0.0, supporting, contradicting,
            List.of("DEMO_API_CONTAINER_STOPPED", "GATEWAY_FAILURE"),
            missing, "ESCALATE", false);
    }

    private static Evidence first(EvidenceBundle bundle, EvidenceType type, String scopePart) {
        return bundle.evidence().stream()
            .filter(e -> e.type() == type && e.scope().contains(scopePart))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "missing evidence: " + type + " scope=" + scopePart));
    }

    private static Integer integer(Evidence evidence, String... path) {
        JsonNode node = at(evidence.fact(), path);
        return node != null && node.isIntegralNumber() ? node.intValue() : null;
    }

    private static Boolean bool(Evidence evidence, String... path) {
        JsonNode node = at(evidence.fact(), path);
        return node != null && node.isBoolean() ? node.booleanValue() : null;
    }

    private static String text(Evidence evidence, String... path) {
        JsonNode node = at(evidence.fact(), path);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static JsonNode at(JsonNode node, String... path) {
        JsonNode current = node;
        for (String part : path) {
            if (current == null) return null;
            current = current.get(part);
        }
        return current;
    }
}
