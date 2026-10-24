---
"hephaestus": patch
---

Pull-based deployment: promoting staging with **freeze** now moves it to the release or commit you name and keeps it there, so one promotion replaces "promote, then freeze". Freezing what a host already runs restarts nothing. Production follows only manual promotions, so it can no longer be frozen; to stop a host from applying anything, disable its reconcile timer.
