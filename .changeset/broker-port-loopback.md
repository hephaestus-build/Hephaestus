---
"hephaestus": minor
---

The broker's client port is now published on the host's loopback address only, and the `NATS_BIND_HOST` setting is gone. Stacks on the same host, such as preview environments, keep connecting over the shared Docker network. **Operators:** if you set `NATS_BIND_HOST` so that another host could reach the broker, that host can no longer connect. Remove the setting from your environment file.
