package org.example.carrental.infrastructure;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.example.carrental.domain.CarType;
import org.example.carrental.domain.Fleet;
import org.example.carrental.domain.availability.AvailabilityDay;
import org.example.carrental.domain.availability.AvailabilityKey;
import org.example.carrental.domain.availability.AvailabilityStore;
import org.example.carrental.domain.availability.CommitOutcome;
import org.springframework.stereotype.Repository;

/**
 * In-memory availability storage, as required by the exercise.
 * <p>
 * Rows are created on demand rather than materialised ahead of a horizon, which is the one
 * liberty this implementation takes over the PostgreSQL design; a new day starts empty with as
 * many cars as the fleet configuration gives that type.
 * <p>
 * {@link #commit} stands in for a database transaction: it checks every row's version and
 * writes all of them or none. The brief lock it takes is <em>not</em> the concurrency control -
 * the versions are - it is only what makes the multi-row swap atomic, the job a transaction
 * would do. The expensive part, reading and deciding, happens entirely outside it.
 */
@Repository
public class InMemoryAvailabilityStore implements AvailabilityStore {

    private final Map<AvailabilityKey, AvailabilityDay> days = new ConcurrentHashMap<>();
    private final Map<CarType, ReentrantLock> locksByCarType = new ConcurrentHashMap<>();
    private final Fleet fleet;

    public InMemoryAvailabilityStore(Fleet fleet) {
        this.fleet = fleet;
    }

    @Override
    public List<AvailabilityDay> read(CarType carType, List<LocalDate> dates) {
        if (carType == null) {
            throw new IllegalArgumentException("Car type must not be null");
        }
        if (dates == null || dates.isEmpty()) {
            throw new IllegalArgumentException("There must be at least one date to read");
        }
        List<AvailabilityDay> rows = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            rows.add(days.computeIfAbsent(
                    AvailabilityKey.of(carType, date),
                    key -> AvailabilityDay.empty(key.carType(), key.date(), fleet.sizeOf(key.carType()))));
        }
        return List.copyOf(rows);
    }

    @Override
    public CommitOutcome commit(List<AvailabilityDay> updated) {
        if (updated == null || updated.isEmpty()) {
            throw new IllegalArgumentException("There must be at least one row to commit");
        }
        ReentrantLock lock = lockFor(updated.getFirst().carType());
        lock.lock();
        try {
            for (AvailabilityDay day : updated) {
                AvailabilityDay stored = days.get(day.key());
                if (stored == null || stored.version() != day.version()) {
                    return CommitOutcome.CONFLICT;
                }
            }
            updated.forEach(day -> days.put(day.key(), day.atNextVersion()));
            return CommitOutcome.COMMITTED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Forgets every row, so a test can start from a clean fleet. Not on the port: production
     * code never throws availability away.
     */
    public void clear() {
        days.clear();
    }

    private ReentrantLock lockFor(CarType carType) {
        return locksByCarType.computeIfAbsent(carType, type -> new ReentrantLock());
    }
}
