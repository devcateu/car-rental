package org.example.carrental.infrastructure.config;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.example.carrental.domain.CarType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The fleet, bound from {@code car-rental.fleet.counts} in application.yml:
 *
 * <pre>
 * car-rental:
 *   fleet:
 *     counts:
 *       SEDAN: 5
 *       SUV: 3
 *       VAN: 2
 * </pre>
 *
 * The <em>keys</em> define which car types the business offers - adding a category is a
 * configuration change, not a code change. The keys are kept as plain strings here and turned
 * into {@link CarType} on the way out, so a malformed name fails fast at start-up with a
 * message naming the offending key rather than an opaque binding error.
 * <p>
 * Keeping this in configuration rather than in storage is an explicit limitation: the business
 * cannot add or retire a car without a redeploy. See the README.
 */
@Validated
@ConfigurationProperties(prefix = "car-rental.fleet")
public class FleetProperties {

    @NotEmpty(message = "at least one car type must be configured under car-rental.fleet.counts")
    private Map<String, @Min(0) Integer> counts = new LinkedHashMap<>();

    public Map<String, Integer> getCounts() {
        return counts;
    }

    public void setCounts(Map<String, Integer> counts) {
        this.counts = counts;
    }

    public Map<CarType, Integer> toCountsByType() {
        Map<CarType, Integer> byType = new LinkedHashMap<>();
        counts.forEach((name, count) -> {
            CarType carType = toCarType(name);
            if (byType.put(carType, count) != null) {
                throw new IllegalStateException("Car type " + carType + " is configured more than once");
            }
        });
        return byType;
    }

    private static CarType toCarType(String name) {
        try {
            return CarType.of(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid car type '" + name + "' in car-rental.fleet.counts: " + e.getMessage(), e);
        }
    }
}
