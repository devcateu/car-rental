package org.example.carrental.domain.availability;

import java.time.LocalDate;

import org.example.carrental.domain.CarType;

/**
 * Identity of one availability row: a car type on a calendar day.
 */
public record AvailabilityKey(CarType carType, LocalDate date) {

    public AvailabilityKey {
        if (carType == null) {
            throw new IllegalArgumentException("Car type must not be null");
        }
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
    }

    public static AvailabilityKey of(CarType carType, LocalDate date) {
        return new AvailabilityKey(carType, date);
    }

    @Override
    public String toString() {
        return carType + "/" + date;
    }
}
