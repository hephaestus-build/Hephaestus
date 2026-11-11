---
"hephaestus": minor
---

Log records name their trace in the Elastic Common Schema fields `trace.id` and `span.id`, so a log store or a collector's trace parser links each record to its trace without a custom mapping. A local development server writes readable text to its console. Every deployed role still writes JSON.

**Operators:** Search, parse and alert on `trace.id` and `span.id` instead of `context.traceId` and `context.spanId`.
