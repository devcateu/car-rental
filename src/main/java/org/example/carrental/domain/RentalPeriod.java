package org.example.carrental.domain;

import java.time.LocalDateTime;

import org.example.carrental.domain.exception.InvalidRentalPeriodException;

/**
 * The window a car is rented for: a start instant plus a whole number of days.
 * <p>
 * The period is treated as the half-open interval {@code [start, end)}. A car returned at
 * 10:00 can therefore be rented again from 10:00 on the same day - see the "no cleaning /
 * servicing window" tradeoff documented in the README.
 */
public record RentalPeriod(LocalDateTime start, int days) {

    private static final int MINIMUM_DAYS = 1;

    public RentalPeriod {
        if (start == null) {
            throw new InvalidRentalPeriodException("Rental start date and time must be provided");
        }
        if (days < MINIMUM_DAYS) {
            throw new InvalidRentalPeriodException(
                    "Rental must last at least " + MINIMUM_DAYS + " day but was " + days);
        }
    }

    public static RentalPeriod startingAt(LocalDateTime start, int days) {
        return new RentalPeriod(start, days);
    }

    public LocalDateTime end() {
        return start.plusDays(days);
    }

    /**
     * The same period as a plain half-open interval, which is the shape the availability model
     * works in once a booking is clipped to individual days.
     */
    public TimeSpan asTimeSpan() {
        return TimeSpan.of(start, end());
    }

    /**
     * Two periods overlap when they share at least one instant. Touching periods
     * ({@code this.end == other.start}) do not overlap.
     */
    public boolean overlaps(RentalPeriod other) {
        return start.isBefore(other.end()) && other.start().isBefore(end());
    }

    @Override
    public String toString() {
        return "[" + start + " -> " + end() + ")";
    }
}
