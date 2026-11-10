#### 🔴 Stop every role to cut over to 1.0.0

1.0.0 has no mixed-version window: no role of the previous release runs beside a 1.0.0 role or on its schema.
Follow [Upgrade to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0).
It lists the prerequisites (the agent-sandbox controller, the sandbox runtime, NetworkPolicy enforcement and NATS Server 2.11 or later), the values to move, and the stopped cutover in order.
Read every other entry of this section: each owns one change.
To go back, restore the verified backup from before the migration, then install the previous release.
