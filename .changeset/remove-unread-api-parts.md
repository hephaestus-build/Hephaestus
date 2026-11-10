---
"hephaestus": minor
---

The API no longer serves reads that no Hephaestus client uses: `GET /workspaces/{workspaceSlug}/practices/feedback/resolution-counts`, `GET /workspaces/{workspaceSlug}/connections/{id}/audit` and `GET /product-feedback/{feedbackId}`. A review's detail no longer carries `stageTiming` (its jobs carry the same times), a connection no longer carries `instanceKey`, `stateReason` or `capabilities`, and Slack preferences no longer carry `slackUserId` or `slackDisplayName`. **API clients:** read a report through `GET /product-feedback/mine`, and a review's timing from `runtime.jobs`.
