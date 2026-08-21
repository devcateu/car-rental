package org.example.carrental.application;

import org.example.carrental.domain.CarType;

/**
 * Raised when a booking kept losing races and ran out of attempts.
 * <p>
 * Nothing is wrong with the request and the fleet may well have room: the availability rows it
 * needed simply kept moving underneath it. It is worth trying again, which is why it is
 * reported separately from "the fleet is full".
 * <p>
 * This lives in the application layer rather than the domain: contention is a property of how
 * availability is stored and written, and the domain knows nothing about either.
 */
public class BookingContentionException extends RuntimeException {

    private final CarType carType;
    private final int attempts;

    public BookingContentionException(CarType carType, int attempts) {
        super("Could not secure a car of type " + carType + " after " + attempts
                + " attempts because the availability kept changing; the request may succeed if retried");
        this.carType = carType;
        this.attempts = attempts;
    }

    public CarType carType() {
        return carType;
    }

    public int attempts() {
        return attempts;
    }
}
