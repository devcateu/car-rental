package org.example.carrental.application

import spock.lang.Specification

import static org.example.carrental.support.Fixtures.SEDAN
import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.VAN
import static org.example.carrental.support.Fixtures.period

class FleetAvailabilitySpec extends Specification {

    static availability(carType, int fleetSize, int available) {
        Availability.of(carType, period(0, 3), fleetSize, available)
    }

    def "reports the car types in a stable order whatever order they arrive in"() {
        when:
        def fleet = FleetAvailability.of(period(0, 3),
                [availability(VAN, 2, 2), availability(SUV, 3, 2), availability(SEDAN, 5, 0)])

        then: "so that two identical questions always come back identically"
        fleet.byCarType()*.carType() == [SEDAN, SUV, VAN]
    }

    def "keeps every type it was given, including ones with nothing free"() {
        when:
        def fleet = FleetAvailability.of(period(0, 3),
                [availability(SEDAN, 5, 0), availability(SUV, 3, 2), availability(VAN, 0, 0)])

        then:
        fleet.byCarType().size() == 3
        fleet.byCarType()*.availableCount() == [0, 2, 0]
    }

    def "carries the period it was asked about"() {
        expect:
        FleetAvailability.of(period(0, 3), []).period() == period(0, 3)
    }

    def "rejects a missing period or missing entries"() {
        when:
        FleetAvailability.of(rentalPeriod, entries)

        then:
        thrown(IllegalArgumentException)

        where:
        rentalPeriod | entries
        null         | []
        period(0, 1) | null
    }

    def "an availability cannot offer more cars than the fleet holds"() {
        when:
        Availability.of(SUV, period(0, 1), 2, 3)

        then:
        thrown(IllegalArgumentException)
    }
}
