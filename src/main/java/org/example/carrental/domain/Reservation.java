package org.example.carrental.domain;

/**
 * A confirmed booking of one car <em>of a given type</em> for one period.
 * <p>
 * A claim on capacity, and nothing more. It names no car - the customer books a category, and
 * which physical car they drive away is settled at handover; keeping the reservation free of
 * that commitment is what allows a car to be taken out of service without rewriting anybody's
 * booking. It names no customer either: nothing in this service acts on who booked, so the
 * {@link ReservationId} handed back is the caller's handle. Recording an owner would mean a
 * real customer identity, together with the lookup and cancellation flows that need one.
 * <p>
 * Immutable: there is no cancellation or modification flow in this exercise, so a reservation
 * only ever comes into existence. Two reservations carrying the same id necessarily carry the
 * same everything, so the structural equality a record gives us matches identity here.
 */
public record Reservation(ReservationId id, CarType carType, RentalPeriod period) {

    public Reservation {
        if (id == null) {
            throw new IllegalArgumentException("Reservation id must not be null");
        }
        if (carType == null) {
            throw new IllegalArgumentException("Car type must not be null");
        }
        if (period == null) {
            throw new IllegalArgumentException("Rental period must not be null");
        }
    }

    public static Reservation of(ReservationId id, CarType carType, RentalPeriod period) {
        return new Reservation(id, carType, period);
    }

    public boolean clashesWith(RentalPeriod requested) {
        return period.overlaps(requested);
    }

    @Override
    public String toString() {
        return "Reservation{" + id + ", " + carType + ", " + period + "}";
    }
}
