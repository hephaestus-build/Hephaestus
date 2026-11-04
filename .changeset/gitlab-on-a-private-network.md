---
"hephaestus": minor
---

A self-managed GitLab on a private network can now connect. Set `config.scmPrivateHosts` (`HEPHAESTUS_SCM_EGRESS_PRIVATE_HOSTS`) to its host name or address, and open it with `networkPolicy.scmEndpoints`. The list is empty by default, so every other install keeps refusing private addresses, and loopback, link-local and known cloud metadata addresses stay refused on all installs. Every GitLab API call and sign-in request now checks the address when it connects, so a GitLab that resolves to a private address must be on this list after the upgrade. Git fetches through an HTTP proxy, or with certificate verification turned off, are now refused.
