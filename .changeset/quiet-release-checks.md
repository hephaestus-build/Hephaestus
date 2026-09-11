---
"hephaestus": minor
---

Instance administrators can see which release, commit and image digest the server reports from its verified release lock, and whether GitHub has published a newer release. The overview distinguishes a newer release from a failed, rate-limited, disabled or never-completed check, flags schema migrations the newer release publishes, and links its notes and the upgrade guide. Every runtime role reports the same identity under `release` in `/actuator/info`. Set `HEPHAESTUS_RELEASE_CHECK_ENABLED=false` on an air-gapped host to switch the daily check off.
