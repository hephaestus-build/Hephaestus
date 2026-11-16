---
"hephaestus": minor
---

Issues on a second GitLab instance, or on a GitHub Enterprise Server beside github.com, now keep their own issue types. Before, the second instance's sync overwrote the first instance's issue types, and the second instance's issues showed no type.

**Operators:** Stop every role before this upgrade, as **Stop every role before issue types become unique per organization** describes. The previous server cannot read the new issue type keys.
