package org.example.carrental.application;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.resilience4j.retry.Retry;
import org.example.carrental.domain.CarType;
import org.example.carrental.domain.Fleet;
import org.example.carrental.domain.RentalPeriod;
import org.example.carrental.domain.Reservation;
import org.example.carrental.domain.ReservationId;
import org.example.carrental.domain.ReservationRepository;
import org.example.carrental.domain.TimeSpan;
import org.example.carrental.domain.availability.AvailabilityDay;
import org.example.carrental.domain.availability.AvailabilityStore;
import org.example.carrental.domain.availability.CapacityCalculator;
import org.example.carrental.domain.availability.CapacityDecision;
import org.example.carrental.domain.availability.CapacityRequest;
import org.example.carrental.domain.availability.CommitOutcome;
import org.example.carrental.domain.exception.NoCarAvailableException;
import org.example.carrental.domain.exception.ReservationNotFoundException;
import org.example.carrental.domain.exception.UnknownCarTypeException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates reservations: read the availability rows a period touches, ask the calculator,
 * write the rows back under their versions, record the reservation.
 * <p>
 * There is no lock around the decision. The rows carry versions, so a write built on a row that
 * has moved is refused and the whole attempt starts again - optimistic concurrency, described in
 * {@code docs/adr/0002-storing-availability.md}.
 * <p>
 * The retrying itself belongs to Resilience4j: this class says only what <em>one</em> attempt
 * is, and hands it over. The reservation id is generated <em>once</em>, before the first
 * attempt, so replaying a write that may already have landed is a no-op rather than a second
 * booking - which is what makes an attempt safe to repeat at all.
 * <p>
 * A booking spanning many days touches many rows and so loses more races than a short one. When
 * the budget runs out it fails with {@link BookingContentionException}, which the API reports
 * as a retryable condition rather than as a full fleet.
 */
@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final AvailabilityStore availabilityStore;
    private final CapacityCalculator capacityCalculator;
    private final Fleet fleet;
    private final Retry bookingRetry;

    public ReservationService(ReservationRepository reservationRepository,
                              AvailabilityStore availabilityStore,
                              CapacityCalculator capacityCalculator,
                              Fleet fleet,
                              Retry bookingRetry) {
        this.reservationRepository = reservationRepository;
        this.availabilityStore = availabilityStore;
        this.capacityCalculator = capacityCalculator;
        this.fleet = fleet;
        this.bookingRetry = bookingRetry;
    }

    /**
     * Books a car of the requested type for the requested period. Which physical car the
     * customer drives away is not decided here - see {@link Reservation}.
     *
     * @throws UnknownCarTypeException      when the business does not offer that type at all
     * @throws NoCarAvailableException      when it does, but the window has no spare car
     * @throws BookingContentionException   when the attempts ran out against a moving target
     */
    public Reservation reserve(ReserveCarCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Reserve command must not be null");
        }
        requireKnownType(command.carType());
        ReservationId reservationId = ReservationId.generate();
        Supplier<Optional<Reservation>> attempt = () -> attemptToBook(reservationId, command);

        return bookingRetry.executeSupplier(attempt)
                .orElseThrow(() -> new BookingContentionException(
                        command.carType(), bookingRetry.getRetryConfig().getMaxAttempts()));
    }

    public Reservation findById(ReservationId id) {
        if (id == null) {
            throw new IllegalArgumentException("Reservation id must not be null");
        }
        return reservationRepository.findById(id)
                .orElseThrow(() -> new ReservationNotFoundException(id));
    }

    /**
     * Read-only view over a period, for one car type or for the whole fleet. Uses the very same
     * capacity rule as {@link #reserve}, so the two can never disagree.
     *
     * @throws UnknownCarTypeException when a named type is not one the business offers
     */
    public FleetAvailability checkAvailability(AvailabilityQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("Availability query must not be null");
        }
        RentalPeriod period = query.period();
        List<Availability> byCarType = carTypesToReport(query).stream()
                .map(carType -> availabilityOf(carType, period))
                .toList();
        return FleetAvailability.of(period, byCarType);
    }

    /**
     * One optimistic pass: read, decide, write under the versions that were read. Empty when the
     * rows moved underneath and the caller should try again.
     */
    private Optional<Reservation> attemptToBook(ReservationId reservationId, ReserveCarCommand command) {
        List<AvailabilityDay> days = readDays(command.carType(), command.period());
        CapacityDecision decision = capacityCalculator.decide(
                CapacityRequest.of(reservationId, command.period(), days));
        if (!decision.isAdmitted()) {
            throw new NoCarAvailableException(command.carType(), command.period(), decision);
        }
        if (availabilityStore.commit(decision.updatedDays()) == CommitOutcome.CONFLICT) {
            return Optional.empty();
        }
        Reservation reservation = Reservation.of(reservationId, command.carType(), command.period());
        return Optional.of(reservationRepository.save(reservation));
    }

    private Availability availabilityOf(CarType carType, RentalPeriod period) {
        TimeSpan rental = period.asTimeSpan();
        int spare = readDays(carType, period).stream()
                .mapToInt(day -> day.spareCapacityWithin(rental))
                .min()
                .orElse(0);
        return Availability.of(carType, period, fleet.sizeOf(carType), spare);
    }

    private List<AvailabilityDay> readDays(CarType carType, RentalPeriod period) {
        List<LocalDate> dates = period.asTimeSpan().datesTouched();
        return availabilityStore.read(carType, dates);
    }

    private Collection<CarType> carTypesToReport(AvailabilityQuery query) {
        return query.carType()
                .map(carType -> {
                    requireKnownType(carType);
                    return (Collection<CarType>) List.of(carType);
                })
                .orElseGet(fleet::knownTypes);
    }

    private void requireKnownType(CarType carType) {
        if (!fleet.knows(carType)) {
            throw new UnknownCarTypeException(carType, fleet.knownTypes());
        }
    }
}
