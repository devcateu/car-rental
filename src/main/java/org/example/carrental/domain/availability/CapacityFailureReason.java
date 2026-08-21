package org.example.carrental.domain.availability;

/**
 * Why a period cannot be reserved. Distinguishing "we never had such a car" from "they are all
 * taken over that window" matters to the caller: only the second one is worth retrying with a
 * different date.
 */
public enum CapacityFailureReason {

    NO_CARS_OF_TYPE,
    ALL_CARS_BOOKED
}
