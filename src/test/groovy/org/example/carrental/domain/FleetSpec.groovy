package org.example.carrental.domain

import spock.lang.Specification

import static org.example.carrental.support.Fixtures.SEDAN
import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.VAN

class FleetSpec extends Specification {

    def "reports how many cars of each type are in service"() {
        given:
        def fleet = Fleet.ofCounts([(SEDAN): 2, (SUV): 1, (VAN): 0])

        expect:
        fleet.sizeOf(SEDAN) == 2
        fleet.sizeOf(SUV) == 1
        fleet.sizeOf(VAN) == 0
    }

    def "the configuration decides which types exist - there is no fixed list in the code"() {
        given: "a business that rents out something else entirely"
        def fleet = Fleet.ofCounts([(CarType.of('LIMOUSINE')): 2, (CarType.of('CARGO_BIKE')): 4])

        expect:
        fleet.knownTypes()*.name() as Set == ['LIMOUSINE', 'CARGO_BIKE'] as Set
        fleet.sizeOf(CarType.of('CARGO_BIKE')) == 4

        and: "and the types this project happens to use are not known to it"
        !fleet.knows(SUV)
        fleet.sizeOf(SUV) == 0
    }

    def "a type configured with no cars is known but empty"() {
        given:
        def fleet = Fleet.ofCounts([(SEDAN): 1, (VAN): 0])

        expect: "the distinction that separates a 400 from a 409 at the API"
        fleet.knows(VAN)
        fleet.sizeOf(VAN) == 0

        and:
        !fleet.knows(SUV)
        fleet.sizeOf(SUV) == 0
    }

    def "rejects a negative or missing number of cars"() {
        when:
        Fleet.ofCounts([(SUV): count])

        then:
        thrown(IllegalArgumentException)

        where:
        count << [-1, null]
    }

    def "rejects a fleet with no types at all"() {
        when:
        Fleet.ofCounts(counts)

        then:
        thrown(IllegalArgumentException)

        where:
        counts << [[:], null]
    }

    def "reports its types in a stable order, whatever order they were configured in"() {
        expect: "so that a whole-fleet availability answer is reproducible"
        Fleet.ofCounts([(VAN): 1, (SUV): 1, (SEDAN): 1]).knownTypes() as List == [SEDAN, SUV, VAN]
    }
}
