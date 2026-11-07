---
"hephaestus": minor
---

Concurrent requests can no longer create two monitors with the same repository name in one workspace. A conflicting request returns a conflict response.

**Operators:** Stop every role before upgrading. The upgrade stops if duplicate monitor names exist. Reconcile those monitors against the provider’s repository IDs before retrying. It does not remove or merge your monitors.
