package org.example.carrental.domain.exception;

import org.example.carrental.domain.CarType;
import org.example.carrental.domain.RentalPeriod;
import org.example.carrental.domain.availability.CapacityDecision;
import org.example.carrental.domain.availability.CapacityFailureReason;

/**
 * Raised when the fleet cannot serve a period for the requested car type.
 */
public class NoCarAvailableException extends RuntimeException {

    private final CarType carType;
    private final RentalPeriod period;
    private final CapacityDecision decision;

    public NoCarAvailableException(CarType carType, RentalPeriod period, CapacityDecision decision) {
        super(describe(carType, period, decision));
        this.carType = carType;
        this.period = period;
        this.decision = decision;
    }

    private static String describe(CarType carType, RentalPeriod period, CapacityDecision decision) {
        CapacityFailureReason reason = decision.failureReason().orElseThrow(
                () -> new IllegalArgumentException("An admitted booking is not a failure"));
        return switch (reason) {
            case NO_CARS_OF_TYPE -> "The fleet contains no car of type " + carType;
            case ALL_CARS_BOOKED -> "All cars of type " + carType + " are booked for " + period
                    + decision.blockedOn().map(date -> " (fully booked on " + date + ")").orElse("");
        };
    }

    public CarType carType() {
        return carType;
    }

    public RentalPeriod period() {
        return period;
    }

    public CapacityFailureReason reason() {
        return decision.failureReason().orElseThrow();
    }
}
