---
"hephaestus": patch
---

"Log through the platform logger, not print" now treats `NSLog`, the `os_log` functions and Android's `Log` calls as platform logging, whatever tag a `Log` call uses, rather than as print statements.
