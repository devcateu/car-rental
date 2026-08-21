package org.example.carrental.domain.availability

import org.example.carrental.domain.TimeSpan
import spock.lang.Specification

import static org.example.carrental.support.Fixtures.day
import static org.example.carrental.support.Fixtures.span

/**
 * The sweep line. Everything here is "build a list of spans, assert a number", which is the
 * point of keeping the calculation a pure function.
 */
class PeakUsageSpec extends Specification {

    static final TimeSpan WHOLE_DAY = TimeSpan.wholeDay(day(0))

    def "an untouched window is idle"() {
        when:
        def usage = PeakUsage.within(WHOLE_DAY, [])

        then:
        usage.peak() == 0
        usage.firstReachedAt().empty
    }

    def "#scenario gives a peak of #expectedPeak"() {
        expect:
        PeakUsage.within(WHOLE_DAY, spans).peak() == expectedPeak

        where:
        scenario                                    | spans                                              || expectedPeak
        'one claim inside the window'               | [span(0, 9, 11)]                                   || 1
        'two claims that never coincide'            | [span(0, 6, 8), span(0, 14, 16)]                   || 1
        'four claims spread across the day'         | [span(0, 1, 2), span(0, 5, 6),
                                                       span(0, 9, 10), span(0, 20, 21)]                  || 1
        'two overlapping claims'                    | [span(0, 8, 14), span(0, 10, 16)]                  || 2
        'one claim nested inside another'           | [span(0, 6, 22), span(0, 10, 12)]                  || 2
        'three claims meeting at one instant'       | [span(0, 8, 16), span(0, 9, 17), span(0, 10, 18)]  || 3
        'a car handed straight over at 11:00'       | [span(0, 8, 11), span(0, 11, 14)]                  || 1
        'handover with an hour to spare'            | [span(0, 8, 11), span(0, 12, 14)]                  || 1
        'a pair overlapping, then a lone one later' | [span(0, 6, 10), span(0, 8, 12), span(0, 20, 22)]  || 2
    }

    def "the case that motivates holding intervals rather than a count"() {
        given: "one car; A is returned at 11:00 and B is collected at 11:30"
        def returnedAt11 = span(0, 8, 11)
        def collectedAt1130 = TimeSpan.of(day(0).atStartOfDay().plusHours(11).plusMinutes(30),
                day(0).atStartOfDay().plusHours(18))

        expect: "the two never coincide, so one car serves both"
        PeakUsage.within(WHOLE_DAY, [returnedAt11, collectedAt1130]).peak() == 1
    }

    def "#scenario is outside the window and does not count"() {
        expect:
        PeakUsage.within(span(0, 10, 14), [claim]).peak() == 0

        where:
        scenario                                  | claim
        'a claim that ends before it'             | span(0, 6, 9)
        'a claim that ends exactly at it'         | span(0, 6, 10)
        'a claim that starts after it'            | span(0, 16, 18)
        'a claim that starts exactly when it ends'| span(0, 14, 16)
    }

    def "counts a claim that only partly overlaps the window"() {
        expect:
        PeakUsage.within(span(0, 10, 14), [span(0, 0, 24)]).peak() == 1
    }

    def "clips the usage to the window rather than the claims"() {
        given: "two claims that overlap each other, but only before the window starts"
        def claims = [span(0, 6, 12), span(0, 8, 10)]

        expect:
        PeakUsage.within(span(0, 10, 14), claims).peak() == 1
        PeakUsage.within(span(0, 8, 10), claims).peak() == 2
    }

    def "reports when the peak is first reached"() {
        when:
        def usage = PeakUsage.within(WHOLE_DAY, [span(0, 0, 24), span(0, 9, 11)])

        then:
        usage.peak() == 2
        usage.firstReachedAt().get() == day(0).atStartOfDay().plusHours(9)
    }

    def "reports the earliest of several equally busy instants"() {
        when:
        def usage = PeakUsage.within(WHOLE_DAY, [span(0, 1, 2), span(0, 1, 2), span(0, 20, 21), span(0, 20, 21)])

        then:
        usage.peak() == 2
        usage.firstReachedAt().get() == day(0).atStartOfDay().plusHours(1)
    }

    def "the order of the input does not matter"() {
        given:
        def claims = [span(0, 14, 16), span(0, 6, 12), span(0, 8, 10)]

        expect:
        PeakUsage.within(WHOLE_DAY, claims) == PeakUsage.within(WHOLE_DAY, claims.reverse())
    }

    def "rejects a missing window or missing claims"() {
        when:
        PeakUsage.within(window, claims)

        then:
        thrown(IllegalArgumentException)

        where:
        window    | claims
        null      | []
        WHOLE_DAY | null
    }

    def "an idle window cannot claim an instant, and a used one must"() {
        when:
        new PeakUsage(peak, at)

        then:
        thrown(IllegalArgumentException)

        where:
        peak | at
        0    | Optional.of(day(0).atStartOfDay())
        2    | Optional.empty()
        -1   | Optional.empty()
    }
}
