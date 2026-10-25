---
"hephaestus": patch
---

A GitHub webhook delivery that the receiver answered with a redirect now counts as failed and is redelivered, as GitHub counts it. The example alert rules now also warn when GitHub refuses redeliveries or when the server cannot read the GitHub App's deliveries.
