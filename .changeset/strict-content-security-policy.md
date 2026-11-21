---
"hephaestus": patch
---

The web app's page now runs only the scripts that the server marks for that page load: each page load gets its own content security policy nonce, and a script injected into the page does not run. No operator action is needed.
