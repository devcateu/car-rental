package org.example.carrental.domain

import spock.lang.Specification

class CarTypeSpec extends Specification {

    def "normalises '#raw' to #expected"() {
        expect:
        CarType.of(raw).name() == expected

        where:
        raw        || expected
        'SUV'      || 'SUV'
        'suv'      || 'SUV'
        'Suv'      || 'SUV'
        '  SUV  '  || 'SUV'
        'PICKUP_4X4' || 'PICKUP_4X4'
        'MINIBUS9'   || 'MINIBUS9'
    }

    def "types that differ only in case are the same type"() {
        expect:
        CarType.of('suv') == CarType.of('SUV')
        CarType.of('suv').hashCode() == CarType.of('SUV').hashCode()
        CarType.of('suv') != CarType.of('van')
    }

    def "rejects #scenario"() {
        when:
        CarType.of(raw)

        then:
        thrown(IllegalArgumentException)

        where:
        scenario                  | raw
        'null'                    | null
        'an empty name'           | ''
        'a blank name'            | '   '
        'a name with a space'     | 'OFF ROAD'
        'a name with punctuation' | 'off-road!'
        'a hyphen, which would make a car id ambiguous' | 'OFF-ROAD'
    }

    def "orders alphabetically so fleets and allocations are reproducible"() {
        expect:
        [CarType.of('VAN'), CarType.of('SEDAN'), CarType.of('SUV')].sort(false)*.name() ==
                ['SEDAN', 'SUV', 'VAN']
    }

    def "reads as its plain name"() {
        expect:
        CarType.of('SUV').toString() == 'SUV'
    }

    def "any name the business invents is a valid type - the domain has no fixed list"() {
        expect:
        CarType.of('LIMOUSINE').name() == 'LIMOUSINE'
        CarType.of('CARGO_BIKE').name() == 'CARGO_BIKE'
    }
}
