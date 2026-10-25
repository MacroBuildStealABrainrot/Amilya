# Superseded (test6) lease rules

These files test the **first** lease design (test5/test6): "store the provider's cost first, then settle, and let a cleanup job
finish a stored result". On review you decided (test7, decision 4) that a customer must **not** be charged for an attempt whose
answer was lost, and that a retry must never be charged twice. A stored cost with a lost answer would charge the customer for work
they never received, so that design was replaced. The new rules and tests are in `../lease_schema.sql` and `../lease_pg_test.py`.
Kept only so the earlier results remain traceable. Do not use.
