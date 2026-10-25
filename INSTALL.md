# Installing Hephaestus

There is exactly **one supported install**: the Docker Compose stack in
[`docker/self-host/`](docker/self-host/) on one 64-bit Linux host.
The [installation requirements](https://docs.hephaestus.build/admin/install#requirements)
describe the supported hosts and capacity guidance.
Other setups may work but are unsupported.
The Compose stack is deprecated. From 1.0.0, the Helm chart is the only supported production deployment.
The [compatibility policy](https://docs.hephaestus.build/admin/compatibility-policy#supported-deployment) says what this means for your install.

**→ Follow the install guide: <https://docs.hephaestus.build/admin/install>**

For contributor/development setup, see the
[contributor docs](https://docs.hephaestus.build/contributor/local-development) instead.
Contributors need the Node.js version pinned in `package.json` and the Vite+ launcher (`vp`). The
supported repository-based installation also uses the pinned Node.js version to verify signed release
locks.
