---
"hephaestus": patch
---

A review that reads the earlier feedback on its pull request or issue now holds one database connection instead of two, so many reviews composing at once no longer risk exhausting the connection pool. Feedback that another review delivers during that read still stops the new feedback as Public history changed.
