---
"hephaestus": patch
---

A maintainer's `/publish` on a private product feedback issue now goes out through the same outbox as filing, in order with the report's other GitHub writes. When GitHub's answer to the new public issue is lost, Hephaestus looks for that issue before it creates one again, so a report is never published twice. A pause still holds the command until filing resumes. Resuming a partial publication keeps its existing comment on the private issue.
