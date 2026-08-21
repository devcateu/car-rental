package org.example.carrental.api.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.example.carrental.domain.CarType;
import org.example.carrental.domain.Reservation;

/**
 * Confirmation of a booking.
 * <p>
 * It names a car <em>type</em> and no car: the customer reserved a category, and the vehicle
 * they drive away is picked at handover. Returning a car id here would promise something the
 * business has not committed to, and would have to be broken every time a car went in for
 * repair.
 * <p>
 * The {@code reservationId} is the caller's handle on the booking - the service records no
 * customer identity of its own.
 */
public record ReservationResponse(

        String reservationId,
        CarType carType,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime startDateTime,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime endDateTime,

        int days) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.id().toString(),
                reservation.carType(),
                reservation.period().start(),
                reservation.period().end(),
                reservation.period().days());
    }
}
