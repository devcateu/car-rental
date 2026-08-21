package org.example.carrental.domain.availability;

import java.time.LocalDate;
import java.util.List;

import org.example.carrental.domain.CarType;

/**
 * Storage port for availability. See {@code docs/adr/0002-storing-availability.md} for the
 * model and the PostgreSQL design this interface is meant to survive.
 * <p>
 * The store deliberately knows nothing about capacity. It stores rows and refuses to write one
 * that has moved since it was read; deciding whether a booking fits is
 * {@link CapacityCalculator}'s job, in ordinary Java, against the rows it hands out.
 */
public interface AvailabilityStore {

    /**
     * The rows for these dates, creating any that do not exist yet. In a database this is the
     * materialised horizon; in memory the rows are made on demand.
     */
    List<AvailabilityDay> read(CarType carType, List<LocalDate> dates);

    /**
     * Writes every row back, but only if each still carries the version it was read at -
     * {@code UPDATE ... WHERE version = ?}, with the row count checked. Either all of them are
     * written or none is.
     */
    CommitOutcome commit(List<AvailabilityDay> days);
}
