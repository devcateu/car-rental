package org.example.carrental.domain.availability;

import java.util.ArrayList;
import java.util.List;

import org.example.carrental.domain.TimeSpan;

/**
 * Decides whether a rental period can still be served by a car type, and produces the
 * availability rows that placing it would leave behind.
 * <p>
 * The calculator is <strong>stateless</strong>: it has no fields at all. Every fact it needs
 * arrives in the {@link CapacityRequest} and every fact it produces leaves in the
 * {@link CapacityDecision}, so the same input always yields the same output and a unit test
 * needs nothing but plain objects - no Spring context, no store, no clock.
 * <p>
 * The rule is a counting one, because a reservation claims a car <em>of a type</em> rather than
 * a named car:
 * <blockquote>
 * a booking fits when, at every instant of its window, fewer claims are in force than the type
 * has cars.
 * </blockquote>
 * Because every instant falls on exactly one calendar day, it is enough to check each day the
 * period touches against that day's own row. Finding the busiest instant of a day is delegated
 * to {@link PeakUsage}.
 * <p>
 * Placing a reservation is idempotent: a day that already holds it is passed through untouched
 * and never re-examined, so repeating a write that may already have landed cannot turn a
 * successful booking into a rejection.
 */
public final class CapacityCalculator {

    public CapacityDecision decide(CapacityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Capacity request must not be null");
        }
        if (request.days().stream().allMatch(day -> day.total() == 0)) {
            return CapacityDecision.rejected(CapacityFailureReason.NO_CARS_OF_TYPE, null);
        }
        TimeSpan rental = request.period().asTimeSpan();
        List<AvailabilityDay> updated = new ArrayList<>(request.days().size());
        for (AvailabilityDay day : request.days()) {
            if (day.holds(request.reservationId())) {
                updated.add(day);
                continue;
            }
            TimeSpan onThisDay = rental.intersect(day.dayWindow()).orElseThrow(
                    () -> new IllegalStateException("Day " + day.date() + " is not touched by " + rental));
            AvailabilityDay withBooking = day.with(Occupancy.of(request.reservationId(), onThisDay));
            if (withBooking.isOversoldWithin(onThisDay)) {
                return CapacityDecision.rejected(CapacityFailureReason.ALL_CARS_BOOKED, day.date());
            }
            updated.add(withBooking);
        }
        return CapacityDecision.admitted(updated);
    }
}
