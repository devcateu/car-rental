package org.example.carrental.application

import java.time.Duration

import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.example.carrental.domain.CarType
import org.example.carrental.domain.Fleet
import org.example.carrental.domain.Reservation
import org.example.carrental.domain.ReservationId
import org.example.carrental.domain.ReservationRepository
import org.example.carrental.domain.availability.AvailabilityDay
import org.example.carrental.domain.availability.AvailabilityStore
import org.example.carrental.domain.availability.CapacityCalculator
import org.example.carrental.domain.availability.CapacityFailureReason
import org.example.carrental.domain.availability.CommitOutcome
import org.example.carrental.domain.exception.NoCarAvailableException
import org.example.carrental.domain.exception.ReservationNotFoundException
import org.example.carrental.domain.exception.UnknownCarTypeException
import spock.lang.Specification
import spock.lang.Subject

import static org.example.carrental.support.Fixtures.SEDAN
import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.VAN
import static org.example.carrental.support.Fixtures.availabilityDay
import static org.example.carrental.support.Fixtures.booking
import static org.example.carrental.support.Fixtures.period
import static org.example.carrental.support.Fixtures.span

class ReservationServiceSpec extends Specification {

    static final int ATTEMPTS = 3

    /** The real retry mechanism, configured with the shortest wait the library allows. */
    static final Retry RETRY = Retry.of('test-booking', RetryConfig.custom()
            .maxAttempts(ATTEMPTS)
            .intervalFunction(IntervalFunction.of(Duration.ofMillis(1)))
            .retryOnResult { result -> result instanceof Optional && result.empty }
            .retryOnException { throwable -> false }
            .build())

    def repository = Mock(ReservationRepository)
    def store = Mock(AvailabilityStore)
    def fleet = Fleet.ofCounts([(SEDAN): 1, (SUV): 2, (VAN): 0])

    @Subject
    def service = new ReservationService(repository, store, new CapacityCalculator(), fleet, RETRY)

    def "books a car type and stores the reservation"() {
        given:
        def command = ReserveCarCommand.of(SUV, period(0, 1))
        store.read(SUV, _) >> twoEmptyDays(SUV, 2)
        store.commit(_) >> CommitOutcome.COMMITTED

        when:
        def reservation = service.reserve(command)

        then:
        1 * repository.save(_ as Reservation) >> { Reservation saved -> saved }
        reservation.carType() == SUV
        reservation.period() == period(0, 1)
    }

    def "writes the claim into every day the rental touches"() {
        given:
        def command = ReserveCarCommand.of(SUV, period(0, 1))
        store.read(SUV, _) >> twoEmptyDays(SUV, 2)
        repository.save(_) >> { Reservation saved -> saved }

        when:
        service.reserve(command)

        then: "a rental from 10:00 for one day lands on two calendar days"
        1 * store.commit({ List<AvailabilityDay> days -> days.size() == 2 && days.every { it.busy().size() == 1 } }) >>
                CommitOutcome.COMMITTED
    }

    def "refuses the booking and writes nothing when a day of the window is full"() {
        given:
        def command = ReserveCarCommand.of(SUV, period(0, 1))
        store.read(SUV, _) >> [availabilityDay(SUV, 0, 2, [span(0, 0, 24), span(0, 0, 24)]),
                               availabilityDay(SUV, 1, 2)]

        when:
        service.reserve(command)

        then:
        def failure = thrown(NoCarAvailableException)
        failure.reason() == CapacityFailureReason.ALL_CARS_BOOKED
        failure.carType() == SUV
        0 * store.commit(_)
        0 * repository.save(_)
    }

    def "refuses the booking when the type is configured with no cars"() {
        given:
        def command = ReserveCarCommand.of(VAN, period(0, 1))
        store.read(VAN, _) >> twoEmptyDays(VAN, 0)

        when:
        service.reserve(command)

        then:
        def failure = thrown(NoCarAvailableException)
        failure.reason() == CapacityFailureReason.NO_CARS_OF_TYPE
        0 * store.commit(_)
        0 * repository.save(_)
    }

    def "refuses a type the business does not offer, without touching storage"() {
        when:
        service.reserve(ReserveCarCommand.of(CarType.of('LORRY'), period(0, 1)))

        then:
        thrown(UnknownCarTypeException)
        0 * store.read(_, _)
        0 * repository.save(_)
    }

    def "retries when the rows moved underneath it"() {
        given:
        def command = ReserveCarCommand.of(SUV, period(0, 1))
        store.read(SUV, _) >> twoEmptyDays(SUV, 2)
        repository.save(_) >> { Reservation saved -> saved }

        when:
        def reservation = service.reserve(command)

        then: "the first write loses the race, the second wins"
        2 * store.commit(_) >>> [CommitOutcome.CONFLICT, CommitOutcome.COMMITTED]
        reservation != null
    }

    def "gives up when the attempts run out, and says the request is worth retrying"() {
        given: "every write loses - the fate of a long rental under sustained load"
        def command = ReserveCarCommand.of(SUV, period(0, 1))
        store.read(SUV, _) >> twoEmptyDays(SUV, 2)

        when:
        service.reserve(command)

        then:
        ATTEMPTS * store.commit(_) >> CommitOutcome.CONFLICT
        0 * repository.save(_)

        and:
        def failure = thrown(BookingContentionException)
        failure.carType() == SUV
        failure.attempts() == ATTEMPTS
    }

    def "a full fleet is answered at once, without spending the retry budget"() {
        given:
        def command = ReserveCarCommand.of(SUV, period(0, 1))

        when:
        service.reserve(command)

        then: "one read, one refusal - retrying a capacity answer would only delay it"
        1 * store.read(SUV, _) >> [availabilityDay(SUV, 0, 2, [span(0, 0, 24), span(0, 0, 24)]),
                                   availabilityDay(SUV, 1, 2)]
        thrown(NoCarAvailableException)
    }

    def "replays the same reservation id on every attempt, so a retry cannot double book"() {
        given:
        def seen = []
        store.read(SUV, _) >> twoEmptyDays(SUV, 2)
        store.commit(_) >> { arguments ->
            List<AvailabilityDay> days = arguments[0]
            seen << days.first().busy().first().reservationId()
            seen.size() < ATTEMPTS ? CommitOutcome.CONFLICT : CommitOutcome.COMMITTED
        }
        repository.save(_) >> { Reservation saved -> saved }

        when:
        def reservation = service.reserve(ReserveCarCommand.of(SUV, period(0, 1)))

        then: "every attempt carries one identity - the write is idempotent under replay"
        seen.size() == ATTEMPTS
        seen.toSet().size() == 1
        reservation.id() == seen.first()
    }

    def "reports availability for one type without booking anything"() {
        given:
        store.read(SUV, _) >> [availabilityDay(SUV, 0, 2, [span(0, 0, 24)]), availabilityDay(SUV, 1, 2)]

        when:
        def availability = entryFor(service.checkAvailability(AvailabilityQuery.forType(SUV, period(0, 1))), SUV)

        then:
        availability.fleetSize() == 2
        availability.availableCount() == 1
        0 * store.commit(_)
        0 * repository.save(_)
    }

    def "availability is the tightest day of the window, not the average"() {
        given: "the fleet is free on the first day and full on the second"
        store.read(SUV, _) >> [availabilityDay(SUV, 0, 2),
                               availabilityDay(SUV, 1, 2, [span(1, 0, 24), span(1, 0, 24)])]

        when:
        def availability = entryFor(service.checkAvailability(AvailabilityQuery.forType(SUV, period(0, 1))), SUV)

        then:
        availability.availableCount() == 0
    }

    def "reports every configured type when the query names none"() {
        given:
        store.read(SEDAN, _) >> twoEmptyDays(SEDAN, 1)
        store.read(SUV, _) >> twoEmptyDays(SUV, 2)
        store.read(VAN, _) >> twoEmptyDays(VAN, 0)

        when:
        def fleetAvailability = service.checkAvailability(AvailabilityQuery.forAllTypes(period(0, 1)))

        then: "including VAN, which is configured with no cars"
        fleetAvailability.byCarType()*.carType() == [SEDAN, SUV, VAN]
        entryFor(fleetAvailability, SEDAN).availableCount() == 1
        entryFor(fleetAvailability, SUV).availableCount() == 2
        entryFor(fleetAvailability, VAN).availableCount() == 0
    }

    def "asks storage only about the type it was asked about"() {
        when:
        service.checkAvailability(AvailabilityQuery.forType(SUV, period(0, 1)))

        then:
        1 * store.read(SUV, _) >> twoEmptyDays(SUV, 2)
        0 * store.read(SEDAN, _)
        0 * store.read(VAN, _)
    }

    def "refuses an availability query for a type the business does not offer"() {
        when:
        service.checkAvailability(AvailabilityQuery.forType(CarType.of('LORRY'), period(0, 1)))

        then:
        thrown(UnknownCarTypeException)
        0 * store.read(_, _)
    }

    def "fails loudly when a reservation id is unknown"() {
        given:
        def id = ReservationId.generate()
        repository.findById(id) >> Optional.empty()

        when:
        service.findById(id)

        then:
        def failure = thrown(ReservationNotFoundException)
        failure.id() == id
    }

    def "returns a stored reservation by id"() {
        given:
        def reservation = booking(SUV, 0, 2)
        repository.findById(reservation.id()) >> Optional.of(reservation)

        expect:
        service.findById(reservation.id()) == reservation
    }

    private static twoEmptyDays(CarType carType, int total) {
        [availabilityDay(carType, 0, total), availabilityDay(carType, 1, total)]
    }

    private static entryFor(fleetAvailability, carType) {
        fleetAvailability.byCarType().find { it.carType() == carType }
    }
}
