---
"hephaestus": minor
---

You can now run practice reviews and Heph on a model endpoint inside your own network, for example vLLM or Ollama on your own GPUs. List the endpoint's host names, IP addresses or CIDR ranges in the new optional `HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS` on the server and the worker.

To permit plain `http://` for those hosts only, set `HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS_ALLOW_HTTP=true`. On Kubernetes, set `config.llmPrivateHosts` and `config.llmPrivateHostsAllowHttp`, and open the network path with `networkPolicy.modelEndpoints`.

Without these settings, nothing changes: a provider must still be a public HTTPS host. Only private addresses can be allowed. Loopback addresses and the cloud metadata services stay blocked even when a listed range covers them.
