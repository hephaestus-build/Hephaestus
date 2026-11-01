---
"hephaestus": minor
---

Each release now publishes one signed chart lock, `release-vX.Y.Z.chart.json`, that names the chart digest, every image digest and the source commit.

**Operators:** Move to the Helm installation on k3s or Kubernetes before you upgrade. If your own tooling reads release assets, switch it to `release-vX.Y.Z.chart.json` and its signature bundle: the image-only `release-vX.Y.Z.json` lock is no longer published. The release verification tools no longer check releases older than 0.75.0.
