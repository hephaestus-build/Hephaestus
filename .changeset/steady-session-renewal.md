---
"hephaestus": patch
---

Stay signed in across breaks with a default 24-hour renewable cookie and a fixed 7-day sign-in limit. Sensitive actions still require a recent sign-in. Tabs coordinate session renewal, and temporary renewal failures no longer redirect you to sign-in. Explicit operator timeout overrides remain unchanged.

Cookie-authenticated actions now consistently require CSRF protection, including when a bearer header is also present.

Failed sign-out now reports an error instead of appearing successful. Impersonation changes coordinate with session renewal, and revoked sessions no longer prevent the sign-in page from listing providers.

Sessions that end during renewal now return an authentication refusal instead of reporting success.
