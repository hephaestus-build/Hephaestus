---
"hephaestus": patch
---

Database upgrades now stop before baseline synchronization when an installation has not completed v0.77.4. The error names the required release and links the synchronization instructions. Release checks retain the v0.77.4 upgrade path and verify that v0.76.0 is refused.

**Operators:** Before upgrading to 1.0, follow the upgrade-path table. Databases older than v0.77.4 must run v0.77.4 once before baseline synchronization; databases already synchronized on v0.78.0 or later upgrade directly.
