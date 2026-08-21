package org.example.carrental.application;

import org.example.carrental.domain.CarType;
import org.example.carrental.domain.RentalPeriod;

/**
 * How much of a car type is still free across a period.
 * <p>
 * There is no list of free cars: a reservation claims a car of the type, not a named one, so
 * the only meaningful answer is how many more bookings the window can take.
 */
public record Availability(CarType carType, RentalPeriod period, int fleetSize, int availableCount) {

    public Availability {
        if (carType == null) {
            throw new IllegalArgumentException("Car type must not be null");
        }
        if (period == null) {
            throw new IllegalArgumentException("Rental period must not be null");
        }
        if (fleetSize < 0) {
            throw new IllegalArgumentException("Fleet size must not be negative but was " + fleetSize);
        }
        if (availableCount < 0 || availableCount > fleetSize) {
            throw new IllegalArgumentException(
                    "Available cars must be between zero and the fleet size but was " + availableCount);
        }
    }

    public static Availability of(CarType carType, RentalPeriod period, int fleetSize, int availableCount) {
        return new Availability(carType, period, fleetSize, availableCount);
    }

    @Override
    public String toString() {
        return "Availability{" + carType + ", " + period + ", " + availableCount + "/" + fleetSize + "}";
    }
}
