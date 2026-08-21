package org.example.carrental.domain

import java.time.LocalDate
import java.time.LocalDateTime

import spock.lang.Specification

import static org.example.carrental.support.Fixtures.day
import static org.example.carrental.support.Fixtures.span

class TimeSpanSpec extends Specification {

    def "must end after it starts"() {
        when:
        TimeSpan.of(at, until)

        then:
        thrown(IllegalArgumentException)

        where:
        at                                  | until
        LocalDateTime.of(2026, 9, 7, 10, 0) | LocalDateTime.of(2026, 9, 7, 10, 0)
        LocalDateTime.of(2026, 9, 7, 10, 0) | LocalDateTime.of(2026, 9, 7, 9, 0)
        null                                | LocalDateTime.of(2026, 9, 7, 10, 0)
        LocalDateTime.of(2026, 9, 7, 10, 0) | null
    }

    def "overlap is symmetric and treats touching spans as separate"() {
        expect:
        span(0, 8, 12).overlaps(span(0, 10, 14))
        span(0, 10, 14).overlaps(span(0, 8, 12))
        !span(0, 8, 10).overlaps(span(0, 10, 12))
        !span(0, 10, 12).overlaps(span(0, 8, 10))
    }

    def "a whole day runs midnight to midnight"() {
        given:
        def wholeDay = TimeSpan.wholeDay(LocalDate.of(2026, 9, 7))

        expect:
        wholeDay.startAt() == LocalDate.of(2026, 9, 7).atStartOfDay()
        wholeDay.endAt() == LocalDate.of(2026, 9, 8).atStartOfDay()
    }

    def "intersection is the shared part, or nothing"() {
        expect:
        span(0, 8, 12).intersect(span(0, 10, 14)).get() == span(0, 10, 12)
        span(0, 8, 12).intersect(span(0, 0, 24)).get() == span(0, 8, 12)
        span(0, 8, 10).intersect(span(0, 10, 12)).empty
    }

    def "lists every calendar day it touches"() {
        expect:
        TimeSpan.of(day(0).atStartOfDay().plusHours(10), day(3).atStartOfDay().plusHours(10))
                .datesTouched() == [day(0), day(1), day(2), day(3)]
    }

    def "a span ending exactly at midnight does not touch the day that begins there"() {
        expect:
        TimeSpan.of(day(0).atStartOfDay(), day(1).atStartOfDay()).datesTouched() == [day(0)]
        TimeSpan.of(day(0).atStartOfDay().plusHours(10), day(1).atStartOfDay()).datesTouched() == [day(0)]
    }
}
