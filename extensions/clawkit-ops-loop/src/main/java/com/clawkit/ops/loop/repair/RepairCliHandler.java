package com.clawkit.ops.loop.repair;

import com.clawkit.tools.action.ActionDescriptor;

import java.time.Duration;
import java.util.Scanner;

/**
 * CLI-based approval interaction for repair actions.
 *
 * <p>Displays incident summary, target, action, parameters, risk, blast radius,
 * and expiry. Accepts "approve", "reject", or times out after a configurable
 * duration. Returns an ApprovalGrant or null.
 *
 * <p>MVP-3: CLI only. Does NOT connect to Feishu for approval.
 */
public final class RepairCliHandler {

    private final Duration timeout;

    public RepairCliHandler(Duration timeout) {
        this.timeout = timeout;
    }

    public RepairCliHandler() {
        this(Duration.ofSeconds(30));
    }

    /**
     * Request human approval for a repair action.
     *
     * @return ApprovalGrant if approved, null if rejected or timed out
     */
    public ApprovalGrant requestApproval(
            String incidentId,
            String canonicalTarget,
            ActionDescriptor descriptor,
            String snapshotHash,
            String approver) {

        displayApprovalPrompt(incidentId, canonicalTarget, descriptor, snapshotHash);

        String decision = readWithTimeout("Approve? [approve/reject] (30s timeout): ");

        if (decision == null) {
            System.out.println("\n[repair] Approval timed out — action rejected.");
            return null;
        }

        decision = decision.trim().toLowerCase();
        if ("approve".equals(decision) || "yes".equals(decision) || "y".equals(decision)) {
            System.out.println("\n[repair] Approved by " + approver + ".");
            return ApprovalGrant.create(incidentId, canonicalTarget,
                descriptor, snapshotHash, approver, ApprovalGrant.DEFAULT_TTL);
        }

        System.out.println("\n[repair] Rejected by " + approver + ".");
        return null;
    }

    private void displayApprovalPrompt(String incidentId, String canonicalTarget,
                                        ActionDescriptor descriptor, String snapshotHash) {
        System.out.println();
        System.out.println("========== REPAIR APPROVAL REQUIRED ==========");
        System.out.println("Incident:     " + incidentId);
        System.out.println("Target:       " + canonicalTarget);
        System.out.println("Action:       " + descriptor.actionCode());
        System.out.println("Parameters:   " + descriptor.parameterDigest());
        System.out.println("Risk Level:   " + descriptor.riskLevel());
        System.out.println("Reversibility:" + descriptor.reversibility());
        System.out.println("Verification: " + descriptor.verificationMode());
        System.out.println("Blast Radius: " + descriptor.blastRadius());
        System.out.println("Snapshot:     " + snapshotHash.substring(0, Math.min(16, snapshotHash.length())) + "...");
        System.out.println("Preconditions:");
        for (String p : descriptor.preconditions()) {
            System.out.println("  - " + p);
        }
        System.out.println("Expected Effects:");
        for (String e : descriptor.expectedEffects()) {
            System.out.println("  - " + e);
        }
        System.out.println("Compensation: " + descriptor.compensationSummary());
        System.out.println("Grant TTL:    " + ApprovalGrant.DEFAULT_TTL.toMinutes() + " minutes");
        System.out.println("==============================================");
    }

    private String readWithTimeout(String prompt) {
        System.out.print(prompt);
        System.out.flush();

        // Use a simple thread-based timeout for CLI input
        final String[] result = {null};
        Thread reader = new Thread(() -> {
            try {
                Scanner scanner = new Scanner(System.in);
                if (scanner.hasNextLine()) {
                    result[0] = scanner.nextLine();
                }
            } catch (Exception ignored) { }
        });
        reader.setDaemon(true);
        reader.start();

        try {
            reader.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (reader.isAlive()) {
            reader.interrupt();
            return null; // timeout
        }

        return result[0];
    }

    /**
     * Auto-approve for E2E testing only. Never use in production.
     */
    public ApprovalGrant autoApprove(
            String incidentId,
            String canonicalTarget,
            ActionDescriptor descriptor,
            String snapshotHash) {
        return ApprovalGrant.create(incidentId, canonicalTarget,
            descriptor, snapshotHash, "e2e-auto", Duration.ofHours(1));
    }
}
