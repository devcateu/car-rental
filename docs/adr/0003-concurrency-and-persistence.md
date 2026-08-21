# ADR 0003: Optimistic concurrency, and what this looks like in PostgreSQL

- **Status:** Accepted (the PostgreSQL design is design only - the delivered code is in memory)
- **Date:** 2026-08-21

## Context

The dangerous operation is not the write, it is the read-decide-write sequence:

1. read the availability rows the requested period touches,
2. work out whether the busiest instant of each still leaves a car spare,
3. write the rows back with the new reservation on them.

Two transactions running that sequence concurrently both observe a spare car in step 1, because
under `READ COMMITTED` neither sees the other's uncommitted write. Both then write, and the fleet
is oversold. This is a **write skew**: no row conflicts with any other row, so nothing in a plain
schema stops it.

And nothing *can*, declaratively. As [ADR 0002](0002-storing-availability.md) sets out, the
invariant is a counting one over intervals, and SQL has no constraint for that: `EXCLUDE` forbids
pairs of rows, not a count against a capacity. So the guarantee has to be constructed.

## Decision

**Each availability row carries a version. A write is refused if the row moved since it was read,
and the attempt starts again.**

```sql
UPDATE car_availability
   SET busy = $4,                     -- the whole new array, computed in the application
       version = version + 1
 WHERE (car_type, on_date, version) IN (($1, $2, $3), ...);
-- accept only if the number of updated rows equals the number of days touched;
-- otherwise roll back and start again from the read
```

Note `SET busy = <whole new array>` rather than an append: because the version matched, the row is
known to be exactly the one the calculation was performed on, so writing the full array is safe
and the database never has to understand its contents.

The split is deliberate and worth stating precisely:

- **the database guarantees no lost update** - a row that changed between the read and the write
  cannot be written over, and the failure is loud (fewer rows updated than expected);
- **the application guarantees no oversell** - the capacity comparison happens in Java.

Those two together are as correct as putting the predicate in SQL: if the version still matches,
the row is byte-for-byte what the sweep was run against, so a decision computed outside the
transaction is still a decision about the state being written. → **Version-enforced.**

What is given up is the *floor*: nothing in the schema stops a writer that computes wrongly, or
does not compute at all, from storing an oversold day. That is the honest cost, and the
reconciliation job below is what answers it.

### Retrying

The retry loop is **Resilience4j's**, configured from `car-rental.booking.retry`; the service says
only what one attempt is. Two settings carry the design:

- retry on an **empty result**, which is the conflict signal;
- **never** on an exception - otherwise a booking refused because the fleet is full would burn the
  whole backoff budget before the `409` was sent.

The wait grows geometrically and is randomised around each step, because the writers that need to
back off are, by definition, ones that just collided; making them all wait the same time only
lines them up to collide again.

An attempt is safe to repeat because the reservation id is generated **once**, before the first
try, and placing a reservation is idempotent ([ADR 0002](0002-storing-availability.md)).

**Long rentals are the ones that starve, and this is not fully solved.** A booking touching *n*
days fails if *any* of those *n* rows moved, so the chance of losing grows with the length of the
rental - the classic optimistic-concurrency failure mode, and the reverse of pessimistic locking,
where the long transaction blocks but eventually wins. What is implemented is a bounded budget
with backoff and jitter, which reduces the collision *probability* but grants no fairness: a
booking that exhausts it is answered `503` with `Retry-After`, kept deliberately distinct from the
`409` a full fleet gets. One is worth retrying; the other is not. Escalating to
`SELECT ... FOR UPDATE` after N losses would remove the possibility entirely, at the cost of
reintroducing blocking; it is a knob to turn if measurement demands it, not a default.

## The PostgreSQL schema

```sql
-- Car types are business data, not a fixed list in the code, so they are rows rather than an
-- enum: adding a category must not need a migration. (In the delivered service the same list
-- comes from configuration - see the README.)
CREATE TABLE car_types (
    name text PRIMARY KEY CHECK (name ~ '^[A-Z0-9_]+$')
);

-- Cars exist to define capacity, and for the eventual "this one is off the road" flow.
-- No reservation ever points at one.
CREATE TABLE cars (
    id          text PRIMARY KEY,          -- 'SUV-3'
    type        text NOT NULL REFERENCES car_types (name),
    retired_at  timestamptz                -- null while the car is in service
);

CREATE INDEX cars_in_service_idx ON cars (type) WHERE retired_at IS NULL;

-- The arbiter of what can still be sold. One row per type per day; see ADR 0002.
CREATE TYPE occupancy AS (reservation_id uuid, start_at timestamptz, end_at timestamptz);

CREATE TABLE car_availability (
    car_type text NOT NULL REFERENCES car_types (name),
    on_date  date NOT NULL,
    total    integer NOT NULL,
    busy     occupancy[] NOT NULL DEFAULT '{}',
    version  bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (car_type, on_date)
);

-- The read model: what was sold, to whom, and when.
CREATE TABLE reservations (
    id         uuid PRIMARY KEY,
    car_type   text NOT NULL REFERENCES car_types (name),
    start_at   timestamptz NOT NULL,
    end_at     timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT reservations_at_least_one_day CHECK (end_at - start_at >= interval '1 day')
);

CREATE INDEX reservations_by_type_and_period_idx ON reservations (car_type, start_at, end_at);
```

Notes:

- `reservations` has **no** `car_id`. Taking a car off the road is
  `UPDATE cars SET retired_at = now()` plus lowering `total` on the affected future days; not one
  reservation row is touched.
- The availability rows and the reservation must be written in one transaction. They are two
  records of the same fact, which is why reconciliation is required rather than optional.
- The day rows need materialising ahead of the booking horizon, and days that have passed can be
  archived.

## Rejected alternatives

| Option | Why not |
| --- | --- |
| **`EXCLUDE USING gist` on the reservations** | Not expressible. An exclusion constraint forbids pairs of rows; it cannot count how many overlap at an instant, and there is no `car_id` to key it on once a reservation names a type ([ADR 0001](0001-reserve-a-car-type-not-a-car.md)). |
| **An advisory lock per car type around read-decide-write** | Correct, and it makes the happy path retry-free. Rejected because the lock lives in a separate statement that a writer can simply forget, where the version predicate travels with the write itself; and because it serialises every booking of a type, including ones whose dates never overlap. |
| **`SERIALIZABLE` isolation** | Genuinely correct here - PostgreSQL's SSI detects exactly this write skew - and needs no version column. Rejected as the default because every transaction then carries retry logic for `40001`, and predicate locks are coarse enough that the retry rate under contention is worse. The one alternative I would revisit first. |
| **A trigger that re-runs the capacity check on insert** | Looks like a safety net and is not one. Under `READ COMMITTED` the trigger cannot see the other transaction's uncommitted row, so two concurrent writes both pass. It only works if the writers are already serialised - at which point it adds nothing. |
| **A counter of free cars per type** | Cannot express "free between these two dates"; a counter has no time dimension. See option B in [ADR 0002](0002-storing-availability.md). |
| **Table-level `LOCK TABLE`** | Serialises every car type against every other one, for no benefit. |
| **`car_type` as a PostgreSQL enum** | Adding a category would need `ALTER TYPE ... ADD VALUE`, i.e. a migration and a deploy for what is a business decision. |

## Consequences

- Bookings that touch different days never interfere; contention is scoped to `(type, day)`.
- No writer blocks another. Contention produces retries rather than lock waits, so a slow
  transaction cannot make everyone behind it wait - at the price of the fairness problem above.
- **A reconciliation job is a standing requirement**, not a nicety: with no database-side floor it
  is the only thing that will notice an oversold day. It compares each day row's interval set
  against the reservations.
- Cancellation, when it is added, removes the reservation's `occupancy` from each day it touched;
  capacity returns by itself.
- The in-memory implementation mirrors all of this: a version per row, an all-or-nothing multi-row
  compare-and-set, and the same retry policy. `ConcurrentReservationApiSpec` fires 40 simultaneous
  requests at a fleet of 3 and asserts exactly 3 succeed; removing the version check makes it fail.
