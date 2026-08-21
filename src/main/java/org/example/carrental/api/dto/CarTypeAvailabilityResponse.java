package org.example.carrental.api.dto;

import org.example.carrental.application.Availability;
import org.example.carrental.domain.CarType;

/**
 * What one car type can still take over the window being asked about.
 *
 * @param fleetSize      cars of this type in service
 * @param availableCount how many further reservations the window can take
 */
public record CarTypeAvailabilityResponse(CarType carType, int fleetSize, int availableCount) {

    public static CarTypeAvailabilityResponse from(Availability availability) {
        return new CarTypeAvailabilityResponse(
                availability.carType(),
                availability.fleetSize(),
                availability.availableCount());
    }
}
