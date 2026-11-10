---
"hephaestus": minor
---

The chart now accepts per-provider webhook storage bounds in `webhook.stream.maxBytesByStream`. GitHub keeps its `10GB` default. Other streams use `maxBytes` unless overridden.

**Operators:** Before upgrading, replace `webhook.stream.maxBytesGithub` with `webhook.stream.maxBytesByStream.github`. If you set the old `HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB` variable outside the chart, replace it with `HEPHAESTUS_WEBHOOK_STREAM_MAXBYTESBYSTREAM_GITHUB`. The old field and binding are removed.
