package org.example.carrental.domain.exception;

/**
 * Raised when the requested rental window cannot describe a real rental.
 */
public class InvalidRentalPeriodException extends RuntimeException {

    public InvalidRentalPeriodException(String message) {
        super(message);
    }
}
