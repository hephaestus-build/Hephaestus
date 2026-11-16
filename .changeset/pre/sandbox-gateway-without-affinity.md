---
"hephaestus": minor
---

Sandbox calls can move between ready server replicas without losing an active review's claim or prepared inputs. The gateway no longer pins a sandbox to one server by client IP.

**Operators:** Before the first upgrade, drain active reviews as **Drain legacy reviews before removing gateway affinity** in the upgrade guide describes. Later upgrades use normal rolling updates. With an external PostgreSQL, Helm does not check the connection limit: size it as the sizing guide says.
