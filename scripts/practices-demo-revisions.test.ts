import assert from "node:assert/strict";
import { type TestContext, test } from "node:test";

import { PGlite } from "@electric-sql/pglite";

import { type Queryable, writeOrRewind } from "./lib/practices-demo-revisions.ts";

// The columns and foreign keys the rewind touches, as the baseline schema declares them. An
// observation keeps its row when its revision goes (`ON DELETE SET NULL`), so only the rewind's own
// check stops it from unpinning one. The practice's pointer must move before its revision goes.
const SCHEMA = `
	CREATE TABLE practice (id bigint PRIMARY KEY, workspace_id bigint NOT NULL, current_revision_id bigint);
	CREATE TABLE practice_revision (
		id bigint PRIMARY KEY,
		practice_id bigint NOT NULL REFERENCES practice (id),
		revision_number int NOT NULL,
		review_rule_fingerprint text,
		created_at timestamptz NOT NULL DEFAULT now(),
		criteria text NOT NULL
	);
	ALTER TABLE practice ADD FOREIGN KEY (current_revision_id) REFERENCES practice_revision (id);
	CREATE TABLE observation (
		id bigint PRIMARY KEY,
		practice_revision_id bigint REFERENCES practice_revision (id) ON DELETE SET NULL
	);
	INSERT INTO practice VALUES (1, 7, NULL);
	INSERT INTO practice_revision (id, practice_id, revision_number, review_rule_fingerprint, criteria)
		VALUES (10, 1, 1, 'before', 'one concern'), (11, 1, 2, 'after', 'one concern');
	UPDATE practice SET current_revision_id = 11 WHERE id = 1;
`;
const WORKSPACE = 7;
/** The revision the server appended for the seed; revision 10 was the practice's own. */
const APPENDED = [11];

async function database(t: TestContext): Promise<PGlite> {
	const db = new PGlite();
	t.after(async () => {
		await db.close();
	});
	await db.exec(SCHEMA);
	return db;
}

async function currentRevision(db: PGlite) {
	const { rows } = await db.query<{ id: number }>("SELECT current_revision_id AS id FROM practice");
	return rows[0]?.id;
}

async function revisions(db: PGlite) {
	const { rows } = await db.query<{ id: number }>("SELECT id FROM practice_revision ORDER BY id");
	return rows.map((row) => row.id);
}

async function pinnedBy(db: PGlite, observation: number) {
	const { rows } = await db.query<{ id: number | null }>(
		"SELECT practice_revision_id AS id FROM observation WHERE id = $1",
		[observation],
	);
	return rows[0]?.id;
}

void test("a seed that fails midway leaves none of its rows and rewinds the revision the server appended", async (t) => {
	const db = await database(t);
	const failure = new Error("the second job does not fit");

	await assert.rejects(
		writeOrRewind(db, WORKSPACE, APPENDED, async () => {
			await db.query("INSERT INTO observation VALUES (1, 11)");
			throw failure;
		}),
		(error) => error === failure,
	);

	assert.equal(await pinnedBy(db, 1), undefined);
	assert.equal(await currentRevision(db), 10);
	assert.deepEqual(await revisions(db), [10]);
});

void test("a revision that a review pinned is kept, and its observation stays pinned", async (t) => {
	const db = await database(t);
	await db.exec("INSERT INTO observation VALUES (2, 11)");

	await assert.rejects(
		writeOrRewind(db, WORKSPACE, APPENDED, async () => {
			throw new Error("fails");
		}),
		/fails/u,
	);

	assert.equal(await currentRevision(db), 11);
	assert.equal(await pinnedBy(db, 2), 11);
});

void test("a revision whose rules differ from the one before it is kept", async (t) => {
	const db = await database(t);
	await db.exec("UPDATE practice_revision SET criteria = 'two concerns' WHERE id = 11");

	await assert.rejects(
		writeOrRewind(db, WORKSPACE, APPENDED, async () => {
			throw new Error("fails");
		}),
		/fails/u,
	);

	assert.equal(await currentRevision(db), 11);
	assert.deepEqual(await revisions(db), [10, 11]);
});

void test("a seed that succeeds is committed and keeps the appended revision", async (t) => {
	const db = await database(t);

	await writeOrRewind(db, WORKSPACE, APPENDED, async () => {
		await db.query("INSERT INTO observation VALUES (3, 11)");
	});
	// One connection reads its own uncommitted rows, so a rollback is what shows the commit.
	await db.exec("ROLLBACK");

	assert.equal(await pinnedBy(db, 3), 11);
	assert.equal(await currentRevision(db), 11);
});

void test("a rewind that fails reports both failures and undoes its own steps", async (t) => {
	const db = await database(t);
	const lostConnection = new Error("connection lost");
	const failingDelete: Queryable = {
		query: async (text, values) => {
			if (text.startsWith("DELETE FROM practice_revision")) {
				throw lostConnection;
			}
			return db.query(text, values);
		},
	};
	const failure = new Error("the seed fails");

	await assert.rejects(
		writeOrRewind(failingDelete, WORKSPACE, APPENDED, async () => {
			throw failure;
		}),
		(error) => {
			assert.ok(error instanceof AggregateError);
			assert.deepEqual(error.errors, [failure, lostConnection]);
			return true;
		},
	);

	assert.equal(await currentRevision(db), 11);
	assert.deepEqual(await revisions(db), [10, 11]);
});
