package org.example.carrental.domain

import java.time.LocalDateTime

import org.example.carrental.domain.exception.InvalidRentalPeriodException
import spock.lang.Specification
import spock.lang.Subject

import static org.example.carrental.support.Fixtures.MONDAY_10AM
import static org.example.carrental.support.Fixtures.period

class RentalPeriodSpec extends Specification {

    def "derives the end from the start and the number of days"() {
        given:
        @Subject
        def rental = RentalPeriod.startingAt(MONDAY_10AM, 3)

        expect:
        rental.start() == MONDAY_10AM
        rental.end() == MONDAY_10AM.plusDays(3)
        rental.days() == 3
    }

    def "rejects a rental of #days days"() {
        when:
        RentalPeriod.startingAt(MONDAY_10AM, days)

        then:
        thrown(InvalidRentalPeriodException)

        where:
        days << [0, -1, -10]
    }

    def "rejects a missing start"() {
        when:
        RentalPeriod.startingAt(null, 1)

        then:
        thrown(InvalidRentalPeriodException)
    }

    def "overlap: #scenario"() {
        given:
        def first = period(firstStart, firstDays)
        def second = period(secondStart, secondDays)

        expect: "overlapping is symmetric"
        first.overlaps(second) == overlaps
        second.overlaps(first) == overlaps

        where:
        scenario                                  | firstStart | firstDays | secondStart | secondDays || overlaps
        'identical periods'                       | 0          | 3         | 0           | 3          || true
        'second starts inside the first'          | 0          | 5         | 2           | 3          || true
        'second fully contains the first'         | 2          | 1         | 0           | 5          || true
        'second starts exactly when first ends'   | 0          | 3         | 3           | 2          || false
        'second ends exactly when first starts'   | 3          | 2         | 0           | 3          || false
        'clearly separated periods'               | 0          | 1         | 10          | 1          || false
        'one day apart'                           | 0          | 2         | 3           | 1          || false
        'overlap of a single instant'             | 0          | 3         | 2           | 1          || true
    }

    def "back to back rentals do not overlap, so one car can serve both"() {
        given: "a rental returned on Thursday 10:00 and the next one starting then"
        def first = RentalPeriod.startingAt(MONDAY_10AM, 3)
        def second = RentalPeriod.startingAt(MONDAY_10AM.plusDays(3), 1)

        expect:
        !first.overlaps(second)
        first.end() == second.start()
    }

    def "equal periods are interchangeable"() {
        expect:
        RentalPeriod.startingAt(MONDAY_10AM, 2) == RentalPeriod.startingAt(MONDAY_10AM, 2)
        RentalPeriod.startingAt(MONDAY_10AM, 2).hashCode() == RentalPeriod.startingAt(MONDAY_10AM, 2).hashCode()
        RentalPeriod.startingAt(MONDAY_10AM, 2) != RentalPeriod.startingAt(MONDAY_10AM, 3)
        RentalPeriod.startingAt(MONDAY_10AM, 2) != RentalPeriod.startingAt(LocalDateTime.of(2026, 1, 1, 10, 0), 2)
    }
}
