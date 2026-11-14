#### 🔴 Stop every role before clearing personal logins from GitHub App installation records

1. Stop every server before the migration, then deploy the matching release.
   After the upgrade, a deleted workspace or an erasure clears a personal account's login from its adopted installation record.
   The previous server reads that login as always present, and it does not know the setup answer for a declined installation.
2. No other action is necessary. The migration only allows the login to be empty.
