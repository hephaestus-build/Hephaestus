---
"hephaestus": patch
---

The server turns off the Java runtime's AVX-512 Base64 decoder, which the reported remote code execution flaw CVE-2026-114938 affects. It stays off until the image ships a fixed Java 25 build.
