import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
	verifyChangesets,
	verifyLocalChangesets,
	verifyChangesetMigration,
	verifyMigrationFragment,
} from "./verify-changesets.ts";

const status = (releases: unknown[], summary: string) => ({
	changesets: [{ id: "note", releases, summary }],
});
const rootRelease = (type = "patch") => [{ name: "hephaestus", type }];
const firstStable = (summary: string) => ({
	changesets: [{ id: "release-1-0-0", releases: rootRelease("major"), summary }],
});

void test("accepts root releases and explained opt-outs", () => {
	assert.doesNotThrow(() =>
		verifyChangesets(status(rootRelease(), "Fixes sign-in."), [".changeset/note.md"]),
	);
	assert.doesNotThrow(() =>
		verifyChangesets(status([], "No release note: CI only."), [".changeset/note.md"]),
	);
	assert.doesNotThrow(() =>
		verifyChangesets(
			status(rootRelease("major"), "Stable API. **Operators:** follow the migration guide."),
			[".changeset/note.md"],
			false,
		),
	);
});

void test("accepts the one pre-1.0 major that cuts 1.0.0, under the same rules as any major", () => {
	assert.doesNotThrow(() =>
		verifyChangesets(firstStable("Hephaestus 1.0. **Operators:** follow the 1.0 upgrade guide."), [
			".changeset/release-1-0-0.md",
		]),
	);
	assert.throws(
		() => verifyChangesets(firstStable("Hephaestus 1.0."), [".changeset/release-1-0-0.md"]),
		/must contain \*\*Operators:/u,
	);
});

void test("rejects blank notes, non-root releases, and pre-1.0 majors", () => {
	assert.throws(() => verifyChangesets(status([], ""), [".changeset/note.md"]), /must explain/u);
	assert.throws(
		() =>
			verifyChangesets(status([{ name: "webapp", type: "patch" }], "Fix."), [".changeset/note.md"]),
		/must release only/u,
	);
	assert.throws(
		() =>
			verifyChangesets(status([...rootRelease(), ...rootRelease()], "Fix."), [
				".changeset/note.md",
			]),
		/must release only/u,
	);
	assert.throws(
		() => verifyChangesets(status(rootRelease("major"), "Breaks API."), [".changeset/note.md"]),
		/pre-1.0 major/u,
	);
	assert.throws(
		() =>
			verifyChangesets(status(rootRelease("major"), "Breaks API."), [".changeset/note.md"], false),
		/must contain \*\*Operators:/u,
	);
});

void test("rejects files Changesets did not parse and release-note trailers", () => {
	assert.throws(
		() => verifyChangesets({ changesets: [] }, [".changeset/note.md"]),
		/did not parse/u,
	);
	assert.throws(
		() =>
			verifyChangesets(status(rootRelease(), "Fix.\n\nCo-authored-by: Bot"), [
				".changeset/note.md",
			]),
		/must not contain Co-authored-by/u,
	);
});

void test("migration fragments require an operator marker and one entry", () => {
	assert.doesNotThrow(() =>
		verifyMigrationFragment(
			".migration/note.md",
			"Change. **Operators:** act before upgrading.",
			"#### 🔴 Do the thing\n\n**Migration**: act.\n",
		),
	);
	assert.doesNotThrow(() =>
		verifyMigrationFragment(
			".migration/note.md",
			"**Operators:** act.",
			"#### 🔴 Pin the image\n\n```bash\n# either: remove the line entirely\n#### not a heading here\nVERSION=1\n```\n",
		),
	);
	assert.throws(
		() => verifyMigrationFragment(".migration/note.md", "Change.", "#### 🔴 Act\n"),
		/must contain \*\*Operators:/u,
	);
	assert.throws(
		() =>
			verifyMigrationFragment(
				".migration/note.md",
				"**Operators:** act.",
				"### Next release\n\n#### 🔴 Act\n",
			),
		/no level 1-3 headings/u,
	);
	assert.throws(
		() =>
			verifyMigrationFragment(
				".migration/note.md",
				"**Operators:** act.",
				"#### 🔴 First\n\n#### 🔴 Second\n",
			),
		/exactly one/u,
	);
	assert.throws(
		() => verifyChangesetMigration(".changeset/note.md", "**Operators:** act.", undefined),
		/requires \.migration\/note\.md/u,
	);
	assert.throws(
		() => verifyChangesetMigration(".changeset/note.md", "No action.", "#### 🔴 Act\n"),
		/must contain \*\*Operators:/u,
	);
	assert.doesNotThrow(() =>
		verifyChangesetMigration(".changeset/note.md", "No action.", undefined),
	);
});

void test("local content check reads real notes without a Git repository", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "local-changesets-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	await mkdir(path.join(root, ".changeset", "pre"), { recursive: true });
	await writeFile(path.join(root, "package.json"), JSON.stringify({ version: "0.91.0" }));
	await writeFile(path.join(root, ".changeset", "README.md"), "Not a changeset.");
	await verifyLocalChangesets(root);
	await writeFile(path.join(root, ".changeset", "note.md"), "---\n---\n");
	await assert.rejects(verifyLocalChangesets(root), /must explain/u);
	await writeFile(
		path.join(root, ".changeset", "note.md"),
		"---\n---\n\nCI only. No shipped application changes.\n",
	);
	await writeFile(
		path.join(root, ".changeset", "pre", "note.md"),
		'---\n"hephaestus": patch\n---\n\nSign-in works again.\n',
	);
	await verifyLocalChangesets(root);
	const script = fileURLToPath(new URL("verify-changesets.ts", import.meta.url));
	execFileSync(process.execPath, [script, "--local"], { cwd: root, stdio: "pipe" });
	await writeFile(path.join(root, ".changeset", "new.md"), "---\n---\n");
	assert.throws(() =>
		execFileSync(process.execPath, [script, "--local"], { cwd: root, stdio: "pipe" }),
	);
	await rm(path.join(root, ".changeset", "new.md"));
	await verifyLocalChangesets(root);
});

void test("local files retain the existing release and migration refusals", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "local-note-policy-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	await mkdir(path.join(root, ".changeset"));
	await writeFile(path.join(root, "package.json"), JSON.stringify({ version: "0.91.0" }));
	const note = path.join(root, ".changeset", "note.md");
	for (const [source, expected] of [
		["No frontmatter.", /missing or invalid frontmatter/u],
		['---\n"webapp": patch\n---\n\nFix.\n', /must release only/u],
		['---\n"hephaestus": major\n---\n\nBreaks API.\n', /pre-1.0 major/u],
		[
			'---\n"hephaestus": minor\n---\n\n**Operators:** change the setting.\n',
			/requires \.migration/u,
		],
	] as const) {
		await writeFile(note, source);
		await assert.rejects(verifyLocalChangesets(root), expected);
	}
	await mkdir(path.join(root, ".migration"));
	await writeFile(
		path.join(root, ".migration", "note.md"),
		"#### 🔴 Update the setting\n\nSet the new value before upgrading.\n",
	);
	await verifyLocalChangesets(root);
});

const migrationGuide = (candidate: string) =>
	`# Migration guide\n\n### Next release\n\n### v1.0.0-rc.1\n\n${candidate}### v0.95.1\n\n#### 🔴 Older entry\n\nDone.\n`;

void test("a changeset consumed in pre mode needs its entry in the candidate's migration section", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "consumed-prerelease-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	await mkdir(path.join(root, ".changeset", "pre"), { recursive: true });
	await writeFile(path.join(root, "package.json"), JSON.stringify({ version: "1.0.0-rc.1" }));
	await writeFile(
		path.join(root, ".changeset", "pre", "lease.md"),
		'---\n"hephaestus": minor\n---\n\nAttempts use leases.\n\n**Operators:** stop every role first.\n',
	);
	await writeFile(
		path.join(root, "MIGRATION.md"),
		migrationGuide("#### 🔴 Stop every role before the lease cutover\n\nStop all roles.\n\n"),
	);
	await verifyLocalChangesets(root);
	// The 0.95.1 entry belongs to another release and does not count for the candidate.
	await writeFile(path.join(root, "MIGRATION.md"), migrationGuide("Nothing to do.\n\n"));
	await assert.rejects(
		verifyLocalChangesets(root),
		/\.changeset\/pre\/lease\.md: \*\*Operators:\*\* was consumed in pre mode, but MIGRATION\.md's 1\.0\.0 candidate sections hold 0 #### 🔴 entries for 1/u,
	);
	// A pending fragment still satisfies the rule, as on main before the Version PR.
	await mkdir(path.join(root, ".migration"));
	await writeFile(
		path.join(root, ".migration", "lease.md"),
		"#### 🔴 Stop every role before the lease cutover\n\nStop all roles.\n",
	);
	await verifyLocalChangesets(root);
});
