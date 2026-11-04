---
"hephaestus": patch
---

A review on a slow model provider now finishes in as many attempts as it needs. An attempt that finishes a practice no longer spends a retry, and the next attempt continues with the remaining practices. An attempt that finishes no practice still spends one.
