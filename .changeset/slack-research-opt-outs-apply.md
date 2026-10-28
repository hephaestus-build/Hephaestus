---
"hephaestus": patch
---

A research opt-out that a person gave in the Slack App Home before version 0.81 applies again.
Since 0.81, Hephaestus read research consent only from the person's account and ignored these opt-outs.
The upgrade adds each one to the research decisions of the linked account. A decision that the account made later still counts.
Where the opt-out is now the latest decision, research stops using that person's data, and the next removal list of a released research dataset can name them.
If your instance runs a research program, Hephaestus asks that person the research question again.
