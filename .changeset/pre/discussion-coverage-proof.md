---
"hephaestus": patch
---

Reviews no longer treat unread GitHub or GitLab discussions as proof that no one commented. Practices that need a whole discussion are withheld with a capture reason instead of recording Does not apply. This includes new work received through a webhook before its first whole discussion sync. For work without a recorded whole read, select **Run backfill** on its integration, then request a new review after the read completes. A withheld review does not restart automatically.
