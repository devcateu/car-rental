package org.example.carrental.support

import java.time.LocalDate
import java.time.LocalDateTime

import org.example.carrental.domain.CarType
import org.example.carrental.domain.RentalPeriod
import org.example.carrental.domain.Reservation
import org.example.carrental.domain.ReservationId
import org.example.carrental.domain.TimeSpan
import org.example.carrental.domain.availability.AvailabilityDay
import org.example.carrental.domain.availability.Occupancy

/**
 * Small builders so the specs read as business scenarios rather than object graphs.
 * Every date is expressed as an offset in days from a fixed Monday, which keeps the
 * expectations readable and the tests independent of the current date.
 * <p>
 * The car types below are the ones this project happens to configure. They live here rather
 * than in the domain precisely because {@code CarType} is not an enum: the specs pick their own
 * vocabulary, and {@code FleetSpec} shows an entirely different set works just as well.
 */
class Fixtures {

    static final LocalDateTime MONDAY_10AM = LocalDateTime.of(2026, 9, 7, 10, 0)

    static final CarType SEDAN = CarType.of('SEDAN')
    static final CarType SUV = CarType.of('SUV')
    static final CarType VAN = CarType.of('VAN')

    static RentalPeriod period(int startDayOffset, int days) {
        RentalPeriod.startingAt(MONDAY_10AM.plusDays(startDayOffset), days)
    }

    static Reservation booking(CarType type, RentalPeriod period) {
        Reservation.of(ReservationId.generate(), type, period)
    }

    static Reservation booking(CarType type, int startDayOffset, int days) {
        booking(type, period(startDayOffset, days))
    }

    /** The calendar day {@code MONDAY_10AM} falls on, offset by whole days. */
    static LocalDate day(int offset) {
        MONDAY_10AM.toLocalDate().plusDays(offset)
    }

    /** A span in hours from midnight of day {@code dayOffset}. */
    static TimeSpan span(int dayOffset, int fromHour, int toHour) {
        TimeSpan.of(day(dayOffset).atStartOfDay().plusHours(fromHour),
                day(dayOffset).atStartOfDay().plusHours(toHour))
    }

    static Occupancy occupancy(TimeSpan span, ReservationId id = ReservationId.generate()) {
        Occupancy.of(id, span)
    }

    /** An availability row for day {@code dayOffset} holding the given spans. */
    static AvailabilityDay availabilityDay(CarType type, int dayOffset, int total, List<TimeSpan> spans = []) {
        spans.inject(AvailabilityDay.empty(type, day(dayOffset), total)) { acc, s -> acc.with(occupancy(s)) }
    }
}
