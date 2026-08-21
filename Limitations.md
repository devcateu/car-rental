Limitations to keep task small
- The number of cars of each type is kept in configuration (yml) rather than in a database, and because of that
    - the business cannot easily change the number of cars: adding a new one, or retiring a broken or old one, needs a redeploy
    - the same applies to the car types themselves: they are the keys of that configuration, so adding a category is a config change and a redeploy, not a business action
- There is no process for retiring broken cars
    - the model is however shaped for it: no reservation names a car, so retiring one would lower capacity and touch no booking
    - individual cars have no identity in this service either - capacity is a count per type. A car becomes a thing with an identity when there is a flow that acts on it; the PostgreSQL design in docs/adr/0001 gives them a cars table with a retired_at column, which is where that belongs
- There is no process for cancelling a reservation
- I do not check bank holidays or the rental point's opening hours, so nothing verifies that a rental starting at a particular time is possible
- I do not account for the time needed to clean or repair a car after a rental
    - periods are half-open, so a car returned at 11:00 can be re-let from 11:00
- I assume a car is always returned exactly at the end of the reserved period, never later
- I do not handle payments or confirmation of a reservation
- The service assumes a single location; supporting a network of rental points would need it changed accordingly
    - times are LocalDateTime for that reason; a network of branches would need time zones
- There is no authentication, authorization or rate limiting


Business assumption:
- A reservation is made for a car type, not for a particular car. That keeps us flexible for a future process that takes a car out of service
- A reservation records no customer identity either. Nothing in the service acts on who booked, so the reservation id handed back is the caller's handle
    - the requirement asks for a car of a given type, at a given date and time, for a number of days - and that is exactly what the API takes
    - recording an owner would mean a real customer id rather than free text, together with the lookup and cancellation flows that would need one
- Because of that, a customer cannot be promised a specific vehicle. Choosing the actual car is deliberately deferred to handover and is not implemented here
    - this is not a loss of capacity: rentals are intervals, so admitting on "peak overlap < number of cars" is provably equivalent to an assignment existing (interval graphs are perfect, so the fewest cars needed equals the largest number of rentals in force at once)
    - the opposite - picking a car at booking time - would make the decision online and irreversible, which provably wastes fleet; see docs/adr/0001-reserve-a-car-type-not-a-car.md


Design tradeoffs - deliberate choices, and the consequences of how it is built:
- The design favours consistency over availability, by enforcing the rule "never oversold"
    - to favour availability instead, we would have to tolerate overselling in corner cases, and the main aggregate would then be Reservation. Which of the two to build is a product decision, not an engineering one
    - we could also have made Reservation the main aggregate and used a distributed transaction to enforce availability for each day. That would take considerably more effort, and again it is a product decision - because in that case we would need a procedure for what to do when a reservation cannot be fulfilled for its whole period
- Availability is stored per car type and calendar day, holding the intervals rather than a count
    - that is what lets a car returned at 11:00 be re-let at 11:30, which a per-day counter could not express
    - the cost is that a rental spanning n days touches n rows, so writing it is a multi-row compare-and-set standing in for a transaction; a real database would want one aggregate or a saga here - see docs/adr/0004 and docs/adr/0005
- Availability rows are created on demand rather than materialised ahead of a booking horizon, and nothing ever prunes them
    - a real system would materialise a horizon and archive days that have passed
- "Never oversold" is enforced by the application, not by a database constraint
    - the version check on each row prevents a lost update, but nothing in a schema would reject an oversold row
    - a counting invariant over intervals has no declarative equivalent in SQL - see docs/adr/0001 and docs/adr/0004
- Booking is optimistic: a write that loses its race is retried with backoff and jitter
    - a rental spanning n days loses if any of its n rows moved, so long rentals lose more races than short ones
    - jitter lowers how often collisions happen but grants no fairness, so a long rental competing with many short ones can exhaust its retry budget and be answered 503 even though the fleet had room
    - escalating to a pessimistic lock after N losses would remove that possibility, at the cost of blocking - deliberately not the default
    - retrying also blocks the request thread while it backs off (milliseconds)
- POST /api/reservations is not idempotent at the HTTP level
    - the reservation id is generated server-side, so a client that retries a timed-out request creates a second booking
    - the internal write is idempotent, but end-to-end safety would need a client-supplied Idempotency-Key
- Storage is in memory, so everything is lost on restart and only one process can run


The service only allows cars to be reserved, nothing more.
