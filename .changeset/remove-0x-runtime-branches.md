---
---

No user-facing effect. This removes code paths that only a database on the way from 0.x needed: leaseless review attempts and conversation turns, and the check for an unsealed signing key. Every attempt and turn since 1.0.0-rc.1 holds a lease, and the upgrade to that release sealed every key.
