---
"hephaestus": minor
---

Operators can export practice-review execution and model-request traces to an OpenTelemetry collector using the optional `TRACING_OTLP_ENABLED`, `TRACING_OTLP_ENDPOINT` and `TRACING_SAMPLING_PROBABILITY` settings. Export and sampling remain off by default. Private execution archives link native request bodies to the actual requests forwarded upstream, without putting prompts, conversations or credentials in telemetry attributes.

Timed-out review sessions now retain Pi’s final aborted response and settlement events before disposal, so an interrupted model call remains inspectable in its native transcript.
