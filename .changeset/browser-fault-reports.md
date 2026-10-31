---
"hephaestus": minor
---

Operators can turn on browser fault reports, so the errors that readers meet in the browser reach the server's logs and metrics. The setting is off by default. Turn it on with `BROWSER_FAULT_REPORTS=true`, or `config.browserFaultReports: true` in the Helm chart. In the EU, this can require consent or a documented legal basis, so read [Browser fault reports](https://docs.hephaestus.build/admin/legal-pages#browser-fault-reports) first.

When it is on, each page reports an error that no code caught and a promise that failed with no handler, also before sign-in. The server writes each failure as a `browser.fault` log record and counts it in `browser_faults_total`. A report has no error message, page address or identifier of a person or workspace. The new alert `HephaestusBrowserFaultsRising` fires at twenty failures of one kind in one hour. [Browser faults](https://docs.hephaestus.build/admin/observability#browser-faults) lists the fields.
