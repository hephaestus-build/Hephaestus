---
"hephaestus": patch
---

A new AI provider connection now uses the Responses API unless you clear the checkbox. For models
and providers that support it, this API can preserve reasoning between tool calls. Support depends on
the provider, model, and deployment. Existing connections keep their API; to change it, create a
Responses API connection and recreate the model entries and bindings on that connection.
