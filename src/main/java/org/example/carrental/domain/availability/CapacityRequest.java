package org.example.carrental.domain.availability;

import java.time.LocalDate;
import java.util.List;

import org.example.carrental.domain.RentalPeriod;
import org.example.carrental.domain.ReservationId;

/**
 * The complete input of an admission decision: which reservation is being placed, over which
 * period, and the availability rows as they were read.
 * <p>
 * There is no repository, no clock and no configuration behind the calculator, which is what
 * makes it trivially testable - a test constructs this object by hand and asserts on the
 * result.
 */
public record CapacityRequest(ReservationId reservationId, RentalPeriod period, List<AvailabilityDay> days) {

    public CapacityRequest {
        if (reservationId == null) {
            throw new IllegalArgumentException("Reservation id must not be null");
        }
        if (period == null) {
            throw new IllegalArgumentException("Rental period must not be null");
        }
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("An admission decision needs the availability rows it applies to");
        }
        days = List.copyOf(days);
        List<LocalDate> required = period.asTimeSpan().datesTouched();
        if (!days.stream().map(AvailabilityDay::date).toList().equals(required)) {
            throw new IllegalArgumentException(
                    "The rows must be exactly the days the period touches, in order: expected " + required);
        }
    }

    public static CapacityRequest of(ReservationId reservationId, RentalPeriod period, List<AvailabilityDay> days) {
        return new CapacityRequest(reservationId, period, days);
    }

    @Override
    public String toString() {
        return "CapacityRequest{" + reservationId + ", " + period + ", " + days.size() + " days}";
    }
}
