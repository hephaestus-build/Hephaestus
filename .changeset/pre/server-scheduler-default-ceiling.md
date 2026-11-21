---
"hephaestus": patch
---

The server leaves the virtual-thread scheduler at the JVM's default ceiling. On Java 25, requests that wait for the network no longer need the larger ceiling.
