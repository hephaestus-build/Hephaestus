# Migration fragments

Add `.migration/<changeset-slug>.md` only when an upgrade requires operator action. The file is the
complete migration-guide entry, begins with a `#### 🔴 …` heading, and has the same slug as the
changeset whose summary contains `**Operators:**`. Never edit `MIGRATION.md`'s `### Next release`
section. `vp run release:version` sorts fragments by filename, inserts them under a new version
heading below the unchanged `### Next release` anchor, and then removes them.

For changes to current instructions outside version history, see
[Current migration-guide instructions](https://docs.hephaestus.build/contributor/release-management#current-migration-guide-instructions).
