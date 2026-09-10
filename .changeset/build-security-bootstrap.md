---
"hephaestus": patch
---

Production profile groups now reject missing encryption keys just like the production profile itself. System-key validation is consistent for encrypted fields and JWT signing keys, including non-ASCII key lengths. Build-only profiles no longer supply placeholder secrets or run container-image bootstrap, and cannot be combined with production profiles.
