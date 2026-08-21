package org.example.carrental.domain.exception;

import org.example.carrental.domain.ReservationId;

/**
 * Raised when a reservation is looked up by an id that was never issued.
 */
public class ReservationNotFoundException extends RuntimeException {

    private final ReservationId id;

    public ReservationNotFoundException(ReservationId id) {
        super("No reservation found with id " + id);
        this.id = id;
    }

    public ReservationId id() {
        return id;
    }
}
