---
"hephaestus": minor
---

When an account or a workspace is erased, Hephaestus now tells instance admins about each product feedback issue on GitHub that it cannot fully remove. After it replaces an issue's text, the edit history still shows the erased text. If its cleanup has not finished after 30 days, a maintainer finishes the cleanup. Each such step sends the admin email and appears under **Needs action** on the **Product feedback** page, with the issue link. Select **Confirm done** after the erased text is gone from GitHub. Hephaestus keeps the issue's location until you confirm, and the audit log records the confirmation. Changes to the product feedback destinations are now recorded in the audit log too.
