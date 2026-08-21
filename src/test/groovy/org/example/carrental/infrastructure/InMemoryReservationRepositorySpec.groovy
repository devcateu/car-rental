package org.example.carrental.infrastructure

import org.example.carrental.domain.ReservationId
import spock.lang.Specification
import spock.lang.Subject

import static org.example.carrental.support.Fixtures.SEDAN
import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.VAN
import static org.example.carrental.support.Fixtures.booking
import static org.example.carrental.support.Fixtures.period

class InMemoryReservationRepositorySpec extends Specification {

    @Subject
    def repository = new InMemoryReservationRepository()

    def "stores a reservation and finds it back by id"() {
        given:
        def reservation = booking(SUV, 0, 2)

        when:
        repository.save(reservation)

        then:
        repository.findById(reservation.id()).get() == reservation
        repository.size() == 1
    }

    def "returns nothing for an id that was never issued"() {
        expect:
        repository.findById(ReservationId.generate()).empty
    }

    def "indexes reservations by car type so a query reads only what it needs"() {
        given:
        def suvBooking = booking(SUV, 0, 2)
        def sedanBooking = booking(SEDAN, 0, 2)

        when:
        repository.save(suvBooking)
        repository.save(sedanBooking)

        then:
        repository.findOverlapping(SUV, period(0, 2)) == [suvBooking]
        repository.findOverlapping(SEDAN, period(0, 2)) == [sedanBooking]
        repository.findOverlapping(VAN, period(0, 2)).empty
        repository.size() == 2
    }

    def "returns only the reservations whose period overlaps the window"() {
        given: "bookings on days 0-1, 5-6 and 10-11"
        def early = booking(SUV, 0, 1)
        def middle = booking(SUV, 5, 1)
        def late = booking(SUV, 10, 1)
        [early, middle, late].each { repository.save(it) }

        expect: "narrowing by period is the repository's job, not the caller's"
        repository.findOverlapping(SUV, period(4, 3)) == [middle]
        repository.findOverlapping(SUV, period(0, 11)) == [early, middle, late]
        repository.findOverlapping(SUV, period(2, 2)).empty
    }

    def "treats a window that merely touches a booking as no overlap"() {
        given:
        repository.save(booking(SUV, 0, 2))

        expect: "the car is handed back exactly as the window opens"
        repository.findOverlapping(SUV, period(2, 1)).empty
        repository.findOverlapping(SUV, period(1, 1)).size() == 1
    }

    def "rejects a query missing its type or its period"() {
        when:
        repository.findOverlapping(carType, rentalPeriod)

        then:
        thrown(IllegalArgumentException)

        where:
        carType | rentalPeriod
        null    | period(0, 1)
        SUV     | null
    }

    def "refuses to overwrite an existing reservation"() {
        given:
        def reservation = booking(SUV, 0, 2)
        repository.save(reservation)

        when:
        repository.save(reservation)

        then:
        thrown(IllegalStateException)
    }

    def "hands out defensive copies"() {
        given:
        repository.save(booking(SUV, 0, 2))

        when:
        repository.findOverlapping(SUV, period(0, 2)).add(booking(SUV, 5, 1))

        then:
        thrown(UnsupportedOperationException)
        repository.findOverlapping(SUV, period(0, 2)).size() == 1
    }

    def "survives concurrent writes without losing a reservation"() {
        given:
        def writers = 50

        when:
        def threads = (1..writers).collect { index ->
            Thread.start { repository.save(booking(SUV, index, 1)) }
        }
        threads*.join()

        then:
        repository.size() == writers
        repository.findOverlapping(SUV, period(0, writers + 1)).size() == writers
    }
}
