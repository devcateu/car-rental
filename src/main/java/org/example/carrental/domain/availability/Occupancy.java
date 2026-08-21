package org.example.carrental.domain.availability;

import org.example.carrental.domain.ReservationId;
import org.example.carrental.domain.TimeSpan;

/**
 * One reservation's claim on a car of a type, clipped to a single calendar day.
 * <p>
 * The reservation id travels with the claim so that applying the same reservation twice is a
 * no-op: it is what makes a retried write idempotent, and what makes an eventual cancellation
 * able to find its own entries again.
 */
public record Occupancy(ReservationId reservationId, TimeSpan span) {

    public Occupancy {
        if (reservationId == null) {
            throw new IllegalArgumentException("Occupancy must name the reservation that claims it");
        }
        if (span == null) {
            throw new IllegalArgumentException("Occupancy must have a span");
        }
    }

    public static Occupancy of(ReservationId reservationId, TimeSpan span) {
        return new Occupancy(reservationId, span);
    }

    public boolean belongsTo(ReservationId candidate) {
        return reservationId.equals(candidate);
    }

    @Override
    public String toString() {
        return reservationId + "@" + span;
    }
}
