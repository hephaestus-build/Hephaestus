# Migration fragments

Add `.migration/<changeset-slug>.md` only when an upgrade requires operator action. The file is the
complete migration-guide entry, begins with a `#### 🔴 …` heading, and has the same slug as the
changeset whose summary contains `**Operators:**`. Never edit `MIGRATION.md`'s `### Next release`
section. `vp run release:version` sorts fragments by filename, inserts them under a new version
heading below the unchanged `### Next release` anchor, and then removes them.

An upgrade page lists each fragment by itself, so do not edit the page for it.
Below the heading, give the two fields that the page shows in its entry table:

```md
#### 🔴 Check database connection limits for review recovery

- **When:** before the window
- **Action:** Render the target chart with your values. Set `server.databasePool.maximumPoolSize` to `auto`.
```

- **When** is `before the window`, `in the window` or `after the upgrade`.
  For an action in more than one of them, separate them with commas, in that order.
- **Action** is one line that says what the operator does.
  The rest of the fragment gives the details.
  Use absolute links, because the line appears in `MIGRATION.md` and on the page.

The page names the fragment's section: `Target` until a release takes the fragment.
A fragment that renames site values, Secret keys, settings or images also has a table with the columns `Where`, `Before` and `After`.
The page merges those rows into its values table.
The docs build checks these rules.

For changes to current instructions outside version history, see
[Current migration-guide instructions](https://docs.hephaestus.build/contributor/release-management#current-migration-guide-instructions).

If old and new runtime roles cannot use the database together, start the heading with `#### 🔴 Stop every role`.
The changelog compatibility guard and the upgrade test read that heading: see
[Expand and contract](https://docs.hephaestus.build/contributor/database-migration#expand-and-contract).
