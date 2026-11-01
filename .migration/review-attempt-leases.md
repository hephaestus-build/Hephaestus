#### 🔴 Stop every role before the review lease cutover

Stop all roles before the 1.0 upgrade. Apply the database migration before you start the new release.
Practice review attempts now use a lease ID for each attempt. The migration removes the worker ID from these rows.
A review that was running when you stopped the previous release gets a lease that no worker holds. When that lease expires, the review runs again.
Restore the verified pre-upgrade database backup before you start the previous release.
