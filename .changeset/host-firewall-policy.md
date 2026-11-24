---
"hephaestus": minor
---

Host firewall alerts report policy drift and a stopped policy check when you install the firewall status timer and scrape the host with node_exporter.
Mark enrolled scrape targets to also receive an alert when an available exporter omits the policy metrics.

The firewall policy script reports drift and refuses to apply changes when an enabled netfilter-persistent service has a saved IPv4 or IPv6 rules snapshot.
When an operator does not confirm a firewall change, the recovery timer restores the previous UFW policy.
