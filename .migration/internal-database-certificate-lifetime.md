#### 🔴 Refresh the internal PostgreSQL certificate

The standard one-host certificate manifest now requests a ten-year PostgreSQL server certificate, matching its CA.
After applying the updated platform manifest, wait for cert-manager to issue the replacement certificate.
Check the issued expiry with `kubectl --namespace hephaestus get certificate hephaestus-postgresql-tls -o jsonpath='{.status.notAfter}'`.
Then restart PostgreSQL with `kubectl --namespace hephaestus rollout restart statefulset/hephaestus-postgresql`.
Wait for PostgreSQL and the application roles to become ready before closing the maintenance window.
Follow [TLS certificates](https://docs.hephaestus.build/admin/secret-custody#tls-certificates) for certificate changes.
