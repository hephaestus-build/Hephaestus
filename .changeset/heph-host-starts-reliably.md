---
"hephaestus": patch
---

Hephaestus no longer fails a question with "Hephaestus could not start" when its host starts quickly: the worker now accepts the host's connection as soon as it launches the host. A question whose host still does not start tries one fresh host before it fails. When a sandbox ends abnormally, the worker logs why, for example out of memory, an image that does not pull, or an exit code, and the new `sandbox_exits_total` metric counts each end by sandbox kind and reason.
