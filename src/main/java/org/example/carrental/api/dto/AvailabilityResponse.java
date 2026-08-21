package org.example.carrental.api.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.example.carrental.application.FleetAvailability;

/**
 * How much of the fleet is free across a window.
 * <p>
 * The window is stated once, and every car type asked about appears in {@code availability}.
 * Narrowing the request to a single type narrows the list to one entry rather than changing
 * the shape of the answer.
 */
public record AvailabilityResponse(

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime startDateTime,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime endDateTime,

        int days,
        List<CarTypeAvailabilityResponse> availability) {

    public AvailabilityResponse {
        availability = availability == null ? List.of() : List.copyOf(availability);
    }

    public static AvailabilityResponse from(FleetAvailability fleetAvailability) {
        return new AvailabilityResponse(
                fleetAvailability.period().start(),
                fleetAvailability.period().end(),
                fleetAvailability.period().days(),
                fleetAvailability.byCarType().stream().map(CarTypeAvailabilityResponse::from).toList());
    }
}
