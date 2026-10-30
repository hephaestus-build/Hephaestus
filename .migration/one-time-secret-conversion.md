#### 🔴 Convert stored secrets before you start the new server

Back up the database and encryption keys. Stop all database writers, then run the database migration with the existing `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` and `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY`. Keep the active credential key version. Configure a key only if it is missing. Keep the prior credential key and its version configured if stored credentials still use them.

The migration encrypts plaintext login-provider and LLM connection secrets. It seals active and inactive JWT private keys without changing their signing identity. It authenticates integration credentials with no key version against the configured active, prior and legacy system keys, then encrypts them with the active credential key and records its version.

If a key is missing or cannot decrypt a stored credential, the conversion stops and rolls back. Keep writers stopped, correct the key configuration, and retry. Start the server only after the migration succeeds. The new server cannot read or write plaintext secrets.
