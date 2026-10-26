#### 🔴 Reach the broker only from its own host

The multi-file Compose stack now publishes the broker's client port 4222 on `127.0.0.1` only.
The `NATS_BIND_HOST` setting is no longer read.
A stack on the same host connects over the Docker network `shared-network` to `nats-server:4222`, as preview environments do.

**Operators:** do these steps only if you set `NATS_BIND_HOST`.

1. Remove `NATS_BIND_HOST` from the environment file of the `core` stack.
2. Remove any firewall rule that you added to open port 4222.
3. Move a consumer that ran on another host onto the broker's host, and attach it to `shared-network`.
