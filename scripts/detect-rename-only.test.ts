import assert from "node:assert/strict";
import test from "node:test";

import { renameOnly } from "./detect-rename-only.ts";
import { NEXT_CHANGELOG_DIRECTORY } from "./lib/changelog.ts";

const next = (name: string) => `${NEXT_CHANGELOG_DIRECTORY}/${name}`;
const OLD = next("20261117100000-add-trial.xml");
const NEW = next("20261118020000-add-trial.xml");
const ENTITY = "server/application/src/main/java/Trial.java";
const NOTE = ".migration/add-trial.md";
const blobs = new Map([
	["m1", "<databaseChangeLog/>"],
	["m2", "<databaseChangeLog/> "],
	["e1", "class Trial {}"],
	["e2", "class Trial { int x; }"],
	["n1", "Applies 20261117100000-add-trial.xml before the release."],
	["n2", "Applies 20261118020000-add-trial.xml before the release."],
	["n3", "Applies 20261118020000-add-trial.xml before the next release."],
]);
const read = (blob: string) => {
	const text = blobs.get(blob);
	assert.ok(text !== undefined, blob);
	return text;
};
const before = new Map([
	[OLD, "m1"],
	[ENTITY, "e1"],
]);

void test("a migration moved to a later name with the same bytes is a rename-only head", () => {
	const verdict = renameOnly(
		before,
		new Map([
			[NEW, "m1"],
			[ENTITY, "e1"],
		]),
		read,
	);
	assert.equal(verdict.renameOnly, true, verdict.reason);
	assert.deepEqual(verdict.renames, [{ from: OLD, to: NEW }]);
});

void test("one changed byte in the moved migration runs the full tier", () => {
	const verdict = renameOnly(
		before,
		new Map([
			[NEW, "m2"],
			[ENTITY, "e1"],
		]),
		read,
	);
	assert.equal(verdict.renameOnly, false);
});

void test("an extra changed file beside the rename runs the full tier", () => {
	const verdict = renameOnly(
		before,
		new Map([
			[NEW, "m1"],
			[ENTITY, "e2"],
		]),
		read,
	);
	assert.equal(verdict.renameOnly, false);
	assert.match(verdict.reason, /Trial\.java/u);
});

void test("an extra added file beside the rename runs the full tier", () => {
	const verdict = renameOnly(
		before,
		new Map([
			[NEW, "m1"],
			[ENTITY, "e1"],
			["docs/new.md", "e1"],
		]),
		read,
	);
	assert.equal(verdict.renameOnly, false);
});

void test("a file that changes only by the renamed filename follows the rename", () => {
	const withNote = new Map([...before, [NOTE, "n1"]]);
	assert.equal(
		renameOnly(
			withNote,
			new Map([
				[NEW, "m1"],
				[ENTITY, "e1"],
				[NOTE, "n2"],
			]),
			read,
		).renameOnly,
		true,
	);
	const edited = renameOnly(
		withNote,
		new Map([
			[NEW, "m1"],
			[ENTITY, "e1"],
			[NOTE, "n3"],
		]),
		read,
	);
	assert.equal(edited.renameOnly, false);
	assert.match(edited.reason, /beyond the renamed filenames/u);
});

void test("a move to an earlier name, a new slug or an edit in place runs the full tier", () => {
	const earlier = next("20261116000000-add-trial.xml");
	const otherSlug = next("20261118020000-add-trials.xml");
	for (const after of [
		new Map([
			[earlier, "m1"],
			[ENTITY, "e1"],
		]),
		new Map([
			[otherSlug, "m1"],
			[ENTITY, "e1"],
		]),
		new Map([
			[OLD, "m2"],
			[ENTITY, "e1"],
		]),
	]) {
		assert.equal(renameOnly(before, after, read).renameOnly, false);
	}
});

void test("an unchanged own diff is not a rename", () => {
	assert.equal(renameOnly(before, new Map(before), read).renameOnly, false);
});
