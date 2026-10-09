---
"hephaestus": patch
---

Supported release rescans now check every authenticated image even when archived evidence or another image fails validation. Diagnostics distinguish a refused policy from an unavailable scan, and any failure still fails the overall check.

Release evidence now includes a signed checksum file that authenticates its SBOM, advisory and policy bytes. Older unsigned archives are reported as unauthenticated without trusting their evidence, while their authenticated images are still scanned against current policy.
