---
"hephaestus": patch
---

The server image now runs Java 25, the current long-term support release of Java.

**Operators:** Java 25 refuses TLS 1.2 handshakes that a server signs with SHA-1. If Hephaestus connects to a self-hosted GitLab, Outline or mail server, make sure that server signs its TLS 1.2 handshakes with SHA-256 or stronger, or offers TLS 1.3, before you update.
