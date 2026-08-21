package org.example.carrental.application;

import java.util.Comparator;
import java.util.List;

import org.example.carrental.domain.RentalPeriod;

/**
 * The answer to an availability question: one period, and what each car type can still take
 * over it.
 * <p>
 * Always a collection, even when a single type was asked about. One endpoint answering with
 * one shape is easier to consume than an endpoint whose response changes with its parameters;
 * naming a type simply narrows the list to one entry.
 */
public record FleetAvailability(RentalPeriod period, List<Availability> byCarType) {

    public FleetAvailability {
        if (period == null) {
            throw new IllegalArgumentException("Rental period must not be null");
        }
        if (byCarType == null) {
            throw new IllegalArgumentException("Availability per car type must not be null");
        }
        byCarType = byCarType.stream()
                .sorted(Comparator.comparing(Availability::carType))
                .toList();
    }

    public static FleetAvailability of(RentalPeriod period, List<Availability> byCarType) {
        return new FleetAvailability(period, byCarType);
    }

    @Override
    public String toString() {
        return "FleetAvailability{" + period + ", " + byCarType + "}";
    }
}
