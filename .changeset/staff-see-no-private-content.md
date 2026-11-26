---
"hephaestus": minor
---

Staff no longer see a person's private content in Hephaestus.
Instance admins no longer have **View as user** or the download of a person's data.
**Per-person results for admins** is removed, so workspace admins see practice results without names.
A workspace admin still sees the reason for a disagreement, for 30 days, to correct the result.

To get help, a person selects **Share with support** on one question of their conversation and shares it with one instance admin.
The admin reads exactly what the person previewed, for 7 days, and the person can revoke the share and sees each read.
Without the messages, the share shows only how Hephaestus handled the question, with the run ID for the server logs.
An access request is answered outside Hephaestus for now. A later release delivers the records to the person's own data exports.

**Operators:** Move scripts off the removed View as user, person-data export and per-person results routes. Remove the overrides `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_CAPACITY` and `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_PERIOD`, which the server no longer reads.
