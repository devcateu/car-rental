package org.example.carrental.api;

import java.net.URI;
import java.time.LocalDateTime;

import jakarta.validation.Valid;
import org.example.carrental.api.dto.AvailabilityResponse;
import org.example.carrental.api.dto.ReservationResponse;
import org.example.carrental.api.dto.ReserveCarRequest;
import org.example.carrental.application.AvailabilityQuery;
import org.example.carrental.application.FleetAvailability;
import org.example.carrental.application.ReservationService;
import org.example.carrental.domain.CarType;
import org.example.carrental.domain.RentalPeriod;
import org.example.carrental.domain.Reservation;
import org.example.carrental.domain.ReservationId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reservation API. Kept thin on purpose: translate, delegate, translate back.
 */
@RestController
@RequestMapping("/api")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveCarRequest request) {
        Reservation reservation = reservationService.reserve(request.toCommand());
        ReservationResponse response = ReservationResponse.from(reservation);
        return ResponseEntity
                .created(URI.create("/api/reservations/" + response.reservationId()))
                .body(response);
    }

    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse findById(@PathVariable String reservationId) {
        Reservation reservation = reservationService.findById(ReservationId.fromString(reservationId));
        return ReservationResponse.from(reservation);
    }

    /**
     * Availability over a period. {@code carType} is an optional filter: without it the answer
     * covers every type the business offers.
     */
    @GetMapping("/availability")
    public AvailabilityResponse checkAvailability(
            @RequestParam(required = false) CarType carType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDateTime,
            @RequestParam int days) {
        FleetAvailability availability = reservationService.checkAvailability(
                AvailabilityQuery.of(carType, RentalPeriod.startingAt(startDateTime, days)));
        return AvailabilityResponse.from(availability);
    }
}
