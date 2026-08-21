package org.example.carrental.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A kind of car the rental point offers, such as {@code SEDAN} or {@code VAN}.
 * <p>
 * Deliberately <strong>not</strong> an enum. Which types exist is a business decision that
 * belongs in configuration ({@code car-rental.fleet.counts}), not in the code: adding a
 * category should not need a recompile, and the domain has no rule that depends on any
 * particular type. The authority on which types actually exist is {@link Fleet}, built from
 * that configuration at start-up; a {@code CarType} on its own is just a well-formed name.
 * <p>
 * Names are normalised to upper case and restricted to letters, digits and underscores, so a
 * type reads the same however the configuration spells it and stays safe in a URL.
 */
public record CarType(String name) implements Comparable<CarType> {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Z0-9_]+");

    public CarType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Car type must not be blank");
        }
        name = name.strip().toUpperCase(Locale.ROOT);
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Car type '" + name + "' must consist of letters, digits or underscores only");
        }
    }

    @JsonCreator
    public static CarType of(String name) {
        return new CarType(name);
    }

    @JsonValue
    @Override
    public String name() {
        return name;
    }

    @Override
    public int compareTo(CarType other) {
        return name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return name;
    }
}
