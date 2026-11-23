---
"hephaestus": patch
---

A disconnected integration no longer shows "Check … for what Hephaestus left", and no alert email says that Hephaestus could not confirm a removal. Since rc.8, **Provider removals** records each removal that a provider did not confirm, and an instance admin releases it there. A warning that rc.3 to rc.5 left on a connection stayed until the connection was reconnected. The upgrade clears it.

**Operators:** If this instance ever ran 1.0.0-rc.3, rc.4 or rc.5, list the connections that still carry the warning before you upgrade, and remove what they left at the provider. See the migration guide.
