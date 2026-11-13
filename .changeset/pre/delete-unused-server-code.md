---
"hephaestus": patch
---

The undocumented `hephaestus.practices.seed-default-catalog` setting is gone: the default practice catalog always installs, as it did unless you turned it off. A leftover `HEPHAESTUS_PRACTICES_SEED_DEFAULT_CATALOG` has no effect. Server code that nothing called is gone too; nothing else changes.
