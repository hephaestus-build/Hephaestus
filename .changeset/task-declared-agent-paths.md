---
"hephaestus": minor
---

Practice reviews and precomputed analysis use the input locations declared by each task instead of assuming a fixed folder layout. Precomputed analysis also supports script paths containing URL-special characters such as `#`.

Precomputed analysis now has a whole-stage deadline and a per-file output limit, so a stuck or excessively noisy script can fall back to review without precomputed hints.
