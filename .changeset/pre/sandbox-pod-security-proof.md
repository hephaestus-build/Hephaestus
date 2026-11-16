---
"hephaestus": patch
---

The sandbox isolation gate proves again that the sandbox namespace enforces the Restricted Pod Security Standard. It asks the API server to create, in a dry run, a Pod that only the Baseline level admits, and fails unless Pod Security admission refuses it. The failure line names the namespace's enforce label. A namespace that the admin guide describes passes, so no action is needed.
