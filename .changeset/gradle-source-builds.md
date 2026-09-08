---
"hephaestus": patch
---

Source builds now use a verified Gradle wrapper, reuse unchanged compilation outputs, and select server tests explicitly by tier. Release images continue to use the same packaged application verified by the API and browser checks. Existing deployments require no configuration changes.
