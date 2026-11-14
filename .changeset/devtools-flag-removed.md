---
"hephaestus": patch
---

The web app no longer reads `TANSTACK_DEVTOOLS_ENABLED`. It never had an effect in a release image. If your environment sets it, you can remove it.
