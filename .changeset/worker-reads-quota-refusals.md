---
"hephaestus": patch
---

On Kubernetes, the worker may now read events and the resource quota in the sandbox namespace. It needs both to tell that the sandbox quota refused a review's Pod, so that the review waits for room instead of failing.
