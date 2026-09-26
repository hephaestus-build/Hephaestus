---
"hephaestus": patch
---

A practice review now reads a pull request's or merge request's closing link as a candidate that may
close the issue on an eligible merge, and whether the issue is closed only from its recorded state.
Before, the evidence a review read could describe an open issue as already closed by an open merge
request. A merge request stored as closed by its merge now counts as merged when a review checks that
the linked issue's outcome was confirmed.
