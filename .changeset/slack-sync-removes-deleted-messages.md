---
"hephaestus": patch
---

A message deleted in a monitored Slack channel is now erased even when Slack's deletion event never arrived, for example after an outage. The nightly Slack sync also stores the new text of a message that was edited while events were lost. To check older messages, it uses up to `hephaestus.sync.slack.reconcile-request-budget` requests (30 by default) for each workspace each night, which can add 30 minutes to the nightly sync at the default request interval. If your Slack app has higher rate limits, raise this budget with the other Slack sync budgets.
