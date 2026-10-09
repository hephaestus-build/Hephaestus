---
"hephaestus": patch
---

The bundled reverse proxy now uses Traefik 3.7.14. The web interface image includes updated nginx and OS packages.

Dashboard-enabled Traefik configurations retain a known CPU-exhaustion risk from malicious Range headers until a fixed vendor image is available. The reference proxy configurations keep the dashboard disabled. The vendor replacement is tracked in #2659.
