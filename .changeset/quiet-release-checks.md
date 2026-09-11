---
"hephaestus": minor
---

Instance administrators see which release, commit and image digest the server reports from its verified release lock, and whether a newer release is published. To learn that, the server asks `api.github.com` once a day, unauthenticated and without any instance data; set `HEPHAESTUS_RELEASE_CHECK_ENABLED=false` on an air-gapped host to switch it off. The overview keeps a newer release apart from a failed, rate-limited, disabled or never-completed check, says whether the newer release carries schema migrations, and links its notes and the upgrade guide. Every runtime role reports the same identity under `release` in `/actuator/info`.
