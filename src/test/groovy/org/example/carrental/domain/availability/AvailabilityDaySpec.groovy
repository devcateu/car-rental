package org.example.carrental.domain.availability

import org.example.carrental.domain.ReservationId
import org.example.carrental.domain.TimeSpan
import spock.lang.Specification

import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.availabilityDay
import static org.example.carrental.support.Fixtures.day
import static org.example.carrental.support.Fixtures.occupancy
import static org.example.carrental.support.Fixtures.span

class AvailabilityDaySpec extends Specification {

    static final TimeSpan WHOLE_DAY = TimeSpan.wholeDay(day(0))

    def "an empty day has its whole fleet spare"() {
        given:
        def row = AvailabilityDay.empty(SUV, day(0), 3)

        expect:
        row.spareCapacityWithin(WHOLE_DAY) == 3
        row.busy().empty
        row.version() == 0
    }

    def "each claim consumes capacity only while it is in force"() {
        given: "two cars, one of them out from 08:00 to 11:00"
        def row = availabilityDay(SUV, 0, 2, [span(0, 8, 11)])

        expect:
        row.spareCapacityWithin(span(0, 9, 10)) == 1
        row.spareCapacityWithin(span(0, 12, 14)) == 2
        row.spareCapacityWithin(WHOLE_DAY) == 1
    }

    def "a car returned at 11:00 is spare again at 11:00"() {
        given: "one car, out until 11:00"
        def row = availabilityDay(SUV, 0, 1, [span(0, 8, 11)])

        expect:
        row.spareCapacityWithin(span(0, 11, 14)) == 1
        row.spareCapacityWithin(span(0, 10, 14)) == 0
    }

    def "spare capacity never goes negative when the fleet has shrunk below what is booked"() {
        given: "three claims survive, but only one car is still in service"
        def row = availabilityDay(SUV, 0, 1, [span(0, 8, 12), span(0, 8, 12), span(0, 8, 12)])

        expect:
        row.spareCapacityWithin(WHOLE_DAY) == 0
        row.isOversoldWithin(WHOLE_DAY)
    }

    def "adding the same reservation twice changes nothing"() {
        given:
        def id = ReservationId.generate()
        def row = AvailabilityDay.empty(SUV, day(0), 2).with(occupancy(span(0, 8, 11), id))

        when: "a retried write replays the same claim"
        def again = row.with(occupancy(span(0, 8, 11), id))

        then: "the day is untouched, so the retry cannot double-book"
        again.is(row)
        again.busy().size() == 1
        again.holds(id)
    }

    def "idempotency is by reservation, not by span"() {
        given:
        def id = ReservationId.generate()
        def row = AvailabilityDay.empty(SUV, day(0), 2).with(occupancy(span(0, 8, 11), id))

        when: "the same reservation replayed with a different span is still a no-op"
        def again = row.with(occupancy(span(0, 14, 16), id))

        then:
        again.is(row)

        and: "but a different reservation with the same span is a second claim"
        row.with(occupancy(span(0, 8, 11))).busy().size() == 2
    }

    def "refuses a claim that does not fall on its day"() {
        when:
        AvailabilityDay.empty(SUV, day(0), 2).with(occupancy(span(5, 8, 11)))

        then:
        thrown(IllegalArgumentException)
    }

    def "the next version is what a commit would store"() {
        given:
        def row = AvailabilityDay.empty(SUV, day(0), 2)

        expect:
        row.atNextVersion().version() == 1
        row.atNextVersion().atNextVersion().version() == 2
        row.version() == 0
    }

    def "its window is midnight to midnight"() {
        expect:
        AvailabilityDay.empty(SUV, day(0), 1).dayWindow() == TimeSpan.wholeDay(day(0))
    }

    def "rejects nonsense"() {
        when:
        new AvailabilityDay(carType, date, total, busy, version)

        then:
        thrown(IllegalArgumentException)

        where:
        carType | date   | total | busy | version
        null    | day(0) | 1     | []   | 0
        SUV     | null   | 1     | []   | 0
        SUV     | day(0) | -1    | []   | 0
        SUV     | day(0) | 1     | null | 0
        SUV     | day(0) | 1     | []   | -1
    }
}
