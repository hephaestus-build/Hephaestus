#### 🔴 Stop every role before sealing the system secrets with their key identifier

The migration seals each login provider secret, AI provider key and JWT signing key again, named by its key and bound to its record. A previous server cannot read the new values, so no previous role may run beside the new release.

Keep `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` unchanged, and set no prior system key for this upgrade. The chart gives the system key to the migration Job. If the key is not the key that sealed the stored values, the migration stops and changes nothing: restore the correct key and run the upgrade again.

After the upgrade, you can rotate the system key and the signing key without a stop, as [Secret custody](https://docs.hephaestus.build/admin/secret-custody#system-key) describes.
