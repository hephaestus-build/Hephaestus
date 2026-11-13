---
"hephaestus": minor
---

The one-host installer adds more host names to the Gateway with `--extra-host NAME`, for an alias or a redirect from an old host name. Each name gets its own HTTPS listener and a `Let's Encrypt` certificate, and the redirect from HTTP to HTTPS covers it. Attach your own route to the listener of the name. `--render-platform` prints the platform file that a run would install.
