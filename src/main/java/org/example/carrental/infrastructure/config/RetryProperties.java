package org.example.carrental.infrastructure.config;

import java.time.Duration;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How hard a booking retries when its optimistic write loses a race, bound from
 * {@code car-rental.booking.retry}.
 * <p>
 * Turned into a Resilience4j {@link RetryConfig} rather than a hand-rolled loop: retrying with
 * backoff and jitter is a solved problem, and the interesting code in this project is elsewhere.
 */
@Validated
@ConfigurationProperties(prefix = "car-rental.booking.retry")
public class RetryProperties {

    @Min(value = 1, message = "there must be at least one attempt")
    private int attempts = 4;

    @NotNull
    private Duration baseDelay = Duration.ofMillis(5);

    @NotNull
    private Duration maxDelay = Duration.ofMillis(50);

    @DecimalMin(value = "1.0", message = "the delay must not shrink between attempts")
    private double multiplier = 2.0;

    @DecimalMin(value = "0.0")
    @DecimalMax(value = "0.99", message = "the randomisation factor must stay below one")
    private double randomizationFactor = 0.5;

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Duration getBaseDelay() {
        return baseDelay;
    }

    public void setBaseDelay(Duration baseDelay) {
        this.baseDelay = baseDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(Duration maxDelay) {
        this.maxDelay = maxDelay;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public double getRandomizationFactor() {
        return randomizationFactor;
    }

    public void setRandomizationFactor(double randomizationFactor) {
        this.randomizationFactor = randomizationFactor;
    }

    /**
     * Resilience4j refuses an interval below a millisecond, so catch it here where the message
     * can name the property rather than at bean creation.
     */
    @AssertTrue(message = "car-rental.booking.retry.base-delay must be at least 1ms")
    public boolean isBaseDelayLongEnough() {
        return baseDelay != null && baseDelay.toMillis() >= 1;
    }

    /**
     * Retries while the attempt reports a conflict - an empty result - and <strong>never</strong>
     * on an exception. That second part matters: a booking refused because the fleet is full
     * throws, and repeating it would only burn the budget and delay the answer.
     * <p>
     * The wait grows geometrically and is randomised around each step, so writers that just
     * collided do not line up and collide again.
     */
    public RetryConfig toRetryConfig() {
        return RetryConfig.custom()
                .maxAttempts(attempts)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        baseDelay.toMillis(), multiplier, randomizationFactor, maxDelay.toMillis()))
                .retryOnResult(result -> result instanceof java.util.Optional<?> outcome && outcome.isEmpty())
                .retryOnException(throwable -> false)
                .build();
    }
}
