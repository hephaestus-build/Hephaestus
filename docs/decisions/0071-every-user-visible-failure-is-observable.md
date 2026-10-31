# ADR 0071: Every user-visible failure is observable, through one first-party pipeline

**Status:** Accepted
**Date:** 2026-10-31
**Authors:** Felix T.J. Dietrich
**Relates to:** ADR 0005 and ADR 0008 (runtime roles), ADR 0058 (supported deployment).

## Context

The server writes ECS JSON logs with trace ids and exports Micrometer metrics.
It can also export OTLP traces.
The web app reports only page experience metrics (`POST /web-vitals`), and that is off by default.
A failure in the browser leaves no signal on the server.

Examples are a route that throws, a chunk that fails to load after a deploy, a request that never arrives, and a sign-out on reload.
An operator learns about such a failure only when a reader complains.
Then nothing joins the complaint to the server request that caused it.

Self-hosting is the Helm chart on Kubernetes only.
A small instance runs on one node with about 16 GiB of memory.
Each npm package that the web app ships adds to the supply chain.
Each kilobyte adds load time for each reader.

## Decision drivers

- A failure that a reader sees must be visible to the operator, with the stage that caused it.
- No content and no direct identifier leaves the browser.
- A self-hoster must not run, secure and upgrade a new service for this.
- The web app gets the fewest new packages and bytes.
- Signals go to the logs and metrics that the operator already collects, with no vendor format.

## Considered options

We measured the sizes on 2026-10-31 with esbuild 0.25 (ESM, minified, gzip -9, React external).

- **Sentry SDK with self-hosted Sentry.** `@sentry/browser` 11.10.0 with only the error integrations is 21.3 KB gzipped, with 7 packages.
  Self-hosted Sentry needs 4 cores, 16 GB of memory and 16 GB of swap ([requirements](https://develop.sentry.dev/self-hosted/)).
  It supports Docker Compose only.
  Rejected: it needs more than the whole node.
- **Sentry SDK with GlitchTip.** The SDK cost is the same.
  GlitchTip needs 256–512 MB and its own PostgreSQL, and it has a Helm chart ([install](https://glitchtip.com/documentation/install)).
  Rejected: each self-hoster gets a second database and upgrade path, and the SDK cost stays.
  Of the small options, it has the best issue UI.
- **Grafana Faro.** `@grafana/faro-web-sdk` 2.13 is 41.5 KB gzipped, also in an errors-only configuration.
  It has 12 packages, including a second copy of `web-vitals`.
  It needs Grafana Alloy's `faro.receiver` or an alpha Collector receiver.
  Rejected: the bytes and the new receiver.
- **OpenTelemetry browser SDK.** `sdk-trace-web` with `instrumentation-fetch` and the OTLP exporter is 23.7 KB gzipped for traces only, with 23 packages.
  OpenTelemetry calls browser instrumentation "experimental and mostly unspecified" ([JavaScript status](https://opentelemetry.io/docs/languages/js/)).
  Rejected for now. See the revisit trigger.
- **A first-party reporter (chosen).** It uses `web-vitals` for page experience, and our own code for browser faults.
  It sends with `fetch` and `keepalive` to the server role.
  The server writes each report as its own log event and metric.

## Decision

1. **The browser reports to the server, and the server writes each report as its own signals.**
   A browser fault becomes one structured log event and one counter with closed labels.
   The server stores no row.
   The report then goes to the logs and metrics that the operator already collects.
2. **One id joins a browser event to the server.**
   The server returns its trace id as `X-Request-Id`.
   A browser fault that a request caused carries that id.
   The error view shows the id to the reader as a reference.
   The browser sends no `traceparent`, so the server keeps its sampling decision ([OpenTelemetry samplers](https://opentelemetry.io/docs/specs/otel/trace/sdk/#parentbased)).
3. **Signals carry no content and no direct identifiers, and labels are closed sets.**
   A browser report has closed values only.
   It has no address, query string, error message, component stack, user agent, person id or workspace id.
   The server validates each value against its closed set before it writes a log record.
4. **Source maps come from the build that is shipped, and are never served.**
   The web app build writes hidden source maps as a separate output of the same build.
   They are kept with the release, and contributors decode a stack offline.
   The image contains no map.
5. **Browser reporting is an operator's choice, off by default, with one switch for each purpose.**
   Whether a purpose needs consent depends on the jurisdiction and the configuration.
   CNIL exempts first-party audience measurement, including performance measurement and the detection of navigation problems, under conditions ([CNIL](https://www.cnil.fr/fr/cookies-solutions-pour-les-outils-de-mesure-daudience)).
   The German DSK makes no general statement for such measurement ([OH Digitale Dienste](https://www.datenschutzkonferenz-online.de/media/oh/OH_Digitale_Dienste.pdf), Rz. 87–89).
   Thus, an operator documents a legal basis for each purpose that they turn on.
6. **Observability is part of done.**
   A change ships its signal, its alert or service level objective, and its runbook row.
   It ships them as a component ships its stories.
   A gate checks each part that a machine can check.

When this ADR was accepted, main had only page experience metrics.
The browser fault reporter, the reference on error views, and the source map output come in later changes.

## Consequences

- The web app adds no runtime package for browser faults.
- An operator gets browser faults in the log store and the Prometheus that they already run.
- There is no issue UI with grouping, assignment or regression tracking. A log query and an alert replace it.
- We maintain the reporter: capture, deduplication and limits. Its scope stays the closed set of fault kinds.
- A readable stack needs the source maps of the release and a contributor's checkout. Operators read the closed fields.

## Revisit when

- The OpenTelemetry browser SDK is stable and its errors and fetch instrumentation are near 10 KB gzipped.
- The Digital Omnibus changes the ePrivacy exemptions for security or for the controller's own measurement.
- Operators need an issue UI that a log query cannot replace.
