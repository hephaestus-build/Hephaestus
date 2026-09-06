---
"hephaestus": patch
---

Keeps the edge proxy's TLS certificates across deployments. They were stored next to the Compose file, which on a pull-based host is replaced with every release, so each deployment re-issued every certificate and a handful of deployments in one week were enough for Let's Encrypt to start refusing — leaving the site on an untrusted certificate. Certificates now live in their own volume and survive upgrades.

An instance that already serves TLS from the bundled proxy issues its certificates once more on the first start after this upgrade, which needs nothing from you. To skip even that, copy `acme.json` out of the `letsencrypt` directory beside your Compose files into the new `proxy_letsencrypt` volume before starting.
