package org.example.carrental.domain.availability;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.example.carrental.domain.CarType;
import org.example.carrental.domain.ReservationId;
import org.example.carrental.domain.TimeSpan;

/**
 * The availability of one car type on one calendar day: how many cars are in service, and
 * exactly when each of that day's reservations occupies one.
 * <p>
 * This is the stored unit of the model. Holding the claims as intervals rather than as a
 * counter is what lets a car returned at 11:00 be collected again at 11:30 on the same day; and
 * confining them to a single day is what makes the invariant - "never more claims in force at
 * once than there are cars" - local to a single row, so a row version is enough to protect it.
 * <p>
 * {@code version} is the value the row was read at. Writing it back requires that the stored
 * row still carries the same value; see {@link AvailabilityStore#commit}.
 */
public record AvailabilityDay(CarType carType, LocalDate date, int total, List<Occupancy> busy, long version) {

    public AvailabilityDay {
        if (carType == null) {
            throw new IllegalArgumentException("Car type must not be null");
        }
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        if (total < 0) {
            throw new IllegalArgumentException("A day cannot have a negative number of cars: " + total);
        }
        if (busy == null) {
            throw new IllegalArgumentException("Occupancy list must not be null");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Version must not be negative");
        }
        busy = List.copyOf(busy);
    }

    public static AvailabilityDay empty(CarType carType, LocalDate date, int total) {
        return new AvailabilityDay(carType, date, total, List.of(), 0);
    }

    public AvailabilityKey key() {
        return AvailabilityKey.of(carType, date);
    }

    /**
     * Midnight to midnight - the outer bound of anything this row may hold.
     */
    public TimeSpan dayWindow() {
        return TimeSpan.wholeDay(date);
    }

    public boolean holds(ReservationId reservationId) {
        return busy.stream().anyMatch(occupancy -> occupancy.belongsTo(reservationId));
    }

    /**
     * The same day with one more claim on it. Applying a reservation that is already present
     * returns this day unchanged, which is what makes a retried write safe to repeat.
     */
    public AvailabilityDay with(Occupancy occupancy) {
        if (occupancy == null) {
            throw new IllegalArgumentException("Occupancy must not be null");
        }
        if (holds(occupancy.reservationId())) {
            return this;
        }
        if (!occupancy.span().overlaps(dayWindow())) {
            throw new IllegalArgumentException("Occupancy " + occupancy + " does not fall on " + date);
        }
        List<Occupancy> extended = new ArrayList<>(busy);
        extended.add(occupancy);
        return new AvailabilityDay(carType, date, total, extended, version);
    }

    /**
     * Cars in use at the busiest instant of {@code window} - or of the whole day, where the
     * window covers it. An implementation detail of the two questions callers actually ask:
     * how much is spare, and whether the day is oversold.
     */
    private PeakUsage peakWithin(TimeSpan window) {
        return dayWindow().intersect(window)
                .map(overlap -> PeakUsage.within(overlap, busy.stream().map(Occupancy::span).toList()))
                .orElseGet(PeakUsage::none);
    }

    /**
     * How many further reservations could still be taken across {@code window} on this day.
     * Never negative: a fleet that has shrunk below what is already booked reads as simply
     * full, which is an ordinary state rather than an error.
     */
    public int spareCapacityWithin(TimeSpan window) {
        return Math.max(0, total - peakWithin(window).peak());
    }

    public boolean isOversoldWithin(TimeSpan window) {
        return peakWithin(window).peak() > total;
    }

    /**
     * The same day as it will be stored once this version is committed.
     */
    public AvailabilityDay atNextVersion() {
        return new AvailabilityDay(carType, date, total, busy, version + 1);
    }

    @Override
    public String toString() {
        return "AvailabilityDay{" + carType + " " + date + ", " + busy.size() + "/" + total + ", v" + version + "}";
    }
}
