---
"hephaestus": patch
---

Practice reviews now run once at each moment in the life of a pull or merge request, and not twice.

- A GitLab merge request opened ready for review is reviewed once, not twice. Students no longer earn
  double activity points for opening it.
- New commits pushed to a GitLab merge request are now reviewed, as they already were on GitHub. A burst
  of pushes is reviewed once, ten minutes after the last push, at the latest commit. A push during the
  cooldown now waits for the cooldown to end; before, it was not reviewed at all.
- A draft is reviewed only for the practices set to review drafts.
- Feedback from a review that ends after the work was merged now reaches the developer's practice page
  and conversations. The setting for merged work now controls only comments on the merged work itself.
- A lapse that a later review of the same work found fixed no longer counts toward a developer's
  recurring habit.
