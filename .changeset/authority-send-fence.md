---
"hephaestus": minor
---

When someone leaves a workspace or loses a role, Hephaestus no longer sends a Slack message, an email or a comment on the work that it queued there before the change. A send keeps the access that its request was checked under, so a reply to a question asked before the change does not go out after it. A model call that waits while access narrows does not go out either.

When a developer turns AI off or makes a first AI choice, their running review stops at its next step, and nothing more of it goes to the model or out to the work. A queued weekly digest, in Slack or by email, is not sent either.

An approval from an admin who just lost the role or was blocked is refused, and so is the comment that an earlier approval of theirs queued. Withdrawing a comment still works after access narrowed.
