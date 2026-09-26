---
"hephaestus": patch
---

GitLab access tokens are now only validated against, and workspaces only created on, GitLab
instances your administrator configured. The workspace wizard lists the instances you have linked
your account on, and the new workspace belongs to your account on that instance. When GitLab does
not return your groups, the wizard now says so instead of showing an empty list.

**Operators:** keep one enabled GitLab login provider per GitLab instance. A second one for the same
instance is now refused, and an existing pair blocks GitLab workspace creation on that instance until
one is disabled.
