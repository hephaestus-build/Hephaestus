---
"hephaestus": patch
---

The broker now compresses stored webhook deliveries, which takes the delivery stream to about 5 % of its disk use. A new install does this at once. After an upgrade, no stream is recreated: the broker compresses new blocks after its next restart, and stored deliveries stay readable until they age out. The stream limits still count uncompressed bytes, so keep your settings.
