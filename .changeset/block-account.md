---
"hephaestus": minor
---

Instance admins can now block an account and unblock it again under **Instance administration → Users**. Blocking signs the person out everywhere at once and refuses every later sign-in, so signing in again through GitHub, GitLab or another linked provider no longer undoes it. The person sees that an admin blocked the account. Unblocking lets them sign in again and sends them a security notice by email when the account has a verified address. You cannot block yourself or the last active instance admin. Both actions need a recent sign-in and appear in the audit log as **Account blocked or unblocked**.

The `auth.token.refresh.result` counter uses `result="inactive"` for an account that cannot sign in, instead of `result="suspended"`. Update custom metric queries that use the old value.
