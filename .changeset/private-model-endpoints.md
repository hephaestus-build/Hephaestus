---
"hephaestus": minor
---

You can now run practice reviews and conversations on a model endpoint inside your own network, for example vLLM or Ollama on your own GPUs. List the endpoint's host names, IP addresses or CIDR ranges in the new optional `HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS` on the server and the worker.

To permit plain `http://` for those hosts only, set `HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS_ALLOW_HTTP=true`. On Kubernetes, set `config.llmPrivateHosts` and `config.llmPrivateHostsAllowHttp`, and open the network path with `networkPolicy.modelEndpoints`.

Without these settings, nothing changes: a provider must still be a public HTTPS host. Only private addresses can be allowed. Loopback and link-local addresses stay blocked, and so do the cloud metadata and credential ranges of Alibaba Cloud (100.100.100.200), AWS (fd00:ec2::/32), Google Cloud (fd20:ce::/48), Akamai (fd00:a9fe::/32), Scaleway (fd00:42::/32) and Oracle Cloud (fd00:c1::/32). A range wider than /16 for IPv4 or /48 for IPv6, such as 10.0.0.0/8, or one that covers the server's own address stops startup. List the network your model server is in.
