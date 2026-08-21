package org.example.carrental.domain;

import java.util.List;
import java.util.Optional;

/**
 * Storage port for reservations - the record of what was sold and to whom.
 * <p>
 * Reservations are a <em>read model</em>: what a window can still take is answered by
 * {@link org.example.carrental.domain.availability.AvailabilityStore}, not from here. See
 * {@code docs/adr/0002-storing-availability.md}.
 */
public interface ReservationRepository {

    Reservation save(Reservation reservation);

    Optional<Reservation> findById(ReservationId id);

    /**
     * Reservations of the given type whose rental period overlaps {@code period}.
     * <p>
     * Narrowing by period is the storage layer's job, not the caller's: this is the exact
     * slice a caller needs, and it is one indexed range query rather than a scan of everything
     * the type has ever been booked for.
     */
    List<Reservation> findOverlapping(CarType carType, RentalPeriod period);
}
