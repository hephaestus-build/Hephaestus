---
"hephaestus": patch
---

The project now rescans, every day, the exact images that its staging and production hosts run, read from the signed deploy channels. Before, the daily rescans read the newest `main` build and the latest stable release, so a frozen staging build or a release candidate in production went unscanned. A CVE that CISA lists as exploited, a critical finding or a finding the vulnerability policy refuses now opens a tracking issue at the next run, whether or not a fix exists. No operator action is needed.
