---
"hephaestus": patch
---

GitLab inline review discussions now keep their resolved or reopened state in practice reviews and in conversations with Heph, including discussions Hephaestus first saw through a comment webhook, and an older read of a merge request's discussions no longer overwrites a newer one. GitLab documents an event when all discussions on a merge request become resolved, and Hephaestus picks that up right away; other resolution changes appear the next time Hephaestus reads the merge request's discussions.
