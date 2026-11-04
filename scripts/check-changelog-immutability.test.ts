import assert from "node:assert/strict";
import { test } from "node:test";

import { type ChangelogSnapshot, violations } from "./check-changelog-immutability.ts";

const directory = "server/application/src/main/resources/db/changelog/";
const master = "server/application/src/main/resources/db/master.xml";
const include = (file: string): string =>
	`<include file="./changelog/${file}" relativeToChangelogFile="true"/>`;

const automatic =
	'<includeAll path="./changelog/next" relativeToChangelogFile="true" errorIfMissingOrEmpty="false" endsWithFilter=".xml" maxDepth="1"/>';
const xml = (content: string): string => `<databaseChangeLog>${content}</databaseChangeLog>`;
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

const rc1File = `${directory}1793168406889_changelog.xml`;
const rc1Next = `${directory}next/20261101103000-erasure-history-redaction.xml`;
const laterNext = `${directory}next/20261102210500-issue-native-id-per-type.xml`;
const rc1 = (): ChangelogSnapshot => ({
	blobs: new Map([
		[master, "rc1-master-blob"],
		[`${directory}0000000000000_baseline_v0_77_4.xml`, "legacy-baseline"],
		[rc1File, "legacy-change"],
		[rc1Next, "rc1-head"],
	]),
	master: xml(
		include("0000000000000_baseline_v0_77_4.xml") +
			include("1793168406889_changelog.xml") +
			automatic,
	),
});
const legacy = (): ChangelogSnapshot => changed(rc1(), laterNext, "later");
const baselined = (): ChangelogSnapshot => ({
	blobs: new Map([
		[master, "baseline-master-blob"],
		[`${directory}1.0.0/baseline.xml`, "baseline"],
		[`${directory}1.0.0/baseline.sql`, "baseline-sql"],
		[laterNext, "later"],
	]),
	master: xml(include("1.0.0/baseline.xml") + automatic),
});
const check = (before: ChangelogSnapshot, after: ChangelogSnapshot) =>
	violations(before, after, rc1);

void test("the 1.0 baseline replaces the chain of its release and keeps every later file", () => {
	assert.deepEqual(check(legacy(), baselined()), []);
	// A file that merges while the baseline waits needs nothing from this rule.
	const later = `${directory}next/20261103000000-another.xml`;
	assert.deepEqual(check(changed(legacy(), later, "new"), changed(baselined(), later, "new")), []);
});

void test("the exemption expires when the replacement merges", () => {
	const base = baselined();
	assert.notDeepEqual(check(base, changed(base, `${directory}1.0.0/baseline.sql`, "other")), []);
	assert.notDeepEqual(check(base, changed(base, laterNext)), []);
	assert.notDeepEqual(
		check(base, { ...base, master: xml(include("1.0.0/other.xml") + automatic) }),
		[],
	);
});

void test("a replacement that does anything else gets no exemption", () => {
	const head = baselined();
	for (const [name, before, after] of [
		["keeps a file of the release", legacy(), changed(head, rc1File, "legacy-change")],
		["keeps a next file of the release", legacy(), changed(head, rc1Next, "rc1-head")],
		["edits a later file", legacy(), changed(head, laterNext, "edited")],
		["drops a later file", legacy(), changed(head, laterNext)],
		[
			"renames a later file",
			legacy(),
			changed(changed(head, laterNext), `${directory}next/20261102210501-renamed.xml`, "later"),
		],
		[
			"adds a next file with the replacement",
			legacy(),
			changed(head, `${directory}next/20261103000000-new.xml`, "new"),
		],
		["adds a file elsewhere", legacy(), changed(head, `${directory}1.0.1/baseline.xml`, "other")],
		["replaces an edited release file", changed(legacy(), rc1File, "edited"), head],
		["the base lost a release file", changed(legacy(), rc1File), head],
		[
			"keeps an include",
			legacy(),
			{ ...head, master: xml(include("1.0.0/baseline.xml") + include("old.xml") + automatic) },
		],
		[
			"filters the baseline",
			legacy(),
			{
				...head,
				master: xml(
					include("1.0.0/baseline.xml").replace("/>", ' contextFilter="dev"/>') + automatic,
				),
			},
		],
	] as const) {
		assert.notDeepEqual(check(before, after), [], name);
	}
	const noAutomatic = { ...head, master: xml(include("1.0.0/baseline.xml")) };
	assert.notDeepEqual(check(legacy(), noAutomatic), []);
});

void test("an unreadable release tag fails the replacement", () => {
	assert.throws(() =>
		violations(legacy(), baselined(), () => {
			throw new Error("no tag");
		}),
	);
});
