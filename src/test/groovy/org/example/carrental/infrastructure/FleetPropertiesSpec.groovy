package org.example.carrental.infrastructure

import org.example.carrental.infrastructure.config.FleetProperties
import spock.lang.Specification
import spock.lang.Subject

import static org.example.carrental.support.Fixtures.SEDAN
import static org.example.carrental.support.Fixtures.SUV

class FleetPropertiesSpec extends Specification {

    @Subject
    def properties = new FleetProperties()

    def "turns the configured keys into car types"() {
        given:
        properties.counts = ['SEDAN': 5, 'SUV': 3]

        expect:
        properties.toCountsByType() == [(SEDAN): 5, (SUV): 3]
    }

    def "accepts keys in any case, as yaml is written by hand"() {
        given:
        properties.counts = ['sedan': 5, 'Suv': 3]

        expect:
        properties.toCountsByType() == [(SEDAN): 5, (SUV): 3]
    }

    def "fails fast and names the offending key when a type is malformed"() {
        given:
        properties.counts = ['SEDAN': 5, 'off road!': 1]

        when:
        properties.toCountsByType()

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('off road!')
        failure.message.contains('car-rental.fleet.counts')
    }

    def "fails fast when two keys normalise to the same type"() {
        given:
        properties.counts = ['SUV': 3, 'suv': 5]

        when:
        properties.toCountsByType()

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('SUV')
    }
}
