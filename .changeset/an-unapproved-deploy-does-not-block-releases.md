---
"hephaestus": patch
---

A release no longer waits behind an unapproved production deployment. The approval for production
used to sit inside the same run that serialises tag promotion, so a release nobody approved held
that lock and every later release queued behind it without starting — for two days, in a state the
default run listing does not show. The lock now covers only the promotion of the version, series and
latest tags.
