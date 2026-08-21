package org.example.carrental.application;

import org.example.carrental.domain.CarType;
import org.example.carrental.domain.RentalPeriod;

/**
 * Intent to book a car of a given type for a given period - which is everything the caller
 * decides. The concrete car is chosen at handover, and no customer identity is recorded; see
 * {@link org.example.carrental.domain.Reservation}.
 */
public record ReserveCarCommand(CarType carType, RentalPeriod period) {

    public ReserveCarCommand {
        if (carType == null) {
            throw new IllegalArgumentException("Car type must not be null");
        }
        if (period == null) {
            throw new IllegalArgumentException("Rental period must not be null");
        }
    }

    public static ReserveCarCommand of(CarType carType, RentalPeriod period) {
        return new ReserveCarCommand(carType, period);
    }
}
