---
"hephaestus": patch
---

Practice reviews reject observations and refusal updates from attempts that were cancelled, retried, or reassigned while a submission was being processed. Repository operations also retain consistent locking as more repositories are accessed, preventing concurrent operations from bypassing each other.
