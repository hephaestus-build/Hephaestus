---
"hephaestus": patch
---

Release vulnerability exceptions now apply only to the exact image they were reviewed for. A reviewed claim that vulnerable Go code is absent can carry over to a rebuilt image only when its Go binaries are byte-for-byte unchanged. Such a claim is still reviewed again when the advisory changes or the exception expires. Verifying an earlier release fails if its stored result relied on an exception for a different image. Release SBOMs now also record a SHA-256 for every file in the image.
