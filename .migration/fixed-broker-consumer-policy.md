#### 🔴 Remove broker consumer settings

Remove `config.durableConsumerName` and `config.consumerInactiveThreshold` from every values file. The chart refuses both keys, including their old defaults.

Provider events and replay now use the PostgreSQL inbox and its scope leases. Complete the migration entry *Drain the webhook streams before the upgrade* before starting the target server. There are no broker consumer names or acknowledgment positions to configure in the new release.
