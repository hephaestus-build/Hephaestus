---
"hephaestus": patch
---

The maintenance page now runs from the same signed, scanned webapp image as the application, rather than a separate unpatched Nginx image. Its files and startup remain separate from the application. Release locks select the same verified webapp version for both services.
