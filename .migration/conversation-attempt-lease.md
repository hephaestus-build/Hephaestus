#### 🔴 Stop every role before the conversation lease cutover

Stop all roles before the 1.0 upgrade. Apply the database migration before you start the new release.
Conversation turns now use a lease ID for each attempt. The migration removes the worker ID from these rows.
Old attempt credentials cannot resume an answer. Restore the verified pre-upgrade database backup before you start the previous release.
