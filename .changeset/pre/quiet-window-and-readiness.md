---
"hephaestus": minor
---

A burst of pushes and edits on the same pull request now gets one review instead of one review for each event. Events on the same work wait in a quiet window that closes 2 minutes after the newest event, and at most 10 minutes after the first. The review covers every practice that the events select. Merged, closed and a submitted pull request review still start at once.

A push or an edit now reviews only the practices that have no current observation. Opened, ready and a review request still measure every practice that they select. While an earlier review of the same work, queued or running, still includes a practice that the newer events select, they wait until that review ends and are then decided again.

A practice that needs the checks of a pull request (its checks source is Required or Complete) now waits until they finish. Its review shows why it was not reviewed yet, and the practice runs after the checks finish. A practice that reads the checks as Optional never waits.

A practice whose evidence settings name a source that Hephaestus cannot read for that kind of work is no longer selected. Before, it was withheld on every review and left the review Partial. Fix its evidence settings, or take the offered catalog update, to review it again.

The optional settings `ITEM_EVENT_LEDGER_QUIET_WINDOW` (default `2m`) and `ITEM_EVENT_LEDGER_QUIET_WINDOW_CAP` (default `10m`) set the window.
