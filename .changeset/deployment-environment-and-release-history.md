---
"hephaestus": minor
---

Instance administrators now see where the instance runs and which releases it ran. The **Release** card on the overview names the deployment environment and says when the instance started the running release. **Show release history** lists the last ten releases that the instance started, so an upgrade or a rollback is visible after the fact. Error reports and exported traces carry the same environment and version, and staging errors no longer show as `prod`.

**Operators:** rename `SENTRY_ENVIRONMENT` to `DEPLOYMENT_ENVIRONMENT` in each environment file. If you send errors to Sentry, update alerts and saved searches that filter on `prod`.
