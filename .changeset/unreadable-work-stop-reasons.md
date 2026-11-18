---
"hephaestus": minor
---

Feedback that Hephaestus can no longer send now says why. When Hephaestus loses access to the work, the reason is **Hephaestus can no longer see the work.** When consent to read the work ended, for example in a Slack channel, the reason says that consent ended. Before, an edit or a retraction of feedback reported both as **The work no longer exists.**, and a first post reported them as **The practices behind this feedback no longer require approval.** That reason is gone; feedback that carried it now shows that Hephaestus can no longer see the work. The feedback check before a send also says which of these happened, where before it said that the work was in a state that takes no feedback. A document deleted in Outline now counts as removed work, as deleted GitHub and GitLab work already did. Work that Hephaestus no longer stores, such as a document in a collection you removed from Hephaestus, reads as work that Hephaestus can no longer see.

**API clients:** The `stopReason` filter of the practice review feedback lists (`/practices/reviews/feedback`, `/feedback/locations`, `/feedback/reasons`) no longer accepts `APPROVAL_NO_LONGER_ELIGIBLE`. Filter by `ITEM_NOT_VISIBLE` or `CONSENT_WITHHELD` instead.

**Operators:** Install this change in the stopped window before the 1.0.0 cut. Stop every runtime role before the migration, then start only the upgraded roles. Use the stopped upgrade procedure. Do not run old and new servers together during this change.
