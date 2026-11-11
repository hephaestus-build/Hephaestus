---
"hephaestus": patch
---

On the one-host k3s platform, Traefik now watches only Gateway API resources. It no longer reads Traefik's own route and middleware resources, which the platform never used, and it no longer has cluster-wide permissions for them. The Kubernetes guide now names the two Gateway route features that a controller must support. It also states that live streams need an idle timeout above 20 seconds, not a route timeout.
