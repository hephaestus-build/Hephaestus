---
"hephaestus": patch
---

Opening an observation, a review page or a practice page no longer waits on GitHub or GitLab, and no longer holds other writes while Hephaestus fetches a repository to check whether the observation is still current. When the server cannot read the current work, the observation now says so: "Unverified since" the day it was last verified against the work, with the exact time in its note. It no longer claims that its record is missing. Its feedback is still held back until the work can be checked again.
