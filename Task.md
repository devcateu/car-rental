Technical assessment
We invite you to complete the following technical exercise. A production ready solution is not
expected; an effort equivalent to approximately two hours should be sufficient. We encourage
you to focus on areas where you have the greatest expertise and can demonstrate depth rather
than breadth.

Guidance
The use of any tools, including AI-based assistance, is permitted; however, all design and
implementation decisions will be considered your own. Please clearly note any known
limitations or tradeoffs in your solution.

Technical Exercise
Please design and implement a simulated Car Rental system using object-oriented principles.
Requirements:
✓ The system should allow reservation of a car of a given type at a desired date and time
for a given number of days.
✓ There are 3 types of cars (Sedan, SUV and van).
✓ The number of cars of each type is limited.
✓ Use unit tests to prove the system satisfies the requirements.

Interview
During the interview, you will be asked to show your code and explain your technical decisions
and reasoning. Please concentrate on non-trivial aspects and avoid spending time on
boilerplate or purely infrastructural elements. Emphasize the strengths of your solution and the
areas where you intentionally chose to invest more effort.




What should be done:
- design API for car reservation
  - API should take 
- keep data in in-memory ( store data in Map)
- write tests e2e
- write algorithm which in most optimal way allocate car to reservation 
  - algorithm should be extremely easy to test
  - it should be stateless - all required parameter should be taken as input, everything else should be returned to output
- prepare ADR how to store data in most optimal way for that task, assuming cocurrency (for ADR purpose assumme that data will be kept in PostgreSQL database)


