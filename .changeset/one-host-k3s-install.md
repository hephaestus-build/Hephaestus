---
"hephaestus": minor
---

You can install Hephaestus on one Linux host with one command. Each release publishes a signed `k3s-install.sh` and the files it installs. The installer checks the host first: the host name must reach this host on port 80, ports 80 and 443 must be free, the host needs 4 CPUs and 32 GiB of memory, or 16 GiB with the planned-outage profile for small hosts, and NTP must keep the clock in sync. Then it installs a pinned k3s, Traefik and cert-manager, gets a `Let's Encrypt` certificate, issues and renews the PostgreSQL certificates, verifies the signed release chart, and installs it with reviews turned on. Run it again to repair or upgrade the host: it keeps every key of the release Secret and generates only the missing ones. If your model endpoint is served by the same cluster's Gateway, the new chart value `networkPolicy.workerGatewayPorts` lets the worker reach it.
