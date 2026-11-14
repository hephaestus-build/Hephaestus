---
"hephaestus": patch
---

The web app no longer reads `TANSTACK_DEVTOOLS_ENABLED`. It never had an effect in a release image, because the release builds of the TanStack devtools show nothing. If you set it in your environment, remove it; no other step is needed.
