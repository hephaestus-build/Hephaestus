---
"hephaestus": patch
---

The server now uses Guava 33.7.2, which fixes the security advisory CVE-2026-102554. graphql-java still bundles its own copy of Guava 32.1.2. The release's vulnerability policy records that copy as not affected: the flaw is reachable only through Java deserialization, and a build check prevents any Java deserialization in the server. No action is needed.
