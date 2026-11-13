#### 🔴 Stop every role before the provider resource repair upgrade

1. Stop every server before the migration and deploy the matching release afterward.
   The previous server cannot write the new required authorization binding columns.
2. Finish connection authorization flows before upgrading, or start them again afterward.
   The migration discards pending OAuth nonces so each new authorization binds an exact connection lifecycle.
3. Open **Instance administration → Provider removals** after the upgrade.
   Inspect unresolved resources, including registrations whose outcome is unknown and removals with no surviving workspace.
4. Confirm at the provider that the old resource and uncertain operation are gone before releasing its block.
   A timeout or a missing resource alone is not confirmation that an earlier DELETE cannot still finish.
   Pending removals and suspended hooks without a capture receipt stay uncertain across this upgrade.
5. For a connection with a surviving workspace, open its integration administration page to release the exact block.
   Only an instance admin can do this.
6. Keep the identifiers listed under **Security retention** in the personal-data map within your security-retention policy.
   Credential erasure deadlines do not extend to preserve cleanup access.

Replace `integration_provider_teardown_total` in custom dashboards with `integration_provider_repair_attempts_total` and `integration_provider_repair_open`.
Route `HephaestusProviderRemovalNeedsConfirmation` to an instance administrator.

While repairs remain unresolved, or after a provider release is audited, rollback refuses to remove that evidence. Keep the matching release and apply a forward fix.
