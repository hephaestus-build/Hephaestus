---
---

The image build installs the `pack` CLI and every buildpack dependency, including the Liberica JRE, from pinned checksums through the CI tool cache and mirror. A slow or failed GitHub release download no longer stops the App Server image build.
