package org.example.carrental.domain.availability;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.example.carrental.domain.TimeSpan;

/**
 * The largest number of claims that are simultaneously in force at any instant of a window,
 * and when that first happens.
 * <p>
 * This is the whole question once reservations name a car <em>type</em> rather than a car: a
 * new booking fits exactly when the busiest instant of its window still leaves a car spare. It
 * is not enough to count the claims that overlap the window - ten one-day rentals spread across
 * a fortnight overlap a fortnight-long request but never coincide with each other, and two cars
 * are plenty.
 * <p>
 * The peak is also exactly the number of cars needed: reservations are intervals, "two
 * reservations cannot share a car" makes them an interval graph, and interval graphs are
 * perfect, so the chromatic number equals the largest clique. That is why counting is a
 * sufficient admission test - if the peak never exceeds the fleet size, an assignment of
 * rentals to cars provably exists.
 * <p>
 * Computed with a sweep line: every relevant span contributes a {@code +1} where it starts and
 * a {@code -1} where it ends, both clipped to the window, and the events are walked in time
 * order keeping a running total. Ends are processed before starts at the same instant, which is
 * what makes back-to-back rentals share one car. Cost is O(n log n).
 *
 * @param peak           highest number of claims in force at once, never negative
 * @param firstReachedAt the earliest instant at which {@code peak} is reached, absent when the
 *                       window is completely free
 */
public record PeakUsage(int peak, Optional<LocalDateTime> firstReachedAt) {

    public PeakUsage {
        if (peak < 0) {
            throw new IllegalArgumentException("Peak usage must not be negative but was " + peak);
        }
        if (firstReachedAt == null) {
            throw new IllegalArgumentException("First reached instant must not be null");
        }
        if (peak == 0 && firstReachedAt.isPresent()) {
            throw new IllegalArgumentException("An unused window cannot have an instant at which it peaks");
        }
        if (peak > 0 && firstReachedAt.isEmpty()) {
            throw new IllegalArgumentException("A used window must say when it first peaks");
        }
    }

    public static PeakUsage none() {
        return new PeakUsage(0, Optional.empty());
    }

    /**
     * Sweeps {@code spans} across {@code window}. Spans that do not overlap the window are
     * ignored, so the caller may pass everything it has.
     */
    public static PeakUsage within(TimeSpan window, List<TimeSpan> spans) {
        if (window == null) {
            throw new IllegalArgumentException("Window must not be null");
        }
        if (spans == null) {
            throw new IllegalArgumentException("Spans must not be null");
        }
        List<Event> events = eventsClippedTo(window, spans);
        if (events.isEmpty()) {
            return none();
        }
        events.sort(Event.IN_TIME_ORDER);

        int inForce = 0;
        int peak = 0;
        LocalDateTime peakAt = null;
        for (Event event : events) {
            inForce += event.delta();
            if (inForce > peak) {
                peak = inForce;
                peakAt = event.at();
            }
        }
        return new PeakUsage(peak, Optional.ofNullable(peakAt));
    }

    private static List<Event> eventsClippedTo(TimeSpan window, List<TimeSpan> spans) {
        List<Event> events = new ArrayList<>();
        for (TimeSpan span : spans) {
            span.intersect(window).ifPresent(clipped -> {
                events.add(new Event(clipped.startAt(), 1));
                events.add(new Event(clipped.endAt(), -1));
            });
        }
        return events;
    }

    @Override
    public String toString() {
        return firstReachedAt
                .map(at -> "PeakUsage[" + peak + " from " + at + "]")
                .orElse("PeakUsage[idle]");
    }

    /**
     * A change in the number of claims in force, at one instant.
     */
    private record Event(LocalDateTime at, int delta) {

        /**
         * Time order, with releases before claims at the same instant: a car handed back at
         * 10:00 is available to a rental starting at 10:00.
         */
        private static final Comparator<Event> IN_TIME_ORDER =
                Comparator.comparing(Event::at).thenComparingInt(Event::delta);
    }
}
