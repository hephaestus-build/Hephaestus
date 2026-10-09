---
"hephaestus": patch
---

Security checks now recognize that the reviewed launch helpers do not contain the HTTP/2 processing code affected by a newly classified vulnerability. The assessment remains bound to the exact helper files and advisory source.

The bundled proxy retains a known HTTP/2 CPU denial-of-service risk in ordinary client and server traffic, including cleartext HTTP/2. Disabling the dashboard does not remove this risk. The reviewed vendor images have a temporary security-policy exception until October 16, 2026 at 00:00 UTC, with no automatic renewal. A fixed vendor replacement is tracked in [#2659](https://github.com/hephaestus-build/Hephaestus/issues/2659).
