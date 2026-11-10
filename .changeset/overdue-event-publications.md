---
"hephaestus": patch
---

Operators now get the `HephaestusEventPublicationsOverdue` alert when durable server work, such as an email or an account export, is still incomplete one hour after it started. The `event_publications_overdue` metric names the work that keeps retrying.
