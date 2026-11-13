---
"hephaestus": patch
---

GitHub OAuth secret updates now point to the login-provider rotation procedure.
When you rotate a sign-in credential, update the existing login provider in Instance administration and mirror it in the Kubernetes Secret.
Where the provider permits more than one active secret, test a new sign-in before you revoke the old secret.
A server restart still seeds a missing login provider.
