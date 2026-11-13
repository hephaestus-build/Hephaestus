---
"hephaestus": minor
---

Server updates can keep the API and provider webhook reception available. The chart uses two servers by default and a rolling update; the small profile keeps one steady server and reserves its replacement. Sandboxes retry an interrupted model stream and admit the completed answer once.

**Operators:** Remove `server.rolloutStrategy` from your site values. Reserve room for the draining servers and their replacements before updating. Use the target release's small profile. Its database pool follows the inbox and sandbox holder caps, and its PostgreSQL connection limit includes both servers and all other clients. Remove copied pool and `postgresql.parameters.max_connections: 80` overrides, or recalculate them against the complete budget. Its automatic conversation capacity includes the replacement server. The stopped cutover to 1.0 still applies.
