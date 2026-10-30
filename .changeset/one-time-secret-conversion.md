---
"hephaestus": minor
---

Stored connection secrets and JWT signing keys are encrypted during the upgrade. Existing JWT signing identities and connection credentials stay the same. The server refuses plaintext secrets after the conversion.

**Operators:** Keep the existing system encryption key and the active credential key and version. Configure any missing keys before the migration runs. Keep the prior credential key if any stored credentials still use it. Stop all database writers for the upgrade. Back up the database and the keys first.
