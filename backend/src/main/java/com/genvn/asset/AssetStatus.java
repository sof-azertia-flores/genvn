package com.genvn.asset;

public enum AssetStatus {
    /** In the plan, not yet worth paying for. Queued later when a scene or beat needs it. */
    PLANNED,
    QUEUED,
    GENERATING,
    /** A complete, validated image file exists on disk. The only state the UI trusts. */
    READY,
    /** Gave up: non-retryable error, or retries exhausted. Placeholder stays. */
    FAILED,
    /** Held back by budget or a disabled provider. Resumes when either changes. */
    PAUSED,
    /** Was READY, but the file is gone or unreadable. Recoverable within budget. */
    MISSING
}
