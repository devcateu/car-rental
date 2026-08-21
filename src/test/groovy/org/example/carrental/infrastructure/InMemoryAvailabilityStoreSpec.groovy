package org.example.carrental.infrastructure

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.example.carrental.domain.Fleet
import org.example.carrental.domain.ReservationId
import org.example.carrental.domain.availability.CommitOutcome
import spock.lang.Specification
import spock.lang.Subject

import static org.example.carrental.support.Fixtures.SEDAN
import static org.example.carrental.support.Fixtures.SUV
import static org.example.carrental.support.Fixtures.VAN
import static org.example.carrental.support.Fixtures.day
import static org.example.carrental.support.Fixtures.occupancy
import static org.example.carrental.support.Fixtures.span

class InMemoryAvailabilityStoreSpec extends Specification {

    def fleet = Fleet.ofCounts([(SEDAN): 5, (SUV): 2, (VAN): 0])

    @Subject
    def store = new InMemoryAvailabilityStore(fleet)

    def "creates a row on demand, seeded with the configured fleet size"() {
        when:
        def rows = store.read(SUV, [day(0), day(1)])

        then:
        rows*.date() == [day(0), day(1)]
        rows.every { it.total() == 2 && it.busy().empty && it.version() == 0 }
    }

    def "a type configured with no cars still gets rows, with nothing in them"() {
        expect:
        store.read(VAN, [day(0)]).first().total() == 0
    }

    def "reading twice gives the same rows at the same version"() {
        expect:
        store.read(SUV, [day(0)]) == store.read(SUV, [day(0)])
    }

    def "a committed row comes back at the next version"() {
        given:
        def row = store.read(SUV, [day(0)]).first()

        when:
        def outcome = store.commit([row.with(occupancy(span(0, 8, 11)))])

        then:
        outcome == CommitOutcome.COMMITTED

        and:
        def stored = store.read(SUV, [day(0)]).first()
        stored.version() == 1
        stored.busy().size() == 1
    }

    def "refuses a write built on a row that has moved"() {
        given: "two readers see version 0"
        def mine = store.read(SUV, [day(0)]).first()
        def theirs = store.read(SUV, [day(0)]).first()

        when: "the other writer gets there first"
        store.commit([theirs.with(occupancy(span(0, 8, 11)))])

        then: "my write is refused rather than clobbering theirs"
        store.commit([mine.with(occupancy(span(0, 14, 16)))]) == CommitOutcome.CONFLICT

        and: "and the store still holds only their claim"
        store.read(SUV, [day(0)]).first().busy().size() == 1
    }

    def "a multi day write is all or nothing"() {
        given: "three days read together"
        def rows = store.read(SUV, [day(0), day(1), day(2)])

        and: "the middle one moves on underneath"
        store.commit([rows[1].with(occupancy(span(1, 8, 11)))])

        when:
        def id = ReservationId.generate()
        def outcome = store.commit([rows[0].with(occupancy(span(0, 8, 11), id)),
                                    rows[1].with(occupancy(span(1, 12, 14), id)),
                                    rows[2].with(occupancy(span(2, 8, 11), id))])

        then:
        outcome == CommitOutcome.CONFLICT

        and: "not one of the three days was touched by the refused write"
        store.read(SUV, [day(0), day(1), day(2)]).every { !it.holds(id) }
    }

    def "under concurrency exactly one writer wins each version"() {
        given: "twenty writers all reading version 0 of the same day"
        def writers = 20
        def row = store.read(SUV, [day(0)]).first()
        def committed = new AtomicInteger()
        def executor = Executors.newFixedThreadPool(writers)
        def startLine = new CountDownLatch(1)

        when:
        def futures = (0..<writers).collect { index ->
            executor.submit({
                startLine.await()
                if (store.commit([row.with(occupancy(span(0, 8, 11)))]) == CommitOutcome.COMMITTED) {
                    committed.incrementAndGet()
                }
            } as Runnable)
        }
        startLine.countDown()
        futures.each { it.get(30, TimeUnit.SECONDS) }
        executor.shutdownNow()

        then: "the version is what serialises them - nineteen are told to try again"
        committed.get() == 1
        store.read(SUV, [day(0)]).first().version() == 1
    }

    def "rejects a read with no dates"() {
        when:
        store.read(SUV, dates)

        then:
        thrown(IllegalArgumentException)

        where:
        dates << [null, []]
    }

    def "clearing it forgets every row, so a test starts from a clean fleet"() {
        given: "a day that has been booked into and committed"
        def row = store.read(SUV, [day(0)]).first()
        store.commit([row.with(occupancy(span(0, 8, 11)))])

        when:
        store.clear()

        then: "the day comes back empty, and at version zero"
        def afterwards = store.read(SUV, [day(0)]).first()
        afterwards.busy().empty
        afterwards.version() == 0
    }
}
