---
"hephaestus": patch
---

The webhook receiver now answers `408` when a delivery's body does not arrive within 30 seconds. A sender that dripped a body could keep its connection and the bytes behind it for as long as it liked, because the connection's read timeout starts again with each byte. No provider waits that long for an answer, so real deliveries are not affected.
