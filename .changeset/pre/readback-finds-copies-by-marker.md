---
"hephaestus": patch
---

When Hephaestus checks whether feedback reached the work after a write it was not sure of, it now finds the feedback by its hidden marker, its author and its place. Before, feedback posted with older escaping could stay unconfirmed. Feedback that an earlier release candidate posted is no longer reported as missing while it is on the work: Hephaestus shows it as unconfirmed until an admin checks it, and never posts it again on its own.
