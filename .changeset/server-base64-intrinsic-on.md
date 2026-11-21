---
"hephaestus": patch
---

The server uses the Java runtime's AVX-512 Base64 decoder again. The runtime 25.0.5.1 fixes CVE-2026-114938, so the server no longer turns the decoder off. The startup log event `jvm.base64_intrinsics.configured` is removed. No operator action is needed.
