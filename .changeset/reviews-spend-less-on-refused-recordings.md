---
"hephaestus": patch
---

Practice reviews spend fewer model calls on recordings that are refused and sent again. A refused observation now names every problem at once, with what the corrected one needs, and no longer leads the model to record a different verdict just to be accepted. A review also no longer sends the model to cite files it cannot cite, fills in what it already knows instead of refusing it, and stops a model that keeps sending an identical refused recording. In-app feedback no longer describes a pattern across your work when the only other occurrence is an earlier review of the same merge request.
