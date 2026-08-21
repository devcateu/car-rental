package org.example.carrental.api

import java.time.LocalDateTime

import org.example.carrental.api.dto.AvailabilityResponse
import org.example.carrental.api.dto.ErrorResponse
import org.example.carrental.api.dto.ReservationResponse
import org.example.carrental.api.dto.ReserveCarRequest
import org.example.carrental.domain.CarType
import org.example.carrental.infrastructure.InMemoryAvailabilityStore
import org.example.carrental.infrastructure.InMemoryReservationRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import spock.lang.Specification

import static org.example.carrental.support.Fixtures.SEDAN
import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.VAN
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

/**
 * End to end over real HTTP against a deliberately tiny fleet: 2 sedans, 1 SUV, no vans.
 * The van count of zero is not padding - it is the only way to exercise the difference
 * between "we never had one" and "they are all out".
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = [
        'car-rental.fleet.counts.SEDAN=2',
        'car-rental.fleet.counts.SUV=1',
        'car-rental.fleet.counts.VAN=0'
])
class ReservationApiSpec extends Specification {

    static final LocalDateTime MONDAY_10AM = LocalDateTime.of(2026, 9, 7, 10, 0)

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    InMemoryReservationRepository repository

    @Autowired
    InMemoryAvailabilityStore availabilityStore

    def cleanup() {
        repository.clear()
        availabilityStore.clear()
    }

    def "reserves a car type and returns the booking"() {
        when:
        def response = reserve(SUV, MONDAY_10AM, 3)

        then:
        response.statusCode == HttpStatus.CREATED
        response.headers.getFirst('Location') == "/api/reservations/${response.body.reservationId}"

        and: "the booking names the type and works out the return time"
        response.body.carType == SUV
        response.body.startDateTime == MONDAY_10AM
        response.body.endDateTime == MONDAY_10AM.plusDays(3)
        response.body.days == 3
    }

    def "the confirmation names neither a physical car nor a customer"() {
        when: "the request carries only what the requirement asks for"
        def response = restTemplate.postForEntity('/api/reservations',
                new ReserveCarRequest(SUV, MONDAY_10AM, 1), String)

        then: "a car id would promise something the business has not committed to"
        response.statusCode == HttpStatus.CREATED
        !response.body.contains('carId')
        response.body.contains('"carType":"SUV"')

        and: "and nothing records who booked - the reservation id is the caller's handle"
        !response.body.contains('customerReference')
        response.body.contains('reservationId')
    }

    def "a request carrying only a type, a start and a number of days is enough"() {
        when:
        def response = restTemplate.postForEntity('/api/reservations',
                [carType: 'SUV', startDateTime: '2026-09-07T10:00:00', days: 2], ReservationResponse)

        then:
        response.statusCode == HttpStatus.CREATED
        response.body.carType == SUV
        response.body.days == 2
    }

    def "reads a reservation back by its id"() {
        given:
        def created = reserve(SEDAN, MONDAY_10AM, 1).body

        when:
        def response = restTemplate.getForEntity(
                "/api/reservations/${created.reservationId}", ReservationResponse)

        then:
        response.statusCode == HttpStatus.OK
        response.body.reservationId == created.reservationId
        response.body.carType == SEDAN
    }

    def "takes a second overlapping booking while a car of the type is spare"() {
        given:
        reserve(SEDAN, MONDAY_10AM, 3)

        when:
        def second = reserve(SEDAN, MONDAY_10AM.plusDays(1), 1)

        then:
        second.statusCode == HttpStatus.CREATED
    }

    def "refuses the booking with 409 once every car of the type is out"() {
        given: "the single SUV is booked for the week"
        reserve(SUV, MONDAY_10AM, 7)

        when:
        def response = reserveExpectingError(SUV, MONDAY_10AM.plusDays(2), 1)

        then:
        response.statusCode == HttpStatus.CONFLICT
        response.body.code == 'ALL_CARS_BOOKED'
        response.body.message.contains('All cars of type SUV')
        response.body.message.contains('fully booked on')
    }

    def "frees the capacity again as soon as the rental ends"() {
        given: "the only SUV is out from Monday for two days"
        reserve(SUV, MONDAY_10AM, 2)

        when: "someone asks for it from the moment it is due back"
        def second = reserve(SUV, MONDAY_10AM.plusDays(2), 1)

        then:
        second.statusCode == HttpStatus.CREATED
    }

    def "many non overlapping bookings never exhaust a single car"() {
        expect: "ten consecutive one day rentals all fit on the one SUV"
        (0..<10).every { reserve(SUV, MONDAY_10AM.plusDays(it), 1).statusCode == HttpStatus.CREATED }

        and: "and an eleventh across the whole fortnight does not, since every day is taken"
        reserveExpectingError(SUV, MONDAY_10AM, 10).statusCode == HttpStatus.CONFLICT
    }

    def "the whole capacity of a type can be booked, and only then does it refuse"() {
        expect:
        reserve(SEDAN, MONDAY_10AM, 2).statusCode == HttpStatus.CREATED
        reserve(SEDAN, MONDAY_10AM, 2).statusCode == HttpStatus.CREATED
        reserveExpectingError(SEDAN, MONDAY_10AM, 2).statusCode == HttpStatus.CONFLICT
    }

    def "reports availability, and the reservation that follows agrees with it"() {
        given:
        reserve(SEDAN, MONDAY_10AM, 2)

        when:
        def response = availability(SEDAN, MONDAY_10AM, 2)

        then:
        response.statusCode == HttpStatus.OK
        response.body.endDateTime == MONDAY_10AM.plusDays(2)
        response.body.days == 2

        and:
        forType(response).carType == SEDAN
        forType(response).fleetSize == 2
        forType(response).availableCount == 1

        and: "booking now takes the last one"
        reserve(SEDAN, MONDAY_10AM, 2).statusCode == HttpStatus.CREATED

        and: "and none is left"
        forType(availability(SEDAN, MONDAY_10AM, 2)).availableCount == 0
    }

    def "availability counts the busiest instant, not the bookings that overlap"() {
        given: "two sedans, and four one day rentals that never coincide"
        (0..<4).each { reserve(SEDAN, MONDAY_10AM.plusDays(it * 2), 1) }

        when:
        def response = availability(SEDAN, MONDAY_10AM, 8)

        then: "four bookings overlap the window, but only one car is ever in use at a time"
        forType(response).availableCount == 1
    }

    def "reports the full fleet as available for an untouched window"() {
        when:
        def response = availability(SEDAN, MONDAY_10AM.plusDays(30), 1)

        then:
        forType(response).fleetSize == 2
        forType(response).availableCount == 2
    }

    def "reports every car type when the query names none"() {
        given: "one of the two sedans and the only SUV are out"
        reserve(SEDAN, MONDAY_10AM, 2)
        reserve(SUV, MONDAY_10AM, 2)

        when: "asking only about the period"
        def response = availabilityForAllTypes(MONDAY_10AM, 2)

        then: "every configured type is reported, in a stable order"
        response.statusCode == HttpStatus.OK
        response.body.startDateTime == MONDAY_10AM
        response.body.endDateTime == MONDAY_10AM.plusDays(2)
        response.body.availability*.carType == [SEDAN, SUV, VAN]

        and:
        entry(response, SEDAN).with { it.fleetSize == 2 && it.availableCount == 1 }
        entry(response, SUV).with { it.fleetSize == 1 && it.availableCount == 0 }

        and: "including the type configured with no cars at all"
        entry(response, VAN).with { it.fleetSize == 0 && it.availableCount == 0 }
    }

    def "naming a car type narrows the list rather than changing the shape"() {
        when:
        def all = availabilityForAllTypes(MONDAY_10AM, 2)
        def justSuv = availability(SUV, MONDAY_10AM, 2)

        then:
        all.body.availability.size() == 3
        justSuv.body.availability.size() == 1
        justSuv.body.availability.first() == entry(all, SUV)

        and: "the window is reported identically either way"
        justSuv.body.startDateTime == all.body.startDateTime
        justSuv.body.endDateTime == all.body.endDateTime
    }

    def "the whole fleet answer tracks bookings as they are made"() {
        expect:
        entry(availabilityForAllTypes(MONDAY_10AM, 1), SEDAN).availableCount == 2

        when:
        reserve(SEDAN, MONDAY_10AM, 1)

        then:
        entry(availabilityForAllTypes(MONDAY_10AM, 1), SEDAN).availableCount == 1

        when:
        reserve(SEDAN, MONDAY_10AM, 1)

        then:
        entry(availabilityForAllTypes(MONDAY_10AM, 1), SEDAN).availableCount == 0
    }

    def "an availability query still needs a period"() {
        when:
        def response = restTemplate.getForEntity("/api/availability?carType=SUV", ErrorResponse)

        then:
        response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "rejects a request for #scenario with 400"() {
        when:
        def response = restTemplate.postForEntity('/api/reservations', body, ErrorResponse)

        then:
        response.statusCode == HttpStatus.BAD_REQUEST
        response.body.code in ['VALIDATION_FAILED', 'MALFORMED_REQUEST']

        where:
        scenario                   | body
        'zero days'                | [carType: 'SUV', startDateTime: '2026-09-07T10:00:00', days: 0]
        'a negative number of days'| [carType: 'SUV', startDateTime: '2026-09-07T10:00:00', days: -3]
        'a missing start date'     | [carType: 'SUV', days: 2]
        'a missing car type'       | [startDateTime: '2026-09-07T10:00:00', days: 2]
        'a blank car type'         | [carType: '', startDateTime: '2026-09-07T10:00:00', days: 2]
        'a malformed car type'     | [carType: 'off road!', startDateTime: '2026-09-07T10:00:00', days: 2]
        'an unparseable date'      | [carType: 'SUV', startDateTime: 'next monday', days: 2]
    }

    def "a car type the business does not offer is a bad request, not a capacity problem"() {
        when: "LORRY is nowhere in car-rental.fleet.counts"
        def response = restTemplate.postForEntity('/api/reservations',
                [carType: 'LORRY', startDateTime: '2026-09-07T10:00:00', days: 2],
                ErrorResponse)

        then: "400, and the answer says what is on offer"
        response.statusCode == HttpStatus.BAD_REQUEST
        response.body.code == 'UNKNOWN_CAR_TYPE'
        response.body.message.contains('SEDAN')
        response.body.message.contains('SUV')
        response.body.message.contains('VAN')
    }

    def "a configured type with no cars is a capacity problem, not a bad request"() {
        when: "VAN is configured, but with a count of zero"
        def response = reserveExpectingError(VAN, MONDAY_10AM, 1)

        then:
        response.statusCode == HttpStatus.CONFLICT
        response.body.code == 'NO_CARS_OF_TYPE'
    }

    def "car types are matched case insensitively"() {
        when:
        def response = restTemplate.postForEntity('/api/reservations',
                [carType: 'suv', startDateTime: '2026-09-07T10:00:00', days: 2],
                ReservationResponse)

        then:
        response.statusCode == HttpStatus.CREATED
        response.body.carType == SUV
    }

    def "availability rejects a type the business does not offer"() {
        when:
        def response = restTemplate.getForEntity(
                "/api/availability?carType=LORRY&startDateTime=${MONDAY_10AM}&days=1", ErrorResponse)

        then:
        response.statusCode == HttpStatus.BAD_REQUEST
        response.body.code == 'UNKNOWN_CAR_TYPE'
    }

    def "reports 404 for a reservation that does not exist"() {
        when:
        def response = restTemplate.getForEntity(
                "/api/reservations/${UUID.randomUUID()}", ErrorResponse)

        then:
        response.statusCode == HttpStatus.NOT_FOUND
        response.body.code == 'RESERVATION_NOT_FOUND'
    }

    def "reports 400 for a reservation id that is not an id at all"() {
        when:
        def response = restTemplate.getForEntity('/api/reservations/not-a-uuid', ErrorResponse)

        then:
        response.statusCode == HttpStatus.BAD_REQUEST
    }

    private reserve(CarType carType, LocalDateTime start, int days) {
        restTemplate.postForEntity('/api/reservations',
                new ReserveCarRequest(carType, start, days), ReservationResponse)
    }

    private reserveExpectingError(CarType carType, LocalDateTime start, int days) {
        restTemplate.postForEntity('/api/reservations',
                new ReserveCarRequest(carType, start, days), ErrorResponse)
    }

    private availability(CarType carType, LocalDateTime start, int days) {
        restTemplate.getForEntity(
                "/api/availability?carType=${carType}&startDateTime=${start}&days=${days}",
                AvailabilityResponse)
    }

    private availabilityForAllTypes(LocalDateTime start, int days) {
        restTemplate.getForEntity(
                "/api/availability?startDateTime=${start}&days=${days}", AvailabilityResponse)
    }

    private static entry(response, CarType carType) {
        response.body.availability.find { it.carType == carType }
    }

    /** The single entry of a response that was narrowed to one car type. */
    private static forType(response) {
        assert response.body.availability.size() == 1
        response.body.availability.first()
    }
}
