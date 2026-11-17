---
"hephaestus": patch
---

Text from pull requests, merge requests, issues, comments, Slack threads and Outline documents now reaches the review and Hephaestus with each character that does not show written as `⟦U+XXXX⟧`. A quote in feedback can show such a marker where the original had an invisible character. Code, diffs and repository files keep their exact bytes. Once, after the upgrade, a result whose work holds such a character reads as changed work, because its source fingerprint follows the new rule: a review of that work that runs during the upgrade ends as replaced, and the next review of the work reviews it again.
