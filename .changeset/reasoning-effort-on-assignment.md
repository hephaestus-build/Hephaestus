---
"hephaestus": minor
---

Heph no longer fails an answer that is still being written after five minutes. One Heph answer can now take up to 15 minutes. An answer whose model stops sending still fails after the model's silence limits. The reasoning effort is now a setting of each model assignment, not of the model, so one model can answer Heph at a low effort and review at a higher one. A new Heph assignment starts at **Low**. At its time limit, Heph now says "Heph reached its time limit and stopped this reply." An answer that reaches the limit of model calls now says "Heph could not finish this reply."

**Operators:** Stop every role before you upgrade. The upgrade moves the reasoning effort of each model to its assignments. Practice review and decision model assignments keep the effort of their model. A Heph assignment gets **Low** if its model had **Medium** or more, and keeps a lower effort or none. A Heph assignment with a timeout of 300 seconds or more gets 900 seconds. The model API no longer takes or returns `reasoningEffort` and `clearReasoningEffort`. Set these fields on `PUT /workspaces/{slug}/agents/{purpose}` instead, and update each script that sets them on a model.
