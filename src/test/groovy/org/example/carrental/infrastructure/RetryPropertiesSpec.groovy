package org.example.carrental.infrastructure

import java.time.Duration

import org.example.carrental.infrastructure.config.RetryProperties
import spock.lang.Specification
import spock.lang.Subject

/**
 * The loop itself is Resilience4j's; what is worth testing is that we configured it to retry
 * the right thing - and, more importantly, not the wrong thing.
 */
class RetryPropertiesSpec extends Specification {

    @Subject
    def properties = new RetryProperties()

    def "defaults to a small budget with backoff"() {
        expect:
        properties.attempts == 4
        properties.baseDelay == Duration.ofMillis(5)
        properties.maxDelay == Duration.ofMillis(50)
        properties.toRetryConfig().maxAttempts == 4
    }

    def "retries while an attempt reports a conflict"() {
        given:
        def config = properties.toRetryConfig()

        expect: "an empty result is the conflict signal"
        config.resultPredicate.test(Optional.empty())

        and: "a booking that went through is not retried"
        !config.resultPredicate.test(Optional.of('a reservation'))
    }

    def "never retries an exception, so a full fleet is answered at once"() {
        given:
        def config = properties.toRetryConfig()

        expect: "otherwise a 409 would be delayed by the whole backoff budget before being sent"
        !config.exceptionPredicate.test(new IllegalStateException('the fleet is full'))
        !config.exceptionPredicate.test(new RuntimeException())
    }

    def "carries the configured attempt count into the retry"() {
        given:
        properties.attempts = 7

        expect:
        properties.toRetryConfig().maxAttempts == 7
    }

    def "backs off geometrically, jittered around each step"() {
        given:
        properties.baseDelay = Duration.ofMillis(10)
        properties.maxDelay = Duration.ofMillis(200)
        properties.multiplier = 2.0
        properties.randomizationFactor = 0.5

        when: "the interval for each attempt is drawn many times"
        def interval = properties.toRetryConfig().intervalFunction
        def firstStep = (1..200).collect { interval.apply(1) }
        def secondStep = (1..200).collect { interval.apply(2) }

        then: "each step stays within its randomisation band"
        firstStep.every { it >= 5 && it <= 15 }
        secondStep.every { it >= 10 && it <= 30 }

        and: "and jitter spreads collided writers rather than lining them up again"
        firstStep.toSet().size() > 1
    }

    def "insists on a delay the retry library will accept"() {
        expect: "resilience4j refuses anything below a millisecond"
        properties.baseDelayLongEnough

        when:
        properties.baseDelay = delay

        then:
        !properties.baseDelayLongEnough

        where:
        delay << [Duration.ZERO, Duration.ofNanos(500)]
    }

    def "never waits longer than the maximum"() {
        given:
        properties.baseDelay = Duration.ofMillis(10)
        properties.maxDelay = Duration.ofMillis(40)

        when:
        def interval = properties.toRetryConfig().intervalFunction

        then:
        (1..10).every { interval.apply(it) <= 40 }
    }
}
