#### 🔴 Move custom pgBackRest configuration

If you edited `docker/self-host/pgbackrest.conf`, move those edits to `deploy/postgresql/pgbackrest.conf` before upgrading.
The backup and restore overlays now mount that file.
The PITR drill uses the same configuration.
