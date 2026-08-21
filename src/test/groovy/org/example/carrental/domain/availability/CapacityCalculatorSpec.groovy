package org.example.carrental.domain.availability

import org.example.carrental.domain.RentalPeriod
import org.example.carrental.domain.ReservationId
import spock.lang.Specification
import spock.lang.Subject

import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.availabilityDay
import static org.example.carrental.support.Fixtures.day
import static org.example.carrental.support.Fixtures.occupancy
import static org.example.carrental.support.Fixtures.period
import static org.example.carrental.support.Fixtures.span

/**
 * The admission rule is a pure function, so every scenario here is "build the rows, assert the
 * decision". No Spring, no store, no clock, no mocks.
 */
class CapacityCalculatorSpec extends Specification {

    @Subject
    def calculator = new CapacityCalculator()

    def id = ReservationId.generate()

    def "refuses a type the fleet runs no cars of"() {
        when:
        def decision = calculator.decide(CapacityRequest.of(id, period(0, 1),
                [availabilityDay(SUV, 0, 0), availabilityDay(SUV, 1, 0)]))

        then:
        !decision.admitted
        decision.failureReason().get() == CapacityFailureReason.NO_CARS_OF_TYPE
    }

    def "admits a booking into an untouched week"() {
        when:
        def decision = calculator.decide(CapacityRequest.of(id, period(0, 1),
                [availabilityDay(SUV, 0, 2), availabilityDay(SUV, 1, 2)]))

        then:
        decision.admitted
        decision.updatedDays()*.date() == [day(0), day(1)]
        decision.updatedDays().every { it.holds(id) }
    }

    def "writes the booking into every day it touches, clipped to each"() {
        when: "a rental from Monday 10:00 for three days"
        def decision = calculator.decide(CapacityRequest.of(id, period(0, 3),
                (0..3).collect { availabilityDay(SUV, it, 1) }))

        then: "it lands on four calendar days, the first and last only partly"
        decision.admitted
        decision.updatedDays()*.date() == [day(0), day(1), day(2), day(3)]

        and:
        claimOn(decision, 0).span() == span(0, 10, 24)
        claimOn(decision, 1).span() == span(1, 0, 24)
        claimOn(decision, 3).span() == span(3, 0, 10)
    }

    def "refuses when any single day of the window is full"() {
        given: "one car, free all week except on the Wednesday"
        def days = [availabilityDay(SUV, 0, 1), availabilityDay(SUV, 1, 1),
                    availabilityDay(SUV, 2, 1, [span(2, 0, 24)]), availabilityDay(SUV, 3, 1)]

        when:
        def decision = calculator.decide(CapacityRequest.of(id, period(0, 3), days))

        then: "one busy instant is enough to refuse the whole window, and it says which day"
        !decision.admitted
        decision.failureReason().get() == CapacityFailureReason.ALL_CARS_BOOKED
        decision.blockedOn().get() == day(2)
    }

    def "the case that motivates holding intervals: same day, no overlap"() {
        given: "one car, already out from 08:00 until 11:00 on the Monday"
        def days = [availabilityDay(SUV, 0, 1, [span(0, 8, 11)]), availabilityDay(SUV, 1, 1)]

        when: "someone collects at 11:30 the same day"
        def collectedAt1130 = RentalPeriod.startingAt(
                day(0).atStartOfDay().plusHours(11).plusMinutes(30), 1)
        def decision = calculator.decide(CapacityRequest.of(id, collectedAt1130, days))

        then: "a per-day counter would have refused this; intervals admit it"
        decision.admitted
    }

    def "and refuses the same booking when it would overlap by half an hour"() {
        given:
        def days = [availabilityDay(SUV, 0, 1, [span(0, 8, 12)]), availabilityDay(SUV, 1, 1)]

        when:
        def collectedAt1130 = RentalPeriod.startingAt(
                day(0).atStartOfDay().plusHours(11).plusMinutes(30), 1)
        def decision = calculator.decide(CapacityRequest.of(id, collectedAt1130, days))

        then:
        !decision.admitted
        decision.failureReason().get() == CapacityFailureReason.ALL_CARS_BOOKED
    }

    def "placing the same reservation again is a no-op, even on a day that is now full"() {
        given: "the booking already landed on both days, and the fleet has since filled up"
        def days = [availabilityDay(SUV, 0, 1).with(occupancy(span(0, 10, 24), id)),
                    availabilityDay(SUV, 1, 1).with(occupancy(span(1, 0, 10), id))]

        when: "a retry replays it"
        def decision = calculator.decide(CapacityRequest.of(id, period(0, 1), days))

        then: "it is admitted unchanged - a retry cannot turn a booking into a rejection"
        decision.admitted
        decision.updatedDays()[0].busy().size() == 1
        decision.updatedDays()[0].is(days[0])
    }

    def "is a pure function - the same input always yields the same output"() {
        given:
        def days = [availabilityDay(SUV, 0, 2, [span(0, 8, 12)]), availabilityDay(SUV, 1, 2)]
        def request = CapacityRequest.of(id, period(0, 1), days)

        expect:
        (1..5).collect { calculator.decide(request) }.unique().size() == 1
    }

    def "insists on being given exactly the days the period touches"() {
        when:
        CapacityRequest.of(id, period(0, 3), [availabilityDay(SUV, 0, 1)])

        then:
        thrown(IllegalArgumentException)
    }

    def "rejects a malformed request"() {
        when:
        CapacityRequest.of(reservationId, rentalPeriod, days)

        then:
        thrown(IllegalArgumentException)

        where:
        reservationId            | rentalPeriod | days
        null                     | null         | null
        ReservationId.generate() | null         | []
        ReservationId.generate() | null         | null
    }

    def "rejects a null request"() {
        when:
        calculator.decide(null)

        then:
        thrown(IllegalArgumentException)
    }

    private static claimOn(CapacityDecision decision, int dayOffset) {
        decision.updatedDays().find { it.date() == day(dayOffset) }.busy().first()
    }
}
