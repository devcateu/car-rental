# Car Rental

A simulated car rental service: reserve a car of a given type, from a given date and time, for
a given number of days. Java 21 / Spring Boot / Gradle, tested with Spock.

## Running it

```bash
./run.sh                   # start the service on http://localhost:8080
./run.sh test              # run the whole test suite
./run.sh build             # compile, test and package
./run.sh demo              # start it, walk the API with curl, shut it down
```

`run.sh` is a thin wrapper over Gradle, which also picks the JDK (Java 21) through its
toolchain - nothing needs to be on the `PATH` but a JVM. The equivalents are `./gradlew
bootRun` and `./gradlew build`. Set `PORT` to use another port.

The fleet is configured in `src/main/resources/application.yml`:

```yaml
car-rental:
  fleet:
    counts:
      SEDAN: 5
      SUV: 3
      VAN: 2
```

The **keys define which car types exist** - they are not an enum in the code. Renting out
limousines and cargo bikes instead is a configuration change:

```yaml
car-rental:
  fleet:
    counts:
      LIMOUSINE: 1
      CARGO_BIKE: 12
```

Names are matched case-insensitively and normalised to upper case. A type configured with a
count of `0` still exists - it is simply fully booked, which is why the API answers `409` for
it and `400` for a type that was never configured at all.

## API

**Reserve a car.** The request carries exactly what the requirement asks for: a type, a start,
and a number of days. Which physical car the customer drives away is settled at handover, so no
car id comes back - and no customer identity goes in, because nothing in the service acts on it.

```bash
curl -i -X POST http://localhost:8080/api/reservations \
  -H 'Content-Type: application/json' \
  -d '{"carType":"SUV","startDateTime":"2026-09-07T10:00:00","days":3}'
```

```json
{
  "reservationId": "0d1f...",
  "carType": "SUV",
  "startDateTime": "2026-09-07T10:00:00",
  "endDateTime": "2026-09-10T10:00:00",
  "days": 3
}
```

**Read a reservation**

```bash
curl http://localhost:8080/api/reservations/{reservationId}
```

**Check availability without booking.** Only the period is required - the natural question is
"what can I get that week?", not "can I get an SUV that week?".

```bash
curl 'http://localhost:8080/api/availability?startDateTime=2026-09-07T10:00:00&days=3'
```

```json
{
  "startDateTime": "2026-09-07T10:00:00",
  "endDateTime": "2026-09-10T10:00:00",
  "days": 3,
  "availability": [
    {"carType": "SEDAN", "fleetSize": 5, "availableCount": 5},
    {"carType": "SUV",   "fleetSize": 3, "availableCount": 2},
    {"carType": "VAN",   "fleetSize": 2, "availableCount": 2}
  ]
}
```

Adding `&carType=SUV` narrows the list to that one entry rather than changing the shape of the
answer, so a client parses one thing either way.

`availableCount` is how many further bookings the window can take - the fleet size minus the
cars out at its busiest instant. There is no list of free cars: no car is spoken for until
handover.

| Status | When |
| --- | --- |
| `201` | Reserved |
| `400` | The request is malformed or invalid (`days < 1`, unparseable date), or asks for a car type the business does not offer (`UNKNOWN_CAR_TYPE`) |
| `404` | No such reservation |
| `409` | Valid request, but the fleet cannot serve it - `NO_CARS_OF_TYPE` or `ALL_CARS_BOOKED` |
| `503` | Valid request that kept losing races and ran out of attempts - `BOOKING_CONTENTION`, worth retrying |

## Design

```
api            REST controller, DTOs, error mapping     - thin, translates only
application    ReservationService                       - loads state, retries, persists
domain         CarType, RentalPeriod, TimeSpan, Fleet,
               availability/ AvailabilityDay, PeakUsage,
                             CapacityCalculator         - all the rules, no framework
infrastructure InMemoryAvailabilityStore,
               InMemoryReservationRepository, config    - the Maps, the yml binding
```

Availability is **stored**, not derived. One row per car type per calendar day holds how many
cars are in service and exactly when each of that day's reservations occupies one, plus a
version. Reservations are a read model: the record of what was sold.

The decisions worth a reviewer's time:

**1. A reservation names a car type, not a car.** The customer asks for "an SUV", so that is
what is recorded; which vehicle they drive away is settled at handover. Pinning a booking to
`SUV-3` weeks ahead is a promise about a physical object that breakdowns and servicing make
unkeepable, and honouring it would mean reshuffling every future booking of a car the moment it
goes off the road.

It also costs nothing to defer. Rentals are intervals, so they form an interval graph, and
interval graphs are perfect - χ = ω, meaning the fewest cars needed equals the largest number of
rentals in force at any one instant. Admitting on "peak overlap < number of cars" is therefore
*provably equivalent* to an assignment existing. Doing the opposite makes the decision online and
irreversible, which is measurably worse: online interval colouring needs up to 3ω-2 cars
(Kierstead-Trotter, tight), so binding early can waste roughly three times the fleet.
[ADR 0001](docs/adr/0001-reserve-a-car-type-not-a-car.md) works through this, and the two
aggregate shapes it rules out.

**2. The capacity algorithm is a pure function, and the interesting part is the sweep line.**
`CapacityCalculator.decide` receives everything it may know in a `CapacityRequest` (the
reservation being placed, the period, and the availability rows as they were read) and returns
everything it decided in a `CapacityDecision` - the rows as they should now be written, or the
reason and the day that blocks it. It holds no store, no clock, no fields at all, so its tests
are plain objects and data tables.

The rule is that a booking fits when, at every instant of its window, fewer claims are in force
than the type has cars. Counting the bookings that *overlap* the window is the tempting answer
and a wrong one: ten one-day rentals across a fortnight all overlap a fortnight-long request but
never each other, and one car serves them all. `PeakUsage` finds the busiest instant with a
sweep line - `+1` at each start, `-1` at each end, clipped to the window, walked in time order -
with releases ordered before claims at the same instant, so a car returned at 11:00 is free to a
rental starting at 11:00.

**3. Availability is stored per day, but as intervals rather than a count.** Every instant falls
on exactly one calendar day, so checking each day's own row is enough to guarantee the whole
window - which is what makes the invariant local to a single row, and a row version enough to
protect it. Keeping the *intervals* inside the row is what avoids the quantisation a per-day
counter would impose: `A` returned Tuesday 11:00 and `B` collected Tuesday 11:30 both fit.

The cost is that a rental spanning *n* days touches *n* rows, so the write is a multi-row
compare-and-set standing in for a transaction.
[ADR 0002](docs/adr/0002-storing-availability.md) compares this against the three other shapes
that were considered, and is explicit about what each of them costs.

**4. Consistency is chosen over availability - and that is a product decision, not an
engineering one.** The service enforces "never oversold" strictly: a booking is refused rather
than taken on a promise the fleet might not keep. That is a choice, and the opposite choice is
perfectly buildable.

To favour availability instead, we would tolerate overselling in corner cases, and `Reservation`
would become the main aggregate - each booking a local write that never has to agree with
anything else. Airlines do exactly this and compensate afterwards. A middle option is to keep
`Reservation` as the aggregate and span the days a rental touches with a distributed transaction;
that costs considerably more effort, and it needs an answer to a question this design never has
to ask - what to do when a reservation cannot be fulfilled for its whole period.

Which of the three to build depends on what overbooking costs the business against what a refused
booking costs it. That is not an engineering call, and the code does not pretend otherwise: the
rule lives in one place, so moving it is a change of policy rather than a rewrite.

**5. Concurrency is optimistic.** Each day row carries a version; a write is refused if the row
moved since it was read, and the attempt starts again. No lock is taken around the decision, and
bookings in different weeks never interfere.

The retrying itself is **Resilience4j's** - the service says what one attempt is and hands it
over. Two settings carry the design: retry on an *empty result*, which is the conflict signal,
and **never** on an exception, so a request refused because the fleet is full is answered
immediately instead of burning the whole backoff budget first.

An attempt is safe to repeat because the reservation id is generated **once**, before the first
try, and a day that already holds it is passed through untouched - so replaying a write that may
already have landed is a no-op, never a second booking.

A booking spanning *n* days loses if *any* of its *n* rows moves, so long rentals lose more races
than short ones. Backoff with jitter lowers how often that happens but grants no fairness: a
booking that exhausts its budget is answered `503 BOOKING_CONTENTION` with `Retry-After`, kept
deliberately distinct from the `409` a full fleet gets. One is worth retrying; the other is not.

`ConcurrentReservationApiSpec` fires 40 simultaneous requests at a fleet of 3 and asserts exactly
3 succeed; removing the version check from the store makes it fail.

**6. Car types are configuration, not an enum.** `CarType` is a validated value object over a
name; which names exist is decided by the keys of `car-rental.fleet.counts` and owned by
`Fleet`. Adding a category is a config change, not a recompile, and the domain has no rule that
depends on any particular type. `ConfiguredCarTypesApiSpec` boots the whole service against a
fleet of `LIMOUSINE` and `CARGO_BIKE` and drives the full API through it, which is what proves
the types are genuinely out of the code.

Because any well-formed name parses, "unknown type" is a business answer rather than a parse
error, and the two failures are separated deliberately: a type that was never configured is
`400 UNKNOWN_CAR_TYPE` (and the message lists what is on offer), while a configured type with no
free car is `409`.

**Records.** Every value object is a record: the domain values (`CarType`, `RentalPeriod`,
`TimeSpan`, `ReservationId`, `Reservation`), the availability model (`AvailabilityDay`,
`Occupancy`, `PeakUsage`), the algorithm's input and output (`CapacityRequest`,
`CapacityDecision`), the commands and queries, and the API DTOs. Invariants live in compact
constructors, so an instance cannot exist in an invalid state: `RentalPeriod` rejects
`days < 1`, `CapacityDecision` rejects being both admitted and rejected, `CapacityRequest`
insists the rows it is given are exactly the days the period touches, and `PeakUsage` rejects an
idle window that claims an instant. Records also keep the DTOs free of Jackson boilerplate -
no `@JsonCreator` or `@JsonProperty` anywhere.

`Fleet` is a plain class rather than a record: it is a lookup with behaviour - which types exist,
how many cars each has - and a record would have to publish its map through a generated
accessor.

## Tests

```bash
./gradlew test
```

| Spec | What it proves |
| --- | --- |
| `RentalPeriodSpec` | The overlap rule, including both touching boundaries |
| `TimeSpanSpec` | Half-open intervals: overlap, intersection, which days a span touches |
| `PeakUsageSpec` | The sweep line: nesting, staggering, same-day turnaround, clipping at the window edges |
| **`CapacityCalculatorSpec`** | **The admission rule, as an input/output table - no Spring, no database** |
| `AvailabilityDaySpec` | One day's row: capacity while a claim is in force, idempotent writes |
| `CarTypeSpec`, `FleetSpec`, `FleetPropertiesSpec` | Types and counts come from configuration: which types exist, normalisation, validation, fail-fast binding |
| `RetryPropertiesSpec` | That the retry is configured to retry conflicts and never exceptions |
| `ReservationServiceSpec` | Orchestration, against a mocked store and repository |
| `InMemoryAvailabilityStoreSpec` | Version-checked writes, all-or-nothing multi-day commits, concurrent writers |
| `InMemoryReservationRepositorySpec` | The reservation read model and its overlap query |
| `ReservationApiSpec` | **End to end over HTTP**: booking, exhausting the fleet, reuse after return, whole-fleet and filtered availability, every error shape |
| `ConfiguredCarTypesApiSpec` | **End to end** against a fleet of types the code has never heard of |
| `ConcurrentReservationApiSpec` | **End to end**: the fleet is never oversold under load |

## Known limitations and tradeoffs

The same list is kept in [Limitations.md](Limitations.md), which is the one written for the
exercise's "please clearly note any known limitations or tradeoffs".

Deliberate, to keep the exercise to its intended size:

- The number of cars of each type is kept in configuration (yml) rather than in a database, so
  the business cannot easily add a car or retire a broken one without a redeploy. The car types
  themselves are the keys of that configuration, so adding a category is a config change too.
- There is no process for retiring broken cars - but the model is shaped for one: no reservation
  names a car, so retiring one would lower capacity and touch no booking.
- There is no process for cancelling a reservation.
- Bank holidays and the rental point's opening hours are not checked, so nothing verifies that a
  rental starting at a particular time is possible.
- No cleaning or servicing time is reserved after a rental: periods are half-open, so a car
  returned at 11:00 can be re-let from 11:00.
- A car is assumed to be returned exactly at the end of the reserved period, never later.
- Payments and confirmation of a reservation are out of scope.
- The service assumes a single location; times are `LocalDateTime` for that reason, and a network
  of branches would need time zones.
- There is no authentication, authorization or rate limiting.
- A reservation records no customer identity: the reservation id is the caller's handle.
  Recording an owner would mean a real customer id, together with the lookup and cancellation
  flows that need one.
- Storage is in memory, so everything is lost on restart and only one process can run.
  ADR 0003 covers what replaces it.

Consequences of the design itself, rather than of the scope:

- The customer cannot be promised a particular vehicle: choosing the car is deferred to handover
  and is not implemented. This costs no capacity - admitting on peak overlap is provably
  equivalent to an assignment existing - and doing the opposite would waste fleet. See
  ADR 0001.
- **Availability is stored per car type and calendar day**, holding intervals rather than a
  count. That is what lets a car returned at 11:00 be re-let at 11:30, which a per-day counter
  could not express. The cost is that a rental spanning *n* days touches *n* rows, so writing it
  is a multi-row compare-and-set standing in for a transaction; a real database would want one
  aggregate or a saga here. See ADR 0002.
- **Availability rows are created on demand** rather than materialised ahead of a booking
  horizon, and nothing ever prunes them. A real system would materialise a horizon and archive
  days that have passed.
- The design favours consistency over availability by enforcing "never oversold" strictly.
  Tolerating overselling in corner cases, or making `Reservation` the aggregate and spanning the
  days with a distributed transaction, would trade the other way - and which to build is a
  product decision. See point 4 of the Design section.
- The "never oversold" invariant is enforced by the application, not by a database constraint:
  the version check prevents a lost update, but nothing in a schema would reject an oversold row.
  A counting invariant over intervals has no declarative equivalent in SQL. ADR 0003 is explicit
  about that trade.
- A long rental competing with many short ones can exhaust its retry budget and be turned away
  with a `503` even though the fleet had room - it loses if *any* of its rows moved. Backoff with
  jitter lowers how often that happens but grants no fairness; escalating to a pessimistic lock
  would remove it, at the cost of blocking.
- Retrying blocks the request thread while it backs off; the delays are milliseconds, but a
  reactive or queued design would not do this.
- `POST /api/reservations` is not idempotent at the HTTP level: the reservation id is generated
  server-side, so a client retrying a timed-out request creates a second booking. The internal
  write is idempotent, but end-to-end safety would need a client-supplied `Idempotency-Key`.
