# ADR 0001: A reservation is for a car type, not for a car

- **Status:** Accepted
- **Date:** 2026-08-21

## Context

The customer asks for "an SUV from Monday for three days". They do not ask for `SUV-3`.

The system has exactly one non-trivial invariant:

> for a car type and any instant *t*, the number of reservations in force at *t* never exceeds
> the number of cars of that type.

Everything else - a status, `days >= 1`, a non-null start - is local to a single reservation and
dull. The interesting one is **set-based**: it is a statement about *all the other* reservations,
so by construction it cannot be enforced inside one `Reservation`. Where that check is allowed to
live decides the shape of the whole model, because it decides the transaction boundary.

Three shapes are possible.

### A. `Reservation` is the only aggregate; a service computes capacity

The application service asks a repository for the overlapping reservations, counts, and decides.
The model stays small, but correctness moves *out* of it - onto an isolation level, a pessimistic
lock, or a database constraint. A unit test can then prove that the counting is right when called
sequentially; it cannot prove the fleet is never oversold, because the thing that would prevent
that is not in the model.

### B. Availability is the aggregate, per car type

Availability owns what has been claimed and refuses a booking that would exceed the fleet. The
invariant is inside the model and provable in memory, with no database. This is the family the
design belongs to; exactly how availability is stored is [ADR 0002](0002-storing-availability.md).

### C. `Car` is the aggregate, each with its own schedule

Attractive, because it turns the set-based invariant into a local one: "this car has no
overlapping reservations" lives entirely inside one `Car`, the fleet limit becomes emergent
(there are simply *N* cars), and contention is fine-grained.

It is nonetheless the weakest of the three, for a reason worth naming precisely.

## Why not assign a car at booking time

Offline, B and C are **exactly equivalent**. Reservations are intervals, "two reservations cannot
share a car" makes them an interval graph, and interval graphs are perfect: χ = ω, so the fewest
cars that can serve a set of rentals equals the largest number of them in force at any one
instant. Counting is therefore not an approximation of per-car assignment - if the peak never
exceeds *N*, an assignment provably exists. The counting model can never refuse something the
per-car model would have accepted.

The difference is that assigning a car makes the decision **online and irreversible**. With
*N* = 2:

| | |
| --- | --- |
| `R1` Monday, `R2` Wednesday | a "spread the mileage evenly" policy puts `R1` on car A, `R2` on car B |
| `R3` Monday-Friday | **refused** - yet the peak is 2 and a solution exists: `R1` and `R2` both on A, `R3` on B |

Note the irony: the policy that breaks it - levelling mileage across the fleet - is the sensible
business one, while naive "first free car" happens to consolidate and does better. This is not a
matter of finding a cleverer heuristic. Online interval colouring is *provably* worse than
offline - Kierstead-Trotter gives 3ω-2 colours and the bound is tight - so in the worst case
early binding wastes roughly three times the fleet, and no algorithm fixes that without knowing
the future.

## Decision

**A reservation records the car type. Which physical car the customer drives away is settled at
handover, and is outside this service.**

Admissibility and assignment are separated because they are needed at different moments:

- **admissibility** - whether the booking is taken at all - is decided now, transactionally,
  against the fleet's capacity: peak concurrent rentals < *N*. This is the hard invariant, and
  [ADR 0002](0002-storing-availability.md) is how it is computed and stored.
- **assignment of a specific vehicle** may be deferred to handover. Until then it can be changed
  freely, and offline greedy - sort by start time, take the first free car - is optimal.

This is late binding: do not commit earlier than the business requires. It also matches reality;
rental firms do not tell you at booking which car you will drive, precisely because early binding
costs fleet.

## Consequences

- A customer cannot be promised a specific vehicle. If that is ever sold as a product - "this
  exact car, guaranteed" - it needs the pinned model of option C alongside this one.
- **Cars have no identity in this service.** Capacity is a count per type; a car becomes a thing
  with an identity when there is a flow that acts on it, such as taking one off the road. The
  PostgreSQL design in [ADR 0003](0003-concurrency-and-persistence.md) gives them a `cars` table
  with a `retired_at` column, which is where that identity belongs.
- Taking a car off the road is therefore a one-row update that lowers capacity from that moment
  on. No reservation is touched, and the system simply stops selling into any window that would
  now be oversubscribed. Resolving an already-oversubscribed window - upgrade, refund, borrow
  from another branch - is a business process this exercise does not cover.
- The database cannot enforce the invariant declaratively. An exclusion constraint forbids
  overlapping pairs on the same car; it cannot count overlaps against a capacity. That is the
  concrete price of this decision, and it is worked through in
  [ADR 0003](0003-concurrency-and-persistence.md).
- The greedy assignment at handover is not implemented. The interval-graph result guarantees it
  will always find a car.
- In a real firm, cars within a type are not fully interchangeable - mileage, branch, service
  date, equipment - so assignment stops being pure packing and becomes multi-criteria
  optimisation. A further reason for it to be a replaceable policy outside the booking
  transaction rather than logic buried in an aggregate.
