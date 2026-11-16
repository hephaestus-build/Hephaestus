import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { describe, test } from "node:test";

import { migrationEntries, noteProblems, sectionFor } from "./migration-entries.ts";

const repository = "https://github.com/o/r/blob/main";
const fields = "- **When:** before the window\n- **Action:** Do it now.\n";
const guide = [
	"# Migration Guide",
	"## Version History",
	"### Next release",
	"### v1.0.0-rc.7",
	"#### 🔴 Stop every role before the repair",
	"",
	"- **When:** in the window, after the upgrade",
	"- **Action:** Stop every server before the migration. Confirm each removal after it.",
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

	await test("reads the When and Action fields and the rows of a values table", () => {
		const [repair, rename] = migrationEntries(guide, [], repository).entries;
		assert.ok(repair && rename);
		assert.deepEqual(repair.when, ["in the window", "after the upgrade"]);
		assert.equal(
			repair.action,
			"Stop every server before the migration. Confirm each removal after it.",
		);
		assert.deepEqual(repair.rows, []);
		// An entry released before the fields existed has none.
		assert.equal(rename.when, null);
		assert.equal(rename.action, null);
		assert.deepEqual(rename.rows, [["Site file", "`config.monitoring`", "`config.sync`"]]);
	});

	await test("appends each pending note, linked to its file, after the released entries", () => {
		const { entries } = migrationEntries(
			guide,
			[
				{ file: ".migration/b.md", source: `#### Do b\n\n${fields}` },
				{ file: ".migration/a.md", source: `#### 🔴 Do a\n\n${fields}` },
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

	await test("ignores a `#` line inside a fenced code block", () => {
		const fenced = [
			"### v1.0.0",
			"#### Stop the service",
			"",
			"```sh",
			"# Stop the service",
			"systemctl stop hephaestus",
			"```",
			"#### Start the service",
		].join("\n");
		assert.deepEqual(
			migrationEntries(fenced, [], repository).entries.map(({ title, version }) => [
				title,
				version,
			]),
			[
				["Stop the service", "1.0.0"],
				["Start the service", "1.0.0"],
			],
		);
	});

	await test("keeps a fence open on a line that only starts like a closing fence", () => {
		const fenced = [
			"### v1.0.0",
			"#### Stop the service",
			"",
			"```sh",
			"```sh",
			"# Stop the service",
			"```",
			"#### Start the service",
		].join("\n");
		assert.deepEqual(
			migrationEntries(fenced, [], repository).entries.map(({ title }) => title),
			["Stop the service", "Start the service"],
		);
	});

	await test("keeps an escaped pipe inside a values cell", () => {
		const [entry] = migrationEntries(
			"### v1.0.0\n#### Rename\n\n| Where | Before | After |\n| --- | --- | --- |\n| Shell | `a \\| b` | `c` |",
			[],
			repository,
		).entries;
		assert.deepEqual(entry?.rows, [["Shell", "`a | b`", "`c`"]]);
	});

	await test("refuses a note that breaks the rules", () => {
		assert.throws(
			() => migrationEntries("", [{ file: ".migration/a.md", source: "Do a now." }], repository),
			/\.migration\/a\.md:1: Start the migration note/u,
		);
	});
});

const note = (source: string) => noteProblems({ file: ".migration/a.md", source });
const when = (value: string) => note(`#### Do it\n\n- **When:** ${value}\n- **Action:** Do it.\n`);

await describe("noteProblems", async () => {
	await test("accepts a note with a heading, its fields and a values table", () => {
		assert.deepEqual(
			note(
				`#### Rename a key\n\n${fields}\n| Where | Before | After |\n| --- | --- | --- |\n| Secret | \`A\` | \`B\` |\n`,
			),
			[],
		);
	});

	await test("asks for the heading, each field once and the values table's columns", () => {
		assert.deepEqual(note("Rename it."), [
			'.migration/a.md:1: Start the migration note with a "#### " heading.',
		]);
		assert.deepEqual(note("#### Rename a key\n\nRename it.\n"), [
			'.migration/a.md: give one non-empty "- **When:** …" line.',
			'.migration/a.md: give one non-empty "- **Action:** …" line.',
		]);
		assert.deepEqual(note(`#### Rename a key\n\n${fields}- **Action:** Again.\n`), [
			'.migration/a.md: give one non-empty "- **Action:** …" line.',
		]);
		assert.deepEqual(
			note(`#### Rename a key\n\n${fields}\n| Where | Old | New |\n| --- | --- | --- |\n`),
			[".migration/a.md: the values table's columns must be Where, Before, After."],
		);
		// An unescaped pipe in a cell adds a fourth cell, which would shift the Before and After values.
		assert.deepEqual(
			note(
				`#### Rename a key\n\n${fields}\n| Where | Before | After |\n| --- | --- | --- |\n| Shell | \`a | b\` | \`c\` |\n`,
			),
			[
				'.migration/a.md: the values table row "| Shell | `a | b` | `c` |" needs 3 cells; write a pipe inside a cell as \\|.',
			],
		);
		// A second entry heading would become a released entry without its fields; a fenced one is an example.
		assert.deepEqual(note(`#### Rename a key\n\n${fields}\n#### Another entry\n\nText.\n`), [
			".migration/a.md: split the note into one note per entry: its body holds a heading of level 4 or above.",
		]);
		assert.deepEqual(
			note(`#### Rename a key\n\n${fields}\n\`\`\`md\n#### An example\n\`\`\`\n##### A detail\n`),
			[],
		);
		// A separator with fewer cells than the header is no table on GitHub either.
		assert.deepEqual(
			note(
				`#### Rename a key\n\n${fields}\n| Where | Before | After |\n| --- | --- |\n| Secret | \`A\` | \`B\` |\n`,
			),
			[".migration/a.md: the values table needs a `| --- | --- | --- |` row under its header."],
		);
		// A fenced example of a field is literal text, not a second field.
		assert.deepEqual(
			note(
				`#### Rename a key\n\n${fields}\nFor example:\n\n\`\`\`md\n- **Action:** Example.\n\`\`\`\n`,
			),
			[],
		);
		// The page renders the Action line as raw links, which no link check reads.
		assert.deepEqual(
			note(
				"#### Rename a key\n\n- **When:** before the window\n- **Action:** Follow [the guide](../admin/upgrade.mdx).\n",
			),
			['.migration/a.md: use absolute https links in the "- **Action:**" line.'],
		);
		// Without the separator row, Markdown renders no table, so the first row would vanish.
		assert.deepEqual(
			note(
				`#### Rename a key\n\n${fields}\n| Where | Before | After |\n| Secret | \`A\` | \`B\` |\n`,
			),
			[".migration/a.md: the values table needs a `| --- | --- | --- |` row under its header."],
		);
	});

	await test("accepts only the known moments of an upgrade, in upgrade order", () => {
		assert.deepEqual(when("Before the window, after the upgrade"), []);
		const problem = [
			'.migration/a.md: list "before the window", "in the window", "after the upgrade" in "**When:**", in that order, separated by commas.',
		];
		assert.deepEqual(when("after the upgrade, before the window"), problem);
		assert.deepEqual(when("during the window"), problem);
		assert.deepEqual(when("in the window, in the window"), problem);
	});
});

await test("the migration guide and every pending note parse", () => {
	const root = new URL("../../../", import.meta.url);
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
	// Each upgrade page names the last release that it lists by hand. Before and after the Version PR folds a burned
	// release number, that name must stand for a section of the guide.
	const pages = readdirSync(new URL("docs/admin/", root)).filter((name) => name.endsWith(".mdx"));
	const afters = pages.flatMap((name) =>
		[
			...readFileSync(new URL(`docs/admin/${name}`, root), "utf8").matchAll(
				/<MigrationEntries after="(?<after>[^"]+)"/gu,
			),
		].flatMap((match) => (match.groups?.after === undefined ? [] : [match.groups.after])),
	);
	assert.ok(afters.length > 0);
	for (const after of afters) {
		assert.ok(versions.includes(sectionFor(versions, after)));
	}
	assert.equal(entries.filter((entry) => entry.version === null).length, notes.length);
	assert.ok(entries.every((entry) => entry.title !== ""));
	assert.ok(
		entries.every(
			(entry) => entry.version !== null || (entry.when !== null && entry.action !== null),
		),
	);
});

await describe("sectionFor", async () => {
	// Newest first, as `migrationEntries` lists them.
	const unfolded = ["1.0.0-rc.7", "1.0.0-rc.6", "1.0.0-rc.5"];
	const folded = ["1.0.0-rc.9", "1.0.0-rc.8", "1.0.0-rc.5"];

	await test("a version with its own section stands for itself", () => {
		assert.equal(sectionFor(unfolded, "1.0.0-rc.7"), "1.0.0-rc.7");
	});

	await test("a burned version stands for the published release that the Version PR folded it into", () => {
		assert.equal(sectionFor(folded, "1.0.0-rc.7"), "1.0.0-rc.8");
		assert.equal(sectionFor(folded, "1.0.0-rc.6"), "1.0.0-rc.8");
	});

	await test("a version outside the guide's history is an error", () => {
		assert.throws(() => sectionFor(folded, "1.0.0-rc.77"), /no section for 1\.0\.0-rc\.77/u);
		assert.throws(() => sectionFor(folded, "0.1.0"), /no section for 0\.1\.0/u);
		assert.throws(() => sectionFor(folded, "rc.7"), /no section for rc\.7/u);
		assert.throws(() => sectionFor(folded, "1.0.0-rc.10"), /no section for 1\.0\.0-rc\.10/u);
	});

	await test("a release sorts after its own release candidates", () => {
		assert.equal(sectionFor(["1.0.0", "1.0.0-rc.8"], "1.0.0-rc.9"), "1.0.0");
	});
});
