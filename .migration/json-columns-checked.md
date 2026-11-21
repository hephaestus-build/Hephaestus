#### 🔴 Correct stored JSON values that the server cannot read, if the upgrade stops at them

- **When:** in the window
- **Action:** If the upgrade stops at a table's stored JSON values, correct or delete those rows. Then start the upgrade again.

This upgrade adds a check to each JSON column that had none. The check accepts only the JSON type that the server writes there: an object, or an array where the server stores a list.
Writes to a table are checked as soon as the upgrade reaches that table.
The server has always written these types, so a stop most likely means that a row was changed by hand.

Before it checks the stored rows of a table, the upgrade counts the rows that hold another JSON type.
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
Set each row's column to a JSON value of the type that the check names. Where the column allows it, set it to NULL instead.

A trigger refuses most changes to the JSON columns of `agent_action`, so delete such an `agent_action` row instead.
Do not delete a `connection` row: that also deletes the rows that belong to the connection. Correct its `config` instead.
Then start the upgrade again. It continues at the table where it stopped, and can stop at a later table in the same way.
