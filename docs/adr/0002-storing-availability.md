# ADR 0002: Availability is stored per car type and day, as intervals

- **Status:** Accepted
- **Date:** 2026-08-21

## Context

Because a reservation claims a car *of a type* rather than a named car
([ADR 0001](0001-reserve-a-car-type-not-a-car.md)), admitting one is a counting question:

> a booking fits when, at every instant of its window, fewer reservations are in force than the
> type has cars.

Two properties of that sentence drive everything below:

1. it is a **counting** constraint, not a pairwise one - so it cannot be written as a SQL
   `EXCLUDE` constraint, which only forbids pairs of rows;
2. it quantifies over **instants**, not days - the requirement says "date and time" - so any
   model that stores availability in buckets has to answer what a bucket means when a rental does
   not align to one.

The subtlety in the counting is that counting the reservations which *overlap the window* is
wrong, and generously so. Ten one-day rentals spread across a fortnight all overlap a
fortnight-long request, but never coincide with each other; one car serves them all. What matters
is the busiest instant inside the window, not the total.

### Storing intervals as two columns

Every schema below stores an interval as two plain `timestamptz` columns, `start_at` and
`end_at`, rather than as a `tstzrange`. Intervals are half-open - `start_at` inclusive, `end_at`
exclusive - which is what makes a car returned at 11:00 available to a rental starting at 11:00.

The consequences are not all in one direction:

- overlap becomes the explicit predicate `a.start_at < b.end_at AND b.start_at < a.end_at` rather
  than the `&&` operator. Two comparisons that must be written the same way everywhere, and a
  boundary mistake turns silently into a double booking - so it belongs in one tested place;
- the endpoints are ordinary columns: individually indexable, readable by any tool, trivially
  mapped by any JDBC or JPA layer, and portable to a database with no range types at all;
- plain btree indexing serves overlap less well than a GiST index over a range does;
- a `CHECK (end_at > start_at)` replaces the guarantee a range type gives for free.

### What "consistency" means here

| Level | Meaning |
| --- | --- |
| **Schema-enforced** | The database rejects the write. A buggy service, a second service, a migration script or a human with `psql` cannot break the invariant. |
| **Version-enforced** | The database guarantees only that the row has not moved since it was read; the rule itself is applied by the application against that row. Correct while writers follow the protocol, and it fails loudly when they collide - but the schema permits an oversold row. |
| **Lock-enforced** | Correct only if every writer follows a protocol it could skip (takes the right advisory lock, or runs `SERIALIZABLE` and retries). |
| **Reconciled** | Nothing prevents the bad state; a job detects it afterwards. |

---

## Options

### A. Reservations are the truth, availability is derived

Keep only the reservations; on every request, read the ones overlapping the window, sweep them
for the busiest instant, compare with the fleet size.

No derived state, so nothing can drift. Exact interval arithmetic, and no horizon to materialise.
But reads do real work per request, growing with how heavily a window is booked - exactly when
the system is busiest - and the invariant lives entirely in code, one forgotten lock away from
being violated. → **Lock-enforced.**

### B. A counter per car type and day

```sql
CREATE TABLE car_availability (
    car_type text, on_date date, total integer, sold integer DEFAULT 0,
    PRIMARY KEY (car_type, on_date),
    CONSTRAINT sold_is_not_negative CHECK (sold >= 0)
);
```

Booking decrements the range in one statement guarded by `sold < total`, and the row count is the
admission test. This is the ordinary inventory-decrement pattern: two concurrent bookings contend
on the same row, and under `READ COMMITTED` PostgreSQL re-evaluates the `WHERE` clause against the
committed new version, so the loser updates zero rows. No advisory lock, no `SERIALIZABLE`.
→ **Schema-enforced**, per bucket.

Its fatal flaw is that a bucket is not an instant. With a one-day minimum and arbitrary start
times there is no bucket rule that is both safe and lossless:

- *consume every day the interval touches* - safe, but `[Mon 10:00, Tue 10:00)` and its
  back-to-back successor `[Tue 10:00, Wed 10:00)` both touch Tuesday and collide, so handovers are
  rejected;
- *consume days from the start only* - preserves handovers but **oversells**:
  `[Mon 10:00, Tue 10:00)` takes Monday and `[Tue 01:00, Wed 01:00)` takes Tuesday, yet the two
  genuinely overlap from Tue 01:00 to Tue 10:00.

The way out would be a business rule - fix the daily pickup time - not a schema trick.

### C. Per-car assignment with an exclusion constraint

```sql
EXCLUDE USING gist (car_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
```

The strongest guarantee available: the invariant becomes a property of the schema at instant
precision and no application cooperation is required at all. Rejected because it reintroduces
exactly what [ADR 0001](0001-reserve-a-car-type-not-a-car.md) rules out - every reservation names
a physical car weeks in advance.

Worth noting a variant: assign a car *provisionally*, never telling the customer, purely so the
constraint has something to key on. Retiring a car then becomes a background reassignment. It is
the only way to get instant-precision, schema-enforced consistency under type-level booking, and
it costs a reassignment process that must itself be correct under concurrency.

---

## Decision

**Store one row per car type per calendar day, holding the intervals each reservation occupies
rather than a count.**

```sql
CREATE TYPE occupancy AS (reservation_id uuid, start_at timestamptz, end_at timestamptz);

CREATE TABLE car_availability (
    car_type text NOT NULL REFERENCES car_types (name),
    on_date  date NOT NULL,
    total    integer NOT NULL,        -- cars in service that day
    busy     occupancy[] NOT NULL DEFAULT '{}',
    version  bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (car_type, on_date)
);
```

This takes option B's move - partition the timeline by day, so the counting invariant becomes
**local to a single row** - without B's quantisation, because the row holds intervals. A rental
returned Tuesday 11:00 and one collected Tuesday 11:30 both fit.

Each reservation writes one clipped element into **every** day it touches. A rental from
Mon 10:00 to Thu 11:00 appears four times: `Mon 10:00 - Tue 00:00`, `Tue 00:00 - Wed 00:00`,
`Wed 00:00 - Thu 00:00`, `Thu 00:00 - Thu 11:00`. Because intervals are half-open the midnight
boundaries neither double-count nor leave a gap.

**Why splitting by day is sound.** Every instant belongs to exactly one calendar day, so if each
day's row independently satisfies "peak within this day ≤ total", the global invariant holds. The
correctness burden is completeness: a reservation must write into *every* day it touches. Miss one
and that day is under-counted - which is why it is one transaction, and why the day range is
derived from the period rather than supplied by the caller.

Reservations remain as a **read model**: the record of what was sold, never the arbiter of what is
available.

### The algorithm is a pure function

`CapacityCalculator.decide` receives everything it may know in a `CapacityRequest` - the
reservation being placed, the period, and the availability rows as they were read - and returns
everything it decided in a `CapacityDecision`: the rows as they should now be written, or the
reason and the first day that blocks it. It has no store, no clock, no fields at all.

Finding the busiest instant of a day is `PeakUsage`, a sweep line: `+1` at each start, `-1` at
each end, both clipped to the window, walked in time order keeping a running total. Releases are
ordered before claims at the same instant, which is what makes back-to-back rentals share one car.
Cost is O(n log n) in the reservations of that type.

`PeakUsage` also reports *when* the peak is first reached, which the 409 response uses to say
which day is full rather than merely "no".

**Over-commitment is a state, not an error.** `spareCapacityWithin` clamps at zero, so a day whose
fleet has shrunk below what is already booked simply sells nothing more. Validating that away in a
constructor would throw in exactly the scenario [ADR 0001](0001-reserve-a-car-type-not-a-car.md)
exists to support.

**Placing a reservation is idempotent.** A day that already holds it is passed through untouched,
so repeating a write that may already have landed cannot turn a successful booking into a
rejection - which is what makes an attempt safe to retry
([ADR 0003](0003-concurrency-and-persistence.md)).

## Consequences

- Tests of the algorithm are plain object construction and assertions: no Spring context, no
  mocks, no clock, no database. `CapacityCalculatorSpec` and `PeakUsageSpec` are data-driven
  tables.
- The same code answers "book me a car" and "how many are free?", so the two can never disagree
  about what "free" means. The availability endpoint reports the fleet size and the spare capacity
  - the tightest day of the window.
- **A rental spanning *n* days touches *n* rows.** Writing it is therefore a multi-row
  compare-and-set standing in for a transaction; a real database would want one aggregate or a
  saga here. This is the price of the day partition, and it is what makes long rentals lose more
  races ([ADR 0003](0003-concurrency-and-persistence.md)).
- The reservation is duplicated, clipped, across every day it touches. Cancellation must remove it
  from each - hence `reservation_id` inside the payload - and reconciliation compares interval
  sets rather than counters.
- Day rows must exist before a date can be sold. The in-memory implementation creates them on
  demand; a database would materialise a horizon and archive days that have passed.
- Each day's spare capacity is computed on read. A denormalised `peak_in_use` column would make it
  a bare column read, and was deliberately left out: it is derived data that can drift from the
  array beside it. If calendar reads become the bottleneck, that column is the first thing to add
  - as a cache, never as the admission test.
- `on_date` must be defined in a fixed zone - the branch's local day - or clipping is ambiguous.
- Do not reach for `tstzmultirange`: it *unions* overlapping ranges, so two cars busy at the same
  time collapse into one span and the count is lost. The column has to be a bag of ranges.
