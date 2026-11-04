---
"hephaestus": minor
---

The bundled NATS broker now requests and is limited to 1 GiB, instead of a 1.5 GiB request and a 3 GiB limit, and the chart sets its `GOMEMLIMIT` to 80 % of its memory limit. Without that soft limit, catching up several streams at once could fill even a 3 GiB limit with heap and get the broker stopped. With it, a catch-up only takes longer. On a single node of 32 GiB, this frees 0.5 GiB of requests. The 16 GiB overlay no longer lowers the request, so it reserves 0.5 GiB more. **Operators:** if you set `nats.resources` yourself, give its memory request and limit the same size in `Mi` or `Gi`. `GOMEMLIMIT` follows that limit.
