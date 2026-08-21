package org.example.carrental.api

import java.time.LocalDateTime

import org.example.carrental.api.dto.AvailabilityResponse
import org.example.carrental.api.dto.ErrorResponse
import org.example.carrental.api.dto.ReservationResponse
import org.example.carrental.api.dto.ReserveCarRequest
import org.example.carrental.domain.CarType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import spock.lang.Specification

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

/**
 * The proof that car types really are configuration: this context is started with a fleet the
 * code has never heard of, and the whole API works against it. Nothing in the domain, the
 * allocator or the controllers mentions SEDAN, SUV or VAN.
 * <p>
 * It loads {@code limousine-fleet.yml} instead of {@code application.yml}. Overriding
 * {@code car-rental.fleet.counts.*} through {@code @SpringBootTest(properties = ...)} would not
 * do: Spring merges map properties across sources, so the usual types would still be there.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = 'spring.config.name=limousine-fleet')
class ConfiguredCarTypesApiSpec extends Specification {

    static final LocalDateTime MONDAY_10AM = LocalDateTime.of(2026, 9, 7, 10, 0)
    static final CarType LIMOUSINE = CarType.of('LIMOUSINE')
    static final CarType CARGO_BIKE = CarType.of('CARGO_BIKE')

    @Autowired
    TestRestTemplate restTemplate

    def "reserves a type that exists only in the configuration"() {
        when:
        def response = restTemplate.postForEntity('/api/reservations',
                new ReserveCarRequest(LIMOUSINE, MONDAY_10AM, 2), ReservationResponse)

        then:
        response.statusCode == HttpStatus.CREATED
        response.body.carType == LIMOUSINE
    }

    def "reports availability for a configured type"() {
        when:
        def response = restTemplate.getForEntity(
                "/api/availability?carType=CARGO_BIKE&startDateTime=${MONDAY_10AM}&days=1",
                AvailabilityResponse)

        then:
        response.statusCode == HttpStatus.OK
        response.body.availability.size() == 1
        response.body.availability.first().carType == CARGO_BIKE
        response.body.availability.first().fleetSize == 2
        response.body.availability.first().availableCount == 2
    }

    def "the whole fleet answer lists only the configured types"() {
        when:
        def response = restTemplate.getForEntity(
                "/api/availability?startDateTime=${MONDAY_10AM}&days=1", AvailabilityResponse)

        then:
        response.statusCode == HttpStatus.OK
        response.body.availability*.carType == [CARGO_BIKE, LIMOUSINE]
    }

    def "the types this project usually configures mean nothing here"() {
        when:
        def response = restTemplate.postForEntity('/api/reservations',
                [carType: 'SUV', startDateTime: '2026-09-07T10:00:00', days: 1],
                ErrorResponse)

        then:
        response.statusCode == HttpStatus.BAD_REQUEST
        response.body.code == 'UNKNOWN_CAR_TYPE'
        response.body.message.contains('LIMOUSINE')
    }
}
