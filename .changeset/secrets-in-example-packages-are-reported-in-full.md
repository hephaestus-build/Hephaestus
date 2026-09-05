---
"hephaestus": patch
---

A hardcoded credential committed in a package named `example` or `sample` — the placeholder package
most JVM projects are generated with — is no longer read as sample code. Those words counted as a
directory of samples wherever they sat in a path, so a credential in ordinary application code was
reduced to a minor suggestion, and a review with other suggestions to make could leave it out of the
comment entirely. A credential in an `examples/` or `samples/` directory is still a minor suggestion.
