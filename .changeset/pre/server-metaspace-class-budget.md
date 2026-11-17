---
"hephaestus": patch
---

The server reserves more memory for class metadata on Java 25, so a long run under review load stays well below the limit. At the default 3 GiB memory limit, the Java heap is about 34 MiB smaller.
