---
"hephaestus": patch
---

Worker cleanup, review admission and person erasure no longer remove the same review evidence folder at the same time. Admission still deletes the verified evidence as soon as it is recorded, and cleanup retries a folder that another removal holds on its next pass.
