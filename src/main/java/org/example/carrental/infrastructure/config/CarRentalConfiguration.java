package org.example.carrental.infrastructure.config;

import io.github.resilience4j.retry.Retry;
import org.example.carrental.domain.CarType;
import org.example.carrental.domain.Fleet;
import org.example.carrental.domain.availability.CapacityCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/**
 * Wires the domain into Spring. The domain classes themselves carry no Spring annotations, so
 * they can be unit tested as plain objects.
 */
@Configuration
@EnableConfigurationProperties({FleetProperties.class, RetryProperties.class})
public class CarRentalConfiguration {

    @Bean
    public Fleet fleet(FleetProperties fleetProperties) {
        return Fleet.ofCounts(fleetProperties.toCountsByType());
    }

    @Bean
    public CapacityCalculator capacityCalculator() {
        return new CapacityCalculator();
    }

    /**
     * Lets {@code ?carType=SUV} bind to a {@link CarType} request parameter. Jackson handles
     * the request bodies through {@code @JsonCreator} on {@code CarType} itself.
     */
    @Bean
    public Converter<String, CarType> carTypeConverter() {
        return CarType::of;
    }

    /**
     * The retry mechanism behind a booking's optimistic write. Resilience4j owns the loop, the
     * backoff and the jitter; the service only says what one attempt is.
     */
    @Bean
    public Retry bookingRetry(RetryProperties retryProperties) {
        return Retry.of("booking", retryProperties.toRetryConfig());
    }
}
