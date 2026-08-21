package org.example.carrental.api.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.example.carrental.application.ReserveCarCommand;
import org.example.carrental.domain.CarType;
import org.example.carrental.domain.RentalPeriod;

/**
 * Incoming reservation request. Jackson binds it straight into this record - the API layer
 * never touches an untyped tree.
 * <p>
 * It asks for exactly what the requirement asks for: a car type, a start, and a number of days.
 * <p>
 * The constraints live here rather than in the domain so that a bad request comes back as a
 * field-by-field 400 instead of a domain exception; {@link RentalPeriod} enforces the same
 * rules again for callers that never go through HTTP.
 */
public record ReserveCarRequest(

        @NotNull(message = "carType is required and must be one of the configured types")
        CarType carType,

        @NotNull(message = "startDateTime is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime startDateTime,

        @Min(value = 1, message = "days must be at least 1")
        int days) {

    public ReserveCarCommand toCommand() {
        return ReserveCarCommand.of(carType, RentalPeriod.startingAt(startDateTime, days));
    }
}
