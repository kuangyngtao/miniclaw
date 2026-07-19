package com.clawkit.tools.action;

import com.clawkit.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActionDescriptorTest {

    private ActionDescriptor descriptor(String target, String paramDigest) {
        return new ActionDescriptor(
            "file.write", target, paramDigest,
            ToolRiskLevel.HIGH, Reversibility.REVERSIBLE,
            ActionReliability.idempotentSetter(), VerificationMode.DETERMINISTIC,
            List.of("pre:sha256=abc"), List.of("file-sha256:/tmp/a.txt:def"),
            "restore previous content", "single file");
    }

    @Test
    void fingerprintIsStableForSameAction() {
        assertEquals(descriptor("file:/a", "d1").fingerprint(),
                     descriptor("file:/a", "d1").fingerprint());
    }

    @Test
    void fingerprintChangesOnTargetOrParamDrift() {
        String base = descriptor("file:/a", "d1").fingerprint();
        assertNotEquals(base, descriptor("file:/b", "d1").fingerprint());
        assertNotEquals(base, descriptor("file:/a", "d2").fingerprint());
    }

    @Test
    void fingerprintChangesOnRiskOrVerificationDrift() {
        ActionDescriptor a = descriptor("file:/a", "d1");
        ActionDescriptor lowRisk = new ActionDescriptor(
            a.actionCode(), a.canonicalTarget(), a.parameterDigest(),
            ToolRiskLevel.LOW, a.reversibility(), a.reliability(),
            a.verificationMode(), a.preconditions(), a.expectedEffects(),
            a.compensationSummary(), a.blastRadius());
        ActionDescriptor manual = new ActionDescriptor(
            a.actionCode(), a.canonicalTarget(), a.parameterDigest(),
            a.riskLevel(), a.reversibility(), a.reliability(),
            VerificationMode.MANUAL_REQUIRED, a.preconditions(), a.expectedEffects(),
            a.compensationSummary(), a.blastRadius());
        assertNotEquals(a.fingerprint(), lowRisk.fingerprint());
        assertNotEquals(a.fingerprint(), manual.fingerprint());
    }

    @Test
    void contentDerivedActionIdIgnoresToolCallId() {
        // 同一动作内容 → 同一逻辑 ID（模型换 toolCallId 重发不会绕过去重）
        assertEquals(descriptor("file:/a", "d1").contentDerivedActionId(),
                     descriptor("file:/a", "d1").contentDerivedActionId());
        assertNotEquals(descriptor("file:/a", "d1").contentDerivedActionId(),
                        descriptor("file:/a", "d2").contentDerivedActionId());
    }

    @Test
    void targetKeyEqualsCanonicalTarget() {
        assertEquals("file:/a", descriptor("file:/a", "d1").targetKey());
    }

    @Test
    void rejectsBlankRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new ActionDescriptor(
            " ", "file:/a", "d", ToolRiskLevel.HIGH, Reversibility.REVERSIBLE,
            ActionReliability.none(), VerificationMode.MANUAL_REQUIRED,
            null, null, null, null));
        assertThrows(NullPointerException.class, () -> new ActionDescriptor(
            "x", "file:/a", "d", null, Reversibility.REVERSIBLE,
            ActionReliability.none(), VerificationMode.MANUAL_REQUIRED,
            null, null, null, null));
    }

    @Test
    void nullCollectionsBecomeEmpty() {
        ActionDescriptor d = new ActionDescriptor(
            "x", "file:/a", "d", ToolRiskLevel.HIGH, Reversibility.IRREVERSIBLE,
            ActionReliability.none(), VerificationMode.MANUAL_REQUIRED,
            null, null, null, null);
        assertEquals(List.of(), d.preconditions());
        assertEquals(List.of(), d.expectedEffects());
        assertEquals("", d.compensationSummary());
        assertEquals("", d.blastRadius());
    }

    @Test
    void identityFactoryBuildsIdempotencyKey() {
        ActionIdentity id = ActionIdentity.of("file.write:abc", 2, "file:/a", "fp");
        assertEquals("file.write:abc#2", id.idempotencyKey());
        assertThrows(IllegalArgumentException.class,
            () -> ActionIdentity.of("x", 0, "t", "fp"));
    }

    @Test
    void fileTargetNormalizesEquivalentPaths() {
        String a = ActionTargets.fileTarget(Path.of("D:/tmp/x/../a.txt"));
        String b = ActionTargets.fileTarget(Path.of("D:/tmp/a.txt"));
        assertEquals(a, b);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            // Windows 大小写不敏感：不同大小写必须映射到同一 targetKey
            assertEquals(ActionTargets.fileTarget(Path.of("D:/TMP/A.TXT")),
                         ActionTargets.fileTarget(Path.of("d:/tmp/a.txt")));
        }
    }
}
