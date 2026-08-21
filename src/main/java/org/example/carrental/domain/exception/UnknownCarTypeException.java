package org.example.carrental.domain.exception;

import java.util.Set;
import java.util.stream.Collectors;

import org.example.carrental.domain.CarType;

/**
 * Raised when a caller asks for a car type the business does not offer at all.
 * <p>
 * Distinct from {@link NoCarAvailableException}: a type configured with zero cars is a
 * capacity problem, while a type that was never configured is a bad request.
 */
public class UnknownCarTypeException extends RuntimeException {

    private final CarType carType;
    private final Set<CarType> knownTypes;

    public UnknownCarTypeException(CarType carType, Set<CarType> knownTypes) {
        super("Unknown car type " + carType + ". Known types: " + describe(knownTypes));
        this.carType = carType;
        this.knownTypes = Set.copyOf(knownTypes);
    }

    private static String describe(Set<CarType> knownTypes) {
        return knownTypes.stream().map(CarType::name).sorted().collect(Collectors.joining(", "));
    }

    public CarType carType() {
        return carType;
    }
}
