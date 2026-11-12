---
"hephaestus": patch
---

Passkey changes and verifications now commit with their audit records. If an audit record cannot be written, the passkey operation rolls back. A server stop after commit no longer loses the record. Practice feedback preparation can also resume after a server stop when the provider readback already completed. Repeated recovery does not resume it twice.
