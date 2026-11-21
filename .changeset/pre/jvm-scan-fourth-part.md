---
"hephaestus": patch
---

Release vulnerability scans now report the Java runtime flaws that a vendor fixes in an out-of-band build. Before, a scan matched no flaw against a runtime such as 25.0.5.0.3, though the fix is 25.0.5.1, so the daily rescan reported the server images that ran it as clean. No operator action is needed.
