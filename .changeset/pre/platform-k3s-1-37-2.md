---
"hephaestus": patch
---

The one-host k3s platform now runs k3s v1.37.2+k3s1 and Traefik v3.7.18, which fix known vulnerabilities in the k3s binary, its Helm controller image and Traefik. k3s no longer runs its ServiceLB load balancer or its metrics server, which the platform never used, so `kubectl top` reports no metrics on a one-host install. Traefik keeps the host's ports 80 and 443. The installer of this release upgrades k3s and restarts it once.
