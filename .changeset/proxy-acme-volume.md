---
"hephaestus": patch
---

Keeps the edge proxy's TLS certificates across deployments. They were stored next to the Compose file, which on a pull-based host is replaced with every release, so each deployment re-issued every certificate and a few deployments in one week were enough for Let's Encrypt to start refusing — leaving the site on an untrusted certificate. Certificates now live in their own volume and survive upgrades.

**Operators:** a host that already serves TLS from the bundled proxy issues its certificates once more on the first start after this upgrade. To keep the ones it has, copy `acme.json` from the `letsencrypt` directory beside your `compose.proxy.yaml` into the new `proxy_letsencrypt` volume before starting.
