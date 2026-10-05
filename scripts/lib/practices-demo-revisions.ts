/** The part of a database client the rewind uses; `pg`'s `Client` and an in-process Postgres both fit. */
export interface Queryable {
	query: (text: string, values?: unknown[]) => Promise<{ rows: Record<string, unknown>[] }>;
}

/**
 * Rewinds the practice revisions that the server appended for the seed. A revision goes back only
 * while it is current, differs from the one before it in its fingerprint alone, and no observation
 * pins it: the next review would append the same revision again.
 */
export async function rewindRevisions(
	client: Queryable,
	workspaceId: number,
	ids: number[],
): Promise<void> {
	const rewound = await client.query(
		`WITH appended AS (
			SELECT p.id AS practice_id, cur.id AS current_id, prev.id AS previous_id
			FROM practice p
			JOIN practice_revision cur ON cur.id = p.current_revision_id
			JOIN practice_revision prev ON prev.practice_id = p.id
			 AND prev.revision_number = cur.revision_number - 1
			WHERE p.workspace_id = $1
			  AND cur.id = ANY($2::bigint[])
			  AND to_jsonb(cur) - $3::text[] = to_jsonb(prev) - $3::text[]
			  AND NOT EXISTS (SELECT 1 FROM observation o WHERE o.practice_revision_id = cur.id)
		)
		UPDATE practice p SET current_revision_id = appended.previous_id
		FROM appended WHERE p.id = appended.practice_id
		RETURNING appended.current_id AS id`,
		[workspaceId, ids, ["id", "revision_number", "review_rule_fingerprint", "created_at"]],
	);
	await client.query("DELETE FROM practice_revision WHERE id = ANY($1::bigint[])", [
		rewound.rows.map((row) => row.id),
	]);
}

/**
 * Runs `write` in a transaction and commits it. If `write` fails, the transaction rolls back, and
 * so do the revisions the server appended for the seed, since the first job that records them
 * rolled back too. Rethrows the failure, joined by the rewind's if that failed.
 */
export async function writeOrRewind<Result>(
	client: Queryable,
	workspaceId: number,
	appendedIds: number[],
	write: () => Promise<Result>,
): Promise<Result> {
	await client.query("BEGIN");
	let result: Result;
	try {
		result = await write();
	} catch (error) {
		await client.query("ROLLBACK");
		try {
			await client.query("BEGIN");
			await rewindRevisions(client, workspaceId, appendedIds);
			await client.query("COMMIT");
		} catch (rewindError) {
			await client.query("ROLLBACK").catch(() => undefined);
			throw new AggregateError(
				[error, rewindError],
				"The seed failed, and so did the rewind of the revisions the server appended for it",
				{ cause: rewindError },
			);
		}
		throw error;
	}
	await client.query("COMMIT");
	return result;
}
