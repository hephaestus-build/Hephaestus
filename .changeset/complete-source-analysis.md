---
"hephaestus": patch
---

Source builds now fail when static analysis cannot finish, instead of accepting an incomplete check as a pass.

Source builds also reject missing dependency locks instead of silently resolving unpinned dependencies.

API contract generation no longer starts scheduled background jobs or pulls container images.
Scheduling now respects the runtime-role switch even with Spring Modulith on the classpath.
