package org.example.carrental.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.example.carrental.domain.CarType;
import org.example.carrental.domain.RentalPeriod;
import org.example.carrental.domain.Reservation;
import org.example.carrental.domain.ReservationId;
import org.example.carrental.domain.ReservationRepository;
import org.springframework.stereotype.Repository;

/**
 * In-memory storage for the reservation read model, as required by the exercise.
 * <p>
 * Two structures are kept in step: the primary map by id, and an index by car type. The index
 * exists because a query only ever wants one type - without it each call would scan the whole
 * store. It is populated lazily, since the set of car types is configuration rather than a
 * fixed enum, and the overlap filter on top of it stands in for the range index a database
 * would use.
 */
@Repository
public class InMemoryReservationRepository implements ReservationRepository {

    private final Map<ReservationId, Reservation> reservationsById = new ConcurrentHashMap<>();
    private final Map<CarType, List<Reservation>> reservationsByCarType = new ConcurrentHashMap<>();

    @Override
    public Reservation save(Reservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("Reservation must not be null");
        }
        Reservation previous = reservationsById.putIfAbsent(reservation.id(), reservation);
        if (previous != null) {
            throw new IllegalStateException("Reservation " + reservation.id() + " already exists");
        }
        indexOf(reservation.carType()).add(reservation);
        return reservation;
    }

    @Override
    public Optional<Reservation> findById(ReservationId id) {
        return Optional.ofNullable(reservationsById.get(id));
    }

    @Override
    public List<Reservation> findOverlapping(CarType carType, RentalPeriod period) {
        if (carType == null) {
            throw new IllegalArgumentException("Car type must not be null");
        }
        if (period == null) {
            throw new IllegalArgumentException("Rental period must not be null");
        }
        return indexOf(carType).stream()
                .filter(reservation -> reservation.clashesWith(period))
                .toList();
    }

    /**
     * Everything stored, for tests and diagnostics. Not on the port: no production code needs
     * to read the whole store.
     */
    public List<Reservation> findAll() {
        return List.copyOf(new ArrayList<>(reservationsById.values()));
    }

    public int size() {
        return reservationsById.size();
    }

    public void clear() {
        reservationsById.clear();
        reservationsByCarType.clear();
    }

    private List<Reservation> indexOf(CarType carType) {
        return reservationsByCarType.computeIfAbsent(carType, type -> new CopyOnWriteArrayList<>());
    }
}
