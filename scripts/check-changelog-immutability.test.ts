import assert from "node:assert/strict";
import { test } from "node:test";

import { type ChangelogSnapshot, violations } from "./check-changelog-immutability.ts";

const directory = "server/application/src/main/resources/db/changelog/";
const master = "server/application/src/main/resources/db/master.xml";
const include = (file: string): string =>
	`<include file="./changelog/${file}" relativeToChangelogFile="true"/>`;

const xml = (content: string): string => `<databaseChangeLog>${content}</databaseChangeLog>`;

function original(): ChangelogSnapshot {
	return {
		blobs: new Map([
			[master, "released-master-blob"],
			[`${directory}old.xml`, "old-blob"],
		]),
		master: xml(include("old.xml")),
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

void test("accepts unchanged history and appended migrations", () => {
	assert.deepEqual(violations(original(), original()), []);
	assert.deepEqual(
		violations(original(), {
			...original(),
			master: xml(`${include("old.xml")}\n${include("next.xml")}`),
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
			master: xml(`<include relativeToChangelogFile="true" file="./changelog/old.xml"/>`),
		}),
		[],
	);
});

void test("malformed XML and the wrong root fail closed", () => {
	for (const content of ["<databaseChangeLog>", "<other/>"]) {
		assert.notDeepEqual(violations(original(), { ...original(), master: content }), []);
	}
});
