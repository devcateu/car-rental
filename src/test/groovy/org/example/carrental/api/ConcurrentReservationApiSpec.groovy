package org.example.carrental.api

import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.example.carrental.api.dto.ReserveCarRequest
import org.example.carrental.domain.CarType
import org.example.carrental.infrastructure.InMemoryAvailabilityStore
import org.example.carrental.infrastructure.InMemoryReservationRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import spock.lang.Specification

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

import static org.example.carrental.support.Fixtures.SEDAN
import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.VAN

/**
 * The requirement that actually bites: reading the availability rows, assessing capacity, and
 * writing them back has to be one atomic step. Without it, every one of these threads reads "a
 * car of that type is spare" before any of them writes, and the fleet is oversold. Here that
 * atomicity comes from the version on each row - remove the check and this spec fails.
 * <p>
 * The count of successes is pinned; the shape of the failures is not. A request that loses its
 * races until the retry budget runs out is answered 503 rather than 409, and how often that
 * happens is a matter of scheduling.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = [
        'car-rental.fleet.counts.SEDAN=3',
        'car-rental.fleet.counts.SUV=1',
        'car-rental.fleet.counts.VAN=1'
])
class ConcurrentReservationApiSpec extends Specification {

    static final LocalDateTime MONDAY_10AM = LocalDateTime.of(2026, 9, 7, 10, 0)
    static final int SEDANS_IN_FLEET = 3

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

    def "a fleet of #SEDANS_IN_FLEET sedans is never oversold, no matter how many requests arrive at once"() {
        given:
        def requests = 40

        when:
        def statuses = fireAtOnce(requests) { index ->
            reserve(SEDAN, MONDAY_10AM, 2)
        }

        then: "exactly as many bookings as there are cars - never one more"
        statuses.count { it == HttpStatus.CREATED } == SEDANS_IN_FLEET

        and: "and the fleet holds exactly that many bookings for the window"
        repository.findAll().size() == SEDANS_IN_FLEET

        and: "everyone else is told the fleet is full, or that they lost too many races"
        statuses.every { it in [HttpStatus.CREATED, HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE] }
    }

    def "capacity is never oversold when overlapping windows are requested concurrently"() {
        given: "every request overlaps every other one on a single SUV"
        def requests = 25

        when:
        def statuses = fireAtOnce(requests) { index ->
            reserve(SUV, MONDAY_10AM.plusHours(index % 5), 3)
        }

        then:
        statuses.count { it == HttpStatus.CREATED } == 1
        repository.findAll().size() == 1
        statuses.every { it in [HttpStatus.CREATED, HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE] }
    }

    def "requests for different car types do not block each other"() {
        given:
        def types = [SEDAN, SUV, VAN]

        when: "one request per type, all at the same instant"
        def statuses = fireAtOnce(types.size()) { index ->
            reserve(types[index], MONDAY_10AM, 1)
        }

        then: "each type has its own car, so all three succeed"
        statuses.every { it == HttpStatus.CREATED }
        repository.findAll()*.carType().toSet() == types.toSet()
    }

    def "non overlapping windows on the same car all succeed under concurrency"() {
        given: "one SUV, and requests for consecutive weeks that cannot clash"
        def requests = 10

        when:
        def statuses = fireAtOnce(requests) { index ->
            reserve(SUV, MONDAY_10AM.plusDays(index * 7L), 2)
        }

        then:
        statuses.every { it == HttpStatus.CREATED }
        repository.findAll().size() == requests
    }

    private List<HttpStatus> fireAtOnce(int count, Closure<HttpStatus> request) {
        def executor = Executors.newFixedThreadPool(count)
        def startLine = new CountDownLatch(1)
        try {
            def futures = (0..<count).collect { index ->
                executor.submit({
                    startLine.await()
                    request.call(index)
                } as java.util.concurrent.Callable<HttpStatus>)
            }
            startLine.countDown()
            return futures.collect { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private HttpStatus reserve(CarType carType, LocalDateTime start, int days) {
        restTemplate.postForEntity('/api/reservations',
                new ReserveCarRequest(carType, start, days), String).statusCode
    }
}
