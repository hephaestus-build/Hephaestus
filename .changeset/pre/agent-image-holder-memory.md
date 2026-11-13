---
"hephaestus": patch
---

The Pod that keeps the agent image on each sandbox node is no longer at risk of an out-of-memory stop. On a gVisor node it used 15 MiB against its 16 MiB limit (staging bench, 2026-11-10), and a stop lets the node remove the image. It now requests 16 MiB with a limit of 32 MiB.
