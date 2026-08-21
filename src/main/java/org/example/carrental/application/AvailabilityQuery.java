package org.example.carrental.application;

import java.util.Optional;

import org.example.carrental.domain.CarType;
import org.example.carrental.domain.RentalPeriod;

/**
 * Question about how much of the fleet is free over a period, without booking anything.
 * <p>
 * The period is the only thing a caller must supply. Naming a car type narrows the answer to
 * that type; leaving it out asks about everything the business offers, which is the question
 * somebody shopping for a car actually has - "what can I get that week?" rather than "can I
 * get an SUV that week?".
 * <p>
 * The optional is a component here because this is a query object: it is never persisted or
 * serialised, and the two shapes differ only in that one field.
 */
public record AvailabilityQuery(RentalPeriod period, Optional<CarType> carType) {

    public AvailabilityQuery {
        if (period == null) {
            throw new IllegalArgumentException("Rental period must not be null");
        }
        if (carType == null) {
            throw new IllegalArgumentException("Car type must be present or empty, never null");
        }
    }

    /**
     * Availability of every car type the business offers.
     */
    public static AvailabilityQuery forAllTypes(RentalPeriod period) {
        return new AvailabilityQuery(period, Optional.empty());
    }

    public static AvailabilityQuery forType(CarType carType, RentalPeriod period) {
        if (carType == null) {
            throw new IllegalArgumentException("Car type must not be null");
        }
        return new AvailabilityQuery(period, Optional.of(carType));
    }

    /**
     * Builds either shape, treating a missing car type as "all of them".
     */
    public static AvailabilityQuery of(CarType carType, RentalPeriod period) {
        return carType == null ? forAllTypes(period) : forType(carType, period);
    }

    @Override
    public String toString() {
        return "AvailabilityQuery{" + carType.map(CarType::name).orElse("all types") + ", " + period + "}";
    }
}
