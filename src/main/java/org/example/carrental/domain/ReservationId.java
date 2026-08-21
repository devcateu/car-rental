package org.example.carrental.domain;

import java.util.UUID;

/**
 * Identity of a reservation. Opaque to clients: they only ever echo it back.
 */
public record ReservationId(UUID value) {

    public ReservationId {
        if (value == null) {
            throw new IllegalArgumentException("Reservation id must not be null");
        }
    }

    public static ReservationId generate() {
        return new ReservationId(UUID.randomUUID());
    }

    public static ReservationId of(UUID value) {
        return new ReservationId(value);
    }

    public static ReservationId fromString(String value) {
        try {
            return new ReservationId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed reservation id: " + value, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
