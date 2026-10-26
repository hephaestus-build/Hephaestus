---
"hephaestus": minor
---

The released Helm chart now names the release's images by digest, and the release signs the chart in the registry. To install or upgrade on Kubernetes, verify the chart digest with `cosign verify` (Cosign 3.1 or later), pull the chart by that digest, and run `helm upgrade --install` with a profile and your own values. You no longer need Node.js, a checkout of the repository, or a prepared values file.
