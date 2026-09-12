---
"hephaestus": patch
---

Practice reviews reject observations and refusal updates from attempts that were cancelled, retried, or reassigned while a submission was being processed. A fetch into a repository mirror is serialized against the reviews reading that mirror, so concurrent operations on one repository cannot bypass each other.
