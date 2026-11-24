---
"hephaestus": minor
---

The practice review API uses `person` for the person a review concerns. Update clients to send `personId` and read `person` in review responses. Practice groups and workspace practice counts also use the new names. Person-data exports use `person_account_id` for the renamed account fields.

**Operators:** Stop every role for the 1.0 upgrade. Update clients that use the practice review, practice group, or workspace practice overview API.
