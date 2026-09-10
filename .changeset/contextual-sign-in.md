---
"hephaestus": minor
---

Signing in no longer takes you off the page you were reading: public pages open a sign-in dialog you can dismiss, and shared links and reloads still get the full sign-in page. Every sign-in and setup screen links to the instance's privacy notice and imprint, which had no footer to reach them from before. The sign-in page also loads when the server is slow to answer whether you are already signed in.

First-time setup is now one short page. Heph introduces itself and marks off each decision as you make it, and the five paragraphs of notice text became three plain points — use Hephaestus lawfully, treat its feedback as advisory, and read the privacy notice for who runs your instance and what it stores. Accepting the terms and answering the research question stay separate decisions, both visible before either is answered, neither preselected, and nothing is recorded until you press **Continue**. You can change the research answer later in user settings.

The setup screen no longer carries operator-specific text, so it reads correctly on any deployment rather than only on the one it was written for. Because the wording changed, everyone accepts it once more.

**Operators:** the optional research question is now asked only where `HEPHAESTUS_RESEARCH_ORGANIZATION` names the organisation running the study, and that name is shown beside the choice and in account settings. Set it if you run one; leave it unset and setup is the terms alone. Configure `/imprint` and `/privacy` before upgrading — the setup screen now points at them for everything operator-specific. No data is dropped: `consent_decision.notice_sha256` only loses its `NOT NULL`, `research_organization` is added beside it, and each decision identifies its wording by notice version. Changing the organisation later asks everyone the research question again, and leaves terms acceptance alone.
