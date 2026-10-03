---
"hephaestus": minor
---

Instance administrators can now answer a person's access or erasure request under **Instance
administration → Person data**, without database access. Find the person by account ID or by exact
provider user IDs from GitHub, GitLab, Slack or Outline, including people who never signed in.
Names, logins and email addresses are never used to match.

The preview counts the person's rows in every store and lists feedback still posted on a provider, so
you can remove it there first. **Download JSON export** returns one file with the person's
observations, feedback and delivery state, Heph conversations, activity, memberships, profile,
preferences and collected Slack messages. It holds no credentials and no other person's feedback or
profile. Erasure uses exactly the previewed rows, records per-store counts and the acting
administrator but no erased content, and can resume after a failure.

After erasure, Hephaestus does not review, give feedback on, record activity for or chat with the
erased identities again, even when provider sync mirrors their upstream records. Observations and
feedback about other developers stay with them. Hidden Heph memory in the person's workspaces is
reset; visible conversations stay. Git history is not rewritten: the export lists repositories whose
history may contain the person's commits, and the repository owner removes authorship at the source.

**Operators:** Heph's hidden conversation memory resets once on upgrade. Visible messages, titles and
times stay. Users may need to repeat earlier context to Heph.
