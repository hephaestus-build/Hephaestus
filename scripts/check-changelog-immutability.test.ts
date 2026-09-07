import assert from "node:assert/strict";
import { test } from "node:test";

import { type ChangelogSnapshot, violations } from "./check-changelog-immutability.ts";

const directory = "server/application/src/main/resources/db/changelog/";
const master = "server/application/src/main/resources/db/master.xml";
const archive = "docs/db/archive/v0.77.4/";
const releasedMasterBlob = "5ac453b04033d85484ebf4c85d7f39e11581afc5";
const baseline = "0000000000000_baseline_v0_77_4.xml";
const include = (file: string): string =>
	`<include file="./changelog/${file}" relativeToChangelogFile="true"/>`;

function original(): ChangelogSnapshot {
	return {
		blobs: new Map([
			[master, releasedMasterBlob],
			[`${directory}old.xml`, "old-blob"],
		]),
		master: include("old.xml"),
	};
}

function squashed(): ChangelogSnapshot {
	return {
		blobs: new Map([
			[master, "baseline-master-blob"],
			[`${archive}archive-master.xml`, releasedMasterBlob],
			[`${archive}changelog/old.xml`, "old-blob"],
			[`${directory}${baseline}`, "baseline-blob"],
			[`${directory}baseline.sql`, "sql-blob"],
		]),
		master: include(baseline),
	};
}

function changed(snapshot: ChangelogSnapshot, path: string, blob?: string): ChangelogSnapshot {
	const blobs = new Map(snapshot.blobs);
	if (blob === undefined) blobs.delete(path);
	else blobs.set(path, blob);
	return { ...snapshot, blobs };
}

void test("accepts unchanged history and appended migrations", () => {
	assert.deepEqual(violations(original(), original()), []);
	assert.deepEqual(
		violations(squashed(), {
			...squashed(),
			master: `${include(baseline)}\n${include("next.xml")}`,
		}),
		[],
	);
});

void test("permits the pinned baseline transition with byte-identical archived history", () => {
	assert.deepEqual(violations(original(), squashed()), []);
});

void test("rejects missing or modified archive copies and a different release cut-point", () => {
	for (const path of [`${archive}archive-master.xml`, `${archive}changelog/old.xml`]) {
		assert.notDeepEqual(violations(original(), changed(squashed(), path)), []);
		assert.notDeepEqual(violations(original(), changed(squashed(), path, "modified")), []);
	}
	assert.notDeepEqual(violations(changed(original(), master, "another-release"), squashed()), []);
});

void test("rejects incomplete retirement and unapproved replacement includes", () => {
	assert.notDeepEqual(
		violations(original(), changed(squashed(), `${directory}old.xml`, "old-blob")),
		[],
	);
	for (const xml of [
		include("different.xml"),
		`${include(baseline)}${include("next.xml")}`,
		`${include(baseline)}<includeAll path="extra"/>`,
	])
		assert.notDeepEqual(violations(original(), { ...squashed(), master: xml }), []);
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
			master: `${include("next.xml")}${include("old.xml")}`,
		}),
		[],
	);
});

void test("protects the baseline SQL, XML, and archive after the transition", () => {
	for (const path of [
		`${directory}${baseline}`,
		`${directory}baseline.sql`,
		`${archive}archive-master.xml`,
		`${archive}changelog/old.xml`,
	]) {
		assert.notDeepEqual(violations(squashed(), changed(squashed(), path, "modified")), []);
		assert.notDeepEqual(violations(squashed(), changed(squashed(), path)), []);
	}
});
