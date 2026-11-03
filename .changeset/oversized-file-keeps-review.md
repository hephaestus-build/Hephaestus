---
"hephaestus": patch
---

A pull request that changes a large text file, such as a lockfile over 2 MiB, now gets a review. Before, one such file stopped the capture, and no practice ran. The file is now named as not captured. Practices that need the whole change stop with their recorded reason, and the others still run. A finding can point into that file only through the diff it was given, not as a quote that Hephaestus can check. A large binary file no longer affects the review.
