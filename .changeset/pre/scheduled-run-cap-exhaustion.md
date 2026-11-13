---
"hephaestus": minor
---

Scheduled scan runs now end with **Run limit reached** when they use their fixed spending cap.
They no longer block later scheduled checks in the workspace.
Existing scheduled checks in a budget pause at their fixed run cap end automatically the next time they are checked.
In-flight scans must first finish, and their costs must be known.
Runs with no remaining targets finish even when no budget remains.
Temporary funding pauses and unknown review costs still pause the run.
