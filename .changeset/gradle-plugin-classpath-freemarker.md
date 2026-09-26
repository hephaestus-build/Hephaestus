---
"hephaestus": patch
---

The build no longer resolves a vulnerable FreeMarker while generating provider clients. It is used only to generate code at build time and never reached a running Hephaestus, so no deployment is affected.
