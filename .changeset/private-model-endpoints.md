---
"hephaestus": minor
---

You can now run practice reviews and Heph on a model endpoint inside your own network, for example vLLM or Ollama on your own GPUs. List the endpoint's host names, IP addresses or CIDR ranges in the new optional `HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS` on the server and the worker.

To permit plain `http://` for those hosts only, set `HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS_ALLOW_HTTP=true`. On Kubernetes, set `config.llmPrivateHosts` and `config.llmPrivateHostsAllowHttp`, and open the network path with `networkPolicy.modelEndpoints`.

Without these settings, nothing changes: a provider must still be a public HTTPS host. Only private addresses can be allowed. Loopback and link-local addresses stay blocked, as do the cloud metadata addresses 100.100.100.200, fd00:ec2::254 and fd20:ce::254, even when a listed range covers them. List single hosts or narrow ranges: a range that covers your deployment's own network also admits its own services.
