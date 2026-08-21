package org.example.carrental.application

import spock.lang.Specification

import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.period

class AvailabilityQuerySpec extends Specification {

    def "a query without a car type covers the whole fleet"() {
        when:
        def query = AvailabilityQuery.forAllTypes(period(0, 3))

        then:
        query.carType().empty
        query.period() == period(0, 3)
    }

    def "a query with a car type is narrowed to it"() {
        when:
        def query = AvailabilityQuery.forType(SUV, period(0, 3))

        then:
        query.carType().get() == SUV
    }

    def "the lenient factory treats a missing type as all of them"() {
        expect:
        AvailabilityQuery.of(null, period(0, 1)).carType().empty
        AvailabilityQuery.of(SUV, period(0, 1)).carType().get() == SUV
    }

    def "always needs a period"() {
        when:
        AvailabilityQuery.forAllTypes(null)

        then:
        thrown(IllegalArgumentException)
    }
}
