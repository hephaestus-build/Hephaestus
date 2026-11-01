---
"hephaestus": minor
---

When a page fails to load, it now shows a reference, such as `Reference 4bf92f35`, and a button that copies it. A reader can give the reference to their instance operator, who finds every log record of that request with it. Every problem response from the API now carries the same ID as `requestId`, next to the `X-Request-Id` header. [Correlating a request](https://docs.hephaestus.build/admin/observability#correlating-a-request) shows the search on Kubernetes.
