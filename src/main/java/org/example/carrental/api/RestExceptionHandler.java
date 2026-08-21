package org.example.carrental.api;

import java.util.List;

import org.example.carrental.api.dto.ErrorResponse;
import org.example.carrental.application.BookingContentionException;
import org.example.carrental.domain.exception.InvalidRentalPeriodException;
import org.example.carrental.domain.exception.NoCarAvailableException;
import org.example.carrental.domain.exception.ReservationNotFoundException;
import org.example.carrental.domain.exception.UnknownCarTypeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps domain failures onto HTTP. The interesting pair is 400 for an unknown car type against
 * 409 for "no car available": asking for a category the business does not offer is a bad
 * request, while asking for one that exists but is fully booked is a perfectly valid request
 * the fleet simply cannot serve.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(NoCarAvailableException.class)
    public ResponseEntity<ErrorResponse> handleNoCarAvailable(NoCarAvailableException exception) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(exception.reason().name(), exception.getMessage()));
    }

    /**
     * Contention is not a refusal. The request was valid and the fleet may well have room, so
     * this is a 503 with {@code Retry-After} rather than the 409 a full fleet gets: the client
     * should try again, where a 409 tells it not to bother.
     */
    @ExceptionHandler(BookingContentionException.class)
    public ResponseEntity<ErrorResponse> handleContention(BookingContentionException exception) {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ErrorResponse.of("BOOKING_CONTENTION", exception.getMessage()));
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ReservationNotFoundException exception) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("RESERVATION_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(UnknownCarTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnknownCarType(UnknownCarTypeException exception) {
        return badRequest("UNKNOWN_CAR_TYPE", exception.getMessage());
    }

    @ExceptionHandler(InvalidRentalPeriodException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPeriod(InvalidRentalPeriodException exception) {
        return badRequest("INVALID_RENTAL_PERIOD", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(RestExceptionHandler::describe)
                .sorted()
                .toList();
        return ResponseEntity
                .badRequest()
                .body(ErrorResponse.of("VALIDATION_FAILED", "The request is not valid", details));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception exception) {
        return badRequest("MALFORMED_REQUEST", exception.getMessage());
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> badRequest(String code, String message) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(code, message));
    }
}
