package org.example.carrental.domain;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * How many cars of each type the rental point runs, built once at start-up from configuration.
 * <p>
 * It is also the authority on which car types exist at all: a type is known exactly when it
 * appears here, even with a count of zero. That distinction is what separates a {@code 400} for
 * a type the business never offered from a {@code 409} for one whose cars are all out.
 * <p>
 * Capacity is a count, not a set of vehicles. No reservation names a car, so nothing in this
 * service needs one to have an identity - see
 * {@code docs/adr/0001-reserve-a-car-type-not-a-car.md}. Individual cars become real when
 * there is a flow that acts on them, such as taking one off the road; the PostgreSQL design in
 * {@code docs/adr/0003-concurrency-and-persistence.md} gives them a {@code cars} table
 * with a {@code retired_at} column, which is where that identity belongs.
 * <p>
 * Cars are never added or retired at runtime, which is a deliberate limitation of this exercise.
 */
public final class Fleet {

    private final Map<CarType, Integer> carsByType;

    private Fleet(Map<CarType, Integer> carsByType) {
        this.carsByType = carsByType;
    }

    /**
     * Builds a fleet from the number of cars of each type. The keys define which types the
     * business offers.
     */
    public static Fleet ofCounts(Map<CarType, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            throw new IllegalArgumentException("The fleet must define at least one car type");
        }
        Map<CarType, Integer> cars = new TreeMap<>();   // sorted, so iteration is reproducible
        counts.forEach((type, count) -> {
            if (type == null) {
                throw new IllegalArgumentException("Car type must not be null");
            }
            if (count == null || count < 0) {
                throw new IllegalArgumentException(
                        "Number of " + type + " cars must not be negative but was " + count);
            }
            cars.put(type, count);
        });
        return new Fleet(Collections.unmodifiableMap(cars));
    }

    /**
     * Every car type the business offers, including any configured with no cars, in a stable
     * order - two identical availability questions must come back identically.
     */
    public Set<CarType> knownTypes() {
        return carsByType.keySet();
    }

    /**
     * Whether the business offers this type at all - as opposed to offering it but having
     * every car of it out on rental.
     */
    public boolean knows(CarType type) {
        return carsByType.containsKey(type);
    }

    /**
     * How many cars of the type are in service. Zero for a type the business does not offer,
     * which the caller is expected to have ruled out with {@link #knows} first.
     */
    public int sizeOf(CarType type) {
        return carsByType.getOrDefault(type, 0);
    }

    @Override
    public String toString() {
        return "Fleet" + carsByType;
    }
}
