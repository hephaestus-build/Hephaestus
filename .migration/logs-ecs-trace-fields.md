#### 🔴 Log records name their trace in `trace.id` and `span.id`

A log record now carries the trace scope in the Elastic Common Schema fields `trace.id` and `span.id`, at the top level of the record.
The fields `context.traceId` and `context.spanId` are gone.

Update every query, saved search, collector rule and alert that reads them:

- A search for the reference that a reader reports: `grep '"trace":{"id":"<reference>'` instead of `grep '"traceId":"<reference>'`.
- An OpenTelemetry Collector `filelog` pipeline: read the trace ID from `attributes.trace.id` and the span ID from `attributes.span.id`. [Collecting logs](https://docs.hephaestus.build/admin/monitoring#ship-logs-to-a-log-store) has the example.
