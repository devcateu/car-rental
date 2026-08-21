package org.example.carrental.domain.availability;

/**
 * Whether a version-checked write took effect.
 */
public enum CommitOutcome {

    /** Every row still carried the version it was read at, and all were written. */
    COMMITTED,

    /** At least one row had moved on; nothing was written and the caller must start again. */
    CONFLICT
}
