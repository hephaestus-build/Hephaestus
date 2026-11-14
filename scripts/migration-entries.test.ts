import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { describe, test } from "node:test";

import { migrationEntries, noteProblems } from "../docs/plugins/migration-entries.ts";

const repository = "https://github.com/o/r/blob/main";
const guide = [
	"# Migration Guide",
	"## Version History",
	"### Next release",
	"### v1.0.0-rc.7",
	"#### 🔴 Stop every role before the repair",
	"",
	"Some context first. **Operators:** Stop every server, then deploy.",
	"",
	"**Operators:** Stop every server before the migration. Then start the new release.",
	"### v1.0.0-rc.6",
	"#### Rename the sync settings",
	"",
	"Rename `config.monitoring` to `config.sync` in your site file.",
	"",
	"| Where | Before | After |",
	"| --- | --- | --- |",
	"| Site file | `config.monitoring` | `config.sync` |",
	"#### 🔴 Stop every role before the repair",
	"",
	"1. Stop every server.",
	"   Then migrate.",
	"#### Drain the streams",
	"",
	"Events now wait in PostgreSQL.",
	"",
	"**Operators:**",
	"",
	"1. Replay the dead letters that you keep.",
	"2. Stop the servers.",
].join("\n");

await describe("migrationEntries", async () => {
	await test("lists the guide's releases newest first, and each entry with its release and anchor", () => {
		const { versions, entries } = migrationEntries(guide, [], repository);
		assert.deepEqual(versions, ["1.0.0-rc.7", "1.0.0-rc.6"]);
		assert.deepEqual(
			entries.map(({ title, version, link }) => [title, version, link.replace(repository, "")]),
			[
				// GitHub drops the emoji and keeps its space, and numbers a repeated heading.
				[
					"Stop every role before the repair",
					"1.0.0-rc.7",
					"/MIGRATION.md#-stop-every-role-before-the-repair",
				],
				["Rename the sync settings", "1.0.0-rc.6", "/MIGRATION.md#rename-the-sync-settings"],
				[
					"Stop every role before the repair",
					"1.0.0-rc.6",
					"/MIGRATION.md#-stop-every-role-before-the-repair-1",
				],
				["Drain the streams", "1.0.0-rc.6", "/MIGRATION.md#drain-the-streams"],
			],
		);
	});

	await test("summarizes an entry by the first sentence of its Operators paragraph, else of its first paragraph", () => {
		const [repair, rename, numbered, drain] = migrationEntries(guide, [], repository).entries;
		assert.ok(repair && rename && numbered && drain);
		assert.equal(repair.summary, "Stop every server before the migration.");
		// A label on its own line is followed by the action.
		assert.equal(drain.summary, "Replay the dead letters that you keep.");
		assert.equal(rename.summary, "Rename `config.monitoring` to `config.sync` in your site file.");
		assert.equal(numbered.summary, "Stop every server.");
	});

	await test("reads the stopped marker and the rows of a values table", () => {
		const [repair, rename] = migrationEntries(guide, [], repository).entries;
		assert.ok(repair && rename);
		assert.equal(repair.stopped, true);
		assert.deepEqual(repair.rows, []);
		assert.equal(rename.stopped, false);
		assert.deepEqual(rename.rows, [["Site file", "`config.monitoring`", "`config.sync`"]]);
	});

	await test("appends each pending note, linked to its file, after the released entries", () => {
		const { entries } = migrationEntries(
			guide,
			[
				{ file: ".migration/b.md", source: "#### Do b\n\nDo b now." },
				{ file: ".migration/a.md", source: "#### 🔴 Do a\n\nDo a now." },
			],
			repository,
		);
		assert.deepEqual(
			entries.slice(-2).map(({ title, version, link }) => [title, version, link]),
			[
				["Do a", null, `${repository}/.migration/a.md`],
				["Do b", null, `${repository}/.migration/b.md`],
			],
		);
	});

	await test("refuses a note that breaks the rules", () => {
		assert.throws(
			() => migrationEntries("", [{ file: ".migration/a.md", source: "Do a now." }], repository),
			/\.migration\/a\.md:1: Start the migration note/u,
		);
	});
});

const note = (source: string) => noteProblems({ file: ".migration/a.md", source });

await describe("noteProblems", async () => {
	await test("accepts a note with a heading, an action and a values table", () => {
		assert.deepEqual(
			note(
				"#### Rename a key\n\nRename it.\n\n| Where | Before | After |\n| --- | --- | --- |\n| Secret | `A` | `B` |\n",
			),
			[],
		);
	});

	await test("asks for the heading, the action and the values table's columns", () => {
		assert.deepEqual(note("Rename it."), [
			'.migration/a.md:1: Start the migration note with a "#### " heading.',
		]);
		assert.deepEqual(note("#### Rename a key\n"), [
			".migration/a.md: say the operator's action in the first sentence.",
		]);
		assert.deepEqual(
			note("#### Rename a key\n\nRename it.\n\n| Where | Old | New |\n| --- | --- | --- |\n"),
			[".migration/a.md: the values table's columns must be Where, Before, After."],
		);
	});
});

await test("the migration guide and every pending note parse", () => {
	const root = new URL("../", import.meta.url);
	const notes = readdirSync(new URL(".migration/", root))
		.filter((name) => name.endsWith(".md") && name !== "README.md")
		.map((name) => ({
			file: `.migration/${name}`,
			source: readFileSync(new URL(`.migration/${name}`, root), "utf8"),
		}));
	const { versions, entries } = migrationEntries(
		readFileSync(new URL("MIGRATION.md", root), "utf8"),
		notes,
		repository,
	);
	assert.ok(versions.includes("1.0.0-rc.7"));
	assert.equal(entries.filter((entry) => entry.version === null).length, notes.length);
	assert.ok(entries.every((entry) => entry.title !== "" && entry.summary !== ""));
});
