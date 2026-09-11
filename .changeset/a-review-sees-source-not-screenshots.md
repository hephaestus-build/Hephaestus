---
"hephaestus": patch
---

A practice review of a repository that keeps images or other binary files alongside its code now sees
the code. The repository snapshot a review reads has a size bound, and binary files counted against it,
so on a repository with many images the bound could be used up before the snapshot reached a single
source file. Binary files are now left out of the snapshot and named in its capture record, and the
bound is spent on text alone.
