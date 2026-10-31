---
title: Observability principles
description: Why every failure that a reader or an operator meets must leave a signal, and what each change ships for it.
page_type: explanation
---

A failure that nobody can see is a defect, also when the code around it works.
Thus, each change in Hephaestus ships the signal that shows when it fails.
[ADR 0071](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0071-every-user-visible-failure-is-observable.md) records the decision, the options that it rejects, and its order of work.

## The constraints

- **Operators run Hephaestus themselves.** A small instance is one Kubernetes node.
  A signal must go to the log store and the Prometheus that the operator already has.
- **Readers read feedback about their own work.** A signal must not carry what they wrote.
- **Feedback earns trust or it is not sent.** A capture failure is a fact about Hephaestus.
  It is never a claim about the work of a person ([Practice review pipeline](./practice-review-pipeline.mdx)).

## The principles

1. **Every user-visible failure is observable, from the browser to the stage that caused it.**
   An error view, a failed load or a page that does not respond must leave a signal on the server.
   That signal must lead to the request, the job or the stage that failed.
2. **Signals carry no content and no direct identifiers, and labels are closed sets.**
   A signal has no reviewed text, prompts, model output, messages from the browser, page addresses or query strings.
   A metric label is an enum. It is never an id, a model name or an address.
   The correlation ids in logs are personal data. [Observability](/admin/observability#reading-log-records) gives their access and retention rules.
3. **One id joins a browser event to its server trace.**
   A browser event that a request caused carries the trace id of that request.
   [Correlating a request](/admin/observability#correlating-a-request) is the contract for that id.
4. **Each change ships its signal, its alert or service level objective, and its runbook row.**
   It ships them as a component ships its stories.
   A new failure mode without a signal is not done.
5. **The framework writes the signal.** Do not add a second event or metrics abstraction.
   [Structured logging](./structured-logging.mdx) gives the facilities to use.
6. **The server owns sampling.** A caller's trace flags must not decide which traces the server keeps.

## How the parts work together

Each runtime role writes its own signals.
Logs go to standard output as ECS JSON.
Metrics go to the management port.
Traces go to an OTLP collector when the operator turns tracing on.

The browser sends only what an operator switch allows, to an endpoint of the server role.
The server then writes each report as its own metric.
The operator's tools collect all of it.
[Observability](/admin/observability) is the contract for each name that they read.

## What a change ships

| Part | Where | What checks it |
| --- | --- | --- |
| Signal | A log event with an `event.name`, a meter, or a span | Focused tests on the event or the meter |
| Name in the contract | [Observability](/admin/observability) | Review |
| Alert | `deploy/observability/hephaestus-alerts.yml` | `vp run test:alerts`: each alert has a `promtool` case in which it fires and one in which it stays silent |
| Runbook row | [Alert response](/admin/production-operations-runbook#alert-response) | `vp run test:alerts`: each alert has a row in that table |
