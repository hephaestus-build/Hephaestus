---
---

Contributor CI only: a person starts every feedback eval panel run, by hand or by adding `eval:run` to a pull request from this repository, and each run composes the whole panel against the head at that moment. The panel no longer runs each night or on a push that changes the composer, and one run never cancels another. No shipped behavior changes and no operator action is needed.
