---
"hephaestus": patch
---

Durable server work that fails the same way every time no longer retries for ever. The server now retries a failed email, account export or other durable work at most ten times. After that, the work stays recorded and the new alert `HephaestusEventPublicationsExhausted` names its listener. The production operations runbook shows how to replay it after the cause is fixed. Before, one failure that never recovered could grow to thousands of retries and delay the retries of all other work.
