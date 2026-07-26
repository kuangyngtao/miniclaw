package com.clawkit.ops.loop;

/** PR-M3 §5: overall status of a discovery run. */
public enum DiscoveryStatus {
    /** All required evidence collected successfully. */
    COMPLETE,
    /** Required evidence missing, but some collected. */
    INCOMPLETE,
    /** Transport died mid-collection; remaining items not attempted. */
    TRANSPORT_FAILED
}
