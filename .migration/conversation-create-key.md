#### 🔴 Update clients that submit messages to Ask Hephaestus

- **When:** before the window, after the upgrade
- **Action:** Add the `Idempotency-Key` header before the window. Remove `requestId` after the upgrade.

See the [Ask Hephaestus upgrade steps](https://docs.hephaestus.build/admin/upgrade-notes/08-idempotent-creates).
