package org.example.carrental.domain.availability;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The complete output of an admission decision: either the rows as they should now be written,
 * or the reason the booking cannot be taken and the first day that blocks it.
 * <p>
 * Exactly one of the two is present. Use {@link #admitted} and {@link #rejected} rather than
 * the canonical constructor.
 */
public record CapacityDecision(List<AvailabilityDay> updatedDays,
                               Optional<CapacityFailureReason> failureReason,
                               Optional<LocalDate> blockedOn) {

    public CapacityDecision {
        if (updatedDays == null || failureReason == null || blockedOn == null) {
            throw new IllegalArgumentException("A decision must not have null components");
        }
        if (updatedDays.isEmpty() == failureReason.isEmpty()) {
            throw new IllegalArgumentException("A booking is either admitted or rejected, never both or neither");
        }
        updatedDays = List.copyOf(updatedDays);
    }

    public static CapacityDecision admitted(List<AvailabilityDay> updatedDays) {
        if (updatedDays == null || updatedDays.isEmpty()) {
            throw new IllegalArgumentException("An admitted booking must produce rows to write");
        }
        return new CapacityDecision(updatedDays, Optional.empty(), Optional.empty());
    }

    public static CapacityDecision rejected(CapacityFailureReason reason, LocalDate blockedOn) {
        if (reason == null) {
            throw new IllegalArgumentException("A rejection must have a reason");
        }
        return new CapacityDecision(List.of(), Optional.of(reason), Optional.ofNullable(blockedOn));
    }

    public boolean isAdmitted() {
        return failureReason.isEmpty();
    }

    @Override
    public String toString() {
        return isAdmitted()
                ? "CapacityDecision[admitted, " + updatedDays.size() + " days]"
                : "CapacityDecision[rejected=" + failureReason.orElseThrow() + "]";
    }
}
