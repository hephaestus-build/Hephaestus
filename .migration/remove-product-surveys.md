#### 🔴 Stop every role and export survey results before upgrading

Before you upgrade, open **Instance administration → Product feedback → Surveys** in the current release. Open each survey’s results and download its CSV export. Store the exports under your retention and access rules for personal data.

Stop all roles before the upgrade. The database migration permanently deletes survey definitions, answers, declines, invitation records, survey notification subscriptions, and pending survey events. There is no in-place rollback. Recovery requires a verified pre-upgrade database backup and the previous release.

Product feedback submissions and the private admin inbox remain available. Research consent and practice research remain available. People must confirm their consent choices again because the consent wording no longer lists survey answers. Research use stays off for each participant until they confirm again. Plan the upgrade around running studies.

Pre-upgrade backups and CSV exports still contain survey answers. Keep them under your retention and access rules until they expire.
