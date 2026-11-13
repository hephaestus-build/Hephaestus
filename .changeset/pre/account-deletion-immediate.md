---
"hephaestus": minor
---

Account deletion is immediate. When a person deletes their own account, Hephaestus erases it as soon as the deletion is saved, instead of after a 48-hour wait. The hourly job retries an erasure that did not finish. Hephaestus no longer sends the "scheduled for deletion" email. The setting `hephaestus.auth.delete-cooldown` (`HEPHAESTUS_AUTH_DELETE_COOLDOWN`) is gone; remove it from your configuration.
