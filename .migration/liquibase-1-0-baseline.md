#### 🔴 Install 1.0.0-rc.1 before you upgrade a database from 0.x

This release starts from a baseline with the schema of 1.0.0-rc.1, and no longer holds the 0.x migrations.

A database from before 1.0.0-rc.1 is refused before anything changes. Install 1.0.0-rc.1 with its [versioned upgrade instructions](https://github.com/hephaestus-build/Hephaestus/blob/v1.0.0-rc.1/docs/admin/upgrade-to-1-0.mdx), complete its upgrade, then upgrade to this release.

A database on 1.0.0-rc.1 needs no action. The migration compares its schema with 1.0.0-rc.1, records the baseline without changing the schema, and continues. If the schema differs, or if a later build ran on the database, the migration stops without a change and lists what it found. The current release can keep running: contact the maintainers with the list.

An installation that once ran the former intelligence service can still hold its tables: `checkpoints`, `checkpoint_blobs`, `checkpoint_writes`, `checkpoint_migrations`, `store` and `store_migrations`, and the function `text_to_lo(text)`. Hephaestus does not use them, and the migration stops and lists them. Back them up, drop them, and run the migration again. Connect as the owner of the Hephaestus database (here `hephaestus`):

```bash
pg_dump --format=custom --file=intelligence-leftovers.dump \
  --table=public.checkpoints --table=public.checkpoint_blobs --table=public.checkpoint_writes \
  --table=public.checkpoint_migrations --table=public.store --table=public.store_migrations hephaestus
psql --dbname=hephaestus --command='DROP TABLE IF EXISTS public.checkpoints, public.checkpoint_blobs, public.checkpoint_writes, public.checkpoint_migrations, public.store, public.store_migrations; DROP FUNCTION IF EXISTS public.text_to_lo(text);'
```

The tables hold old conversation memory, so keep the backup only as long as your retention rules allow.
