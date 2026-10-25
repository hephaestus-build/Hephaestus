---
"hephaestus": patch
---

Issues and pull requests of a GitHub repository that took the name of a renamed repository no longer stop its sync. Before, events for the new repository could be stored under the renamed one, and the sync of the new repository then stopped at every start. The next sync moves such work to the right repository. Work that a workspace already tracks, or whose number the right repository already uses, stays where it is, is counted in the new `github.misfiled.work` metric, and the rest of the sync continues. New reviews and comments on such work are not stored until a maintainer moves it.
