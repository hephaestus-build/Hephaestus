---
"hephaestus": patch
---

The server runs the fixed Java runtime 25.0.5.1. It fixes the remote code execution flaw CVE-2026-114938 and the nine other flaws of the OpenJDK advisory of 17 November 2026, CVE-2026-114939 to CVE-2026-114947. Because the runtime is fixed, the server runs the AVX-512 Base64 decoder again. No operator action is needed.
