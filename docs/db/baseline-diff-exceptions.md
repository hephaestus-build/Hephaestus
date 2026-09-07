# Baseline equivalence

The v0.77.4 baseline describes the application-managed schema. Its reference is the full archived
994-changeset chain in `archive/v0.77.4/`. `LiquibaseBaselineIntegrationTest` requires an empty
Liquibase diff for tables, columns, keys, indexes, sequences and views in both `dev` and `prod`.
There are no accepted structural differences between those two initialization paths.

Native PostgreSQL comparisons additionally cover functions, trigger definitions and enabled states,
check/not-null/exclusion constraints, index expressions, predicates, validity and readiness, and partition
keys. They disregard only these representation details:

- **Database names:** isolated test databases have different catalog names.
- **Not-null constraint names and physical column numbers:** historical renames and dropped columns
  leave names and attribute numbers that a fresh table does not inherit. Compare the constrained
  column names and definitions instead.
- **Literal-array casts:** PostgreSQL can represent the same constant varchar array as either
  individually text-cast elements or a text-cast array. The test normalizes only those literal forms;
  it does not remove index predicates, arbitrary casts, or constraint expressions.

## Production capture audit

The baseline was derived from the supplied production schema, restored into the repository's
PostgreSQL 18 + pg_partman image. Liquibase 5.0.4 `generateChangeLog` was run as an independent
inspection aid. The committed baseline uses Liquibase `sqlFile` for PostgreSQL-native DDL rather
than losing functions, deferred triggers, partial indexes or partition definitions in XML conversion.

The following production-only objects are outside the application baseline:

| Objects | Treatment |
| --- | --- |
| `checkpoint_blobs`, `checkpoint_migrations`, `checkpoint_writes`, `checkpoints`, `store`, `store_migrations` and their indexes | Not created by the archived application chain or represented by the current persistence model; omitted from fresh initialization. |
| `public.text_to_lo(text)` | Not created by the archived chain or referenced by current server code; omitted from fresh initialization. |
| `auth_event_p*`, `auth_event_default`, `partman.template_public_auth_event` | Generated through `partman.create_parent`, not frozen to the capture date. |
| `databasechangelog`, `databasechangeloglock` | Owned by Liquibase, not baseline DDL. |

Synchronization does not delete or modify these existing objects. Removing unrelated production
objects is not part of this migration. Extension-owned definitions remain owned by their extensions.

The schema captures and accompanying history exports establish the audited schema and migration
cut-point. They contain neither business rows nor `partman.part_config` data and do not prove data
preservation. Automated upgrade and backup/restore tests use synthetic data; operators must still
rehearse with a full environment backup as required by the
[deployment runbook](../admin/liquibase-baseline-runbook.md). The immutable consent notice is copied
from its historical changeset; singleton settings and identity providers are initialized separately
from schema DDL.

Capture fingerprints (SHA-256) bind the audit to the supplied files without publishing the captures:

| Capture | SHA-256 |
| --- | --- |
| Production schema | `522a1ecb1a46ca4903dc380a2efbc417b03198107824fdfaf79f4ea06a7141ec` |
| Production history | `5504bd80ec6e1302fe7a9642fc9b3af9d71fc96d0019df47448a4d8038466f69` |
| Staging schema | `a6cbfbb761a34fdd8105f68fa17e816f7c0a5db073ed46d6d750478d4e0d167b` |
| Staging history | `6576fb27177400781790af599ef2ddf274b83c39039ce9918b8888672f838321` |

The source cut-point is v0.77.4, ending at `1788679885460-1` by `hephaestus` in
`db/changelog/1788679885460_changelog.xml`. Both supplied histories contain every current changeset.
