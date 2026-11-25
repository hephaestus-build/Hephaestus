---
"hephaestus": minor
---

Host firewall alerts report policy drift and a stopped policy check when you install the firewall status timer and scrape the host with node_exporter.
Mark enrolled scrape targets to also receive an alert when an available exporter omits the policy metrics.

The firewall policy check reports drift when an enabled netfilter-persistent service has a saved IPv4 or IPv6 rules snapshot.
