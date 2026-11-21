#### 🔴 Correct stored values that the server cannot read, if the upgrade stops at them

- **When:** in the window
- **Action:** If the upgrade stops at a table's stored rows, correct or delete those rows. Then start the upgrade again.

This upgrade adds a check to each column that stores a fixed set of values, such as a status, a kind or a role.
Writes to a table are checked as soon as the upgrade reaches that table.
Earlier upgrades corrected each such value that a release stored, so a stop most likely means that a row was changed by hand.

Before it checks the stored rows of a table, the upgrade counts the rows that hold an unknown value.
If there are any, it stops and names each column with its number of rows.
The tables before it are complete. The check of a table's stored rows is all or nothing.

List the rows that the stop names. In `psql`, with access to the application database, run:

```sql
SELECT format('SELECT %L AS check_name, t.* FROM %s t WHERE NOT %s', conname, conrelid::regclass,
              regexp_replace(pg_get_constraintdef(oid), '^CHECK (.*) NOT VALID$', '\1'))
FROM pg_constraint
WHERE contype = 'c' AND conname LIKE 'ck\_%' AND NOT convalidated
\gexec
```

Each result names the check and shows the rows that it refuses.
`SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '<check_name>'` shows the values that the check accepts.
Set each row's column to one of them, or delete the row.
Then start the upgrade again. It continues at the table where it stopped, and can stop at a later table in the same way.
