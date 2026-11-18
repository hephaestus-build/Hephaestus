---
"hephaestus": minor
---

An admin's MCP client can now try a practice before anyone relies on it. `save_practice_draft` saves a practice folder as a draft practice, or as a version beside a practice in use. `try_practice` runs it on one pull request, merge request or issue and waits up to 50 seconds. `get_practice_trial` reads a trial later, and `remove_practice_draft` deletes a draft. A trial always gives its status and outcome. It gives its problems and cited lines only if every person whose work it read chose **Cloud**. The trial tools need permission to change practices, and the consent page now says so.
