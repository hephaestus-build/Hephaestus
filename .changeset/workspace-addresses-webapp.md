---
"hephaestus": minor
---

Workspaces can now use their own subdomains for public activity and signed-in pages. Sign-in, account settings, and the API stay on the instance address. The browser extension also accepts a workspace address.

Operators can turn on workspace subdomains after they configure DNS-only wildcard records, a DNS-01 wildcard certificate, and exact OAuth callbacks on the instance address. Use the same workspace-subdomain switch and base domain for the server, web app, and proxy. The switch remains off by default.
