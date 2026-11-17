import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import {
	type ChangelogSnapshot,
	recoveryProblems,
	violations,
} from "./check-changelog-immutability.ts";

const directory = "server/application/src/main/resources/db/changelog/";
const master = "server/application/src/main/resources/db/master.xml";
const include = (file: string): string =>
	`<include file="./changelog/${file}" relativeToChangelogFile="true"/>`;

const automatic =
	'<includeAll path="./changelog/next" relativeToChangelogFile="true" errorIfMissingOrEmpty="false" endsWithFilter=".xml" maxDepth="1"/>';
const xml = (content: string): string => `<databaseChangeLog>${content}</databaseChangeLog>`;
const readEmpty = (): string => xml("");
const current = (): ChangelogSnapshot => ({
	...original(),
	master: xml(include("old.xml") + automatic),
});

function original(): ChangelogSnapshot {
	return {
		blobs: new Map([
			[master, "released-master-blob"],
			[`${directory}old.xml`, "old-blob"],
		]),
		master: xml(include("old.xml")),
		read: readEmpty,
	};
}

function changed(snapshot: ChangelogSnapshot, path: string, blob?: string): ChangelogSnapshot {
	const blobs = new Map(snapshot.blobs);
	if (blob === undefined) {
		blobs.delete(path);
	} else {
		blobs.set(path, blob);
	}
	return { ...snapshot, blobs };
}

void test("preserves the released prefix and adds timestamped files through includeAll", () => {
	assert.deepEqual(violations(original(), current()), []);
	assert.deepEqual(
		violations(
			current(),
			changed(current(), `${directory}next/20261031003000-add-index.xml`, "new"),
		),
		[],
	);
});

void test("rejects malformed, nested, legacy and out-of-order new migration filenames", () => {
	const before = changed(current(), `${directory}next/20261031003000-existing.xml`, "existing");
	for (const file of [
		"next/plain.xml",
		"next/20260230000000-invalid-date.xml",
		"next/nested/20261031004000-new.xml",
		"1793394316000_changelog.xml",
		"next/20261031002900-older.xml",
	]) {
		assert.notDeepEqual(violations(before, changed(before, directory + file, "new")), [], file);
	}
	assert.deepEqual(
		violations(before, changed(before, `${directory}next/20261031003100-new.xml`, "new")),
		[],
	);
});

void test("refuses a second automatic directory and explicit includes after the prefix", () => {
	assert.notDeepEqual(
		violations(original(), {
			...current(),
			master: xml(include("old.xml") + automatic + automatic),
		}),
		[],
	);
	assert.notDeepEqual(
		violations(original(), {
			...current(),
			master: xml(include("old.xml") + include("new.xml") + automatic),
		}),
		[],
	);
});

void test("rejects ordinary edits, deletion, and reordering of released migrations", () => {
	assert.notDeepEqual(
		violations(original(), changed(original(), `${directory}old.xml`, "modified")),
		[],
	);
	assert.notDeepEqual(violations(original(), changed(original(), `${directory}old.xml`)), []);
	assert.notDeepEqual(
		violations(original(), {
			...original(),
			master: xml(`${include("next.xml")}${include("old.xml")}`),
		}),
		[],
	);
});

void test("commenting out an include cannot retire migrations or remove the baseline", () => {
	const commented = { ...original(), master: xml(`<!-- ${include("old.xml")} -->`) };
	assert.notDeepEqual(violations(original(), commented), []);
});

void test("include attributes remain immutable but their XML ordering is irrelevant", () => {
	for (const attribute of ['contextFilter="dev"', 'ignore="true"', 'labels="skip"']) {
		const altered = xml(include("old.xml").replace("/>", ` ${attribute}/>`));
		assert.notDeepEqual(violations(original(), { ...original(), master: altered }), []);
	}
	assert.deepEqual(
		violations(original(), {
			...original(),
			master: xml(
				`<include relativeToChangelogFile="true" file="./changelog/old.xml"/>${automatic}`,
			),
		}),
		[],
	);
});

void test("malformed XML and the wrong root fail closed", () => {
	for (const content of ["<databaseChangeLog>", "<other/>"]) {
		assert.notDeepEqual(violations(original(), { ...original(), master: content }), []);
	}
});

const changeSet = (body: string): string =>
	xml(`<changeSet id="1794580948952-1" author="hephaestus">${body}</changeSet>`);
const stop = "<rollback><stop>Restore the backup taken before the upgrade.</stop></rollback>";

void test("a new change set with raw SQL or a custom change states its rollback", () => {
	const file = `${directory}next/20261115000000-new.xml`;
	const added = (content: string) =>
		violations(current(), { ...changed(current(), file, "new"), read: () => content });
	for (const raw of [
		"<sql>CREATE INDEX i ON t (c);</sql>",
		'<sql splitStatements="false"><![CDATA[UPDATE t SET c = 1;]]></sql>',
		'<sqlFile path="x.sql"/>',
		'<customChange class="x.Backfill"/>',
	]) {
		assert.match(added(changeSet(raw)).join("\n"), /without a rollback/u, raw);
		assert.match(added(changeSet(`${raw}<rollback/>`)).join("\n"), /without a rollback/u, raw);
		assert.match(
			added(changeSet(`${raw}<rollback><stop/></rollback>`)).join("\n"),
			/without a rollback/u,
			raw,
		);
		assert.deepEqual(added(changeSet(`${raw}${stop}`)), [], raw);
		assert.deepEqual(
			added(
				changeSet(`${raw}<rollback><stop message="Restore the pre-upgrade backup."/></rollback>`),
			),
			[],
			raw,
		);
	}
	assert.deepEqual(added(changeSet('<createIndex tableName="t" indexName="i"/>')), []);
	assert.deepEqual(
		added(changeSet("<sql>CREATE INDEX i ON t (c);</sql><rollback>DROP INDEX i;</rollback>")),
		[],
	);
	assert.deepEqual(
		added(
			changeSet(
				'<sql>CREATE INDEX i ON t (c);</sql><rollback changeSetId="1794580948952-0" changeSetAuthor="hephaestus"/>',
			),
		),
		[],
	);
});

void test("the guard refuses the role-notice change sets that reached main without a rollback", () => {
	const file = `${directory}next/20261114013648-harvest-role-change-notifications.xml`;
	assert.deepEqual(
		recoveryProblems(file, readFileSync(file, "utf8")).map((problem) => problem.split(":")[0]),
		["-1", "-2", "-3"].map((id) => `${file} change set 1794580948952${id}`),
	);
});
