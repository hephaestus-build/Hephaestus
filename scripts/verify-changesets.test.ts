import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import { localReleaseNoteProblems } from "./release-note-rules.ts";
import {
	removedChartValues,
	verifyChangesets,
	verifyLocalChangesets,
	verifyChangesetMigration,
	verifyMigrationFragment,
	verifyRemovedChartValues,
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

const chartSchema = (gitlab: Record<string, unknown>, top: Record<string, unknown> = {}) => ({
	definitions: { flag: { type: "boolean" } },
	properties: {
		config: {
			type: "object",
			properties: {
				gitlab: { type: "object", properties: gitlab },
			},
		},
		...top,
	},
});
const flag = { $ref: "#/definitions/flag" };
const listSchema = (fields: Record<string, unknown>) => ({
	properties: { route: { type: "array", items: { properties: fields } } },
});
const branchSchema = (fields: Record<string, unknown>) => ({
	properties: { auth: { properties: { mode: flag }, oneOf: [{ properties: fields }] } },
});

void test("names a removed chart value once, at the top of what the schema lost", () => {
	const base = chartSchema(
		{ enabled: flag, workspaceCreation: flag },
		{ route: { type: "string" } },
	);
	assert.deepEqual(removedChartValues(base, base), []);
	assert.deepEqual(removedChartValues(base, chartSchema({ enabled: flag }, base.properties)), []);
	assert.deepEqual(
		removedChartValues(base, chartSchema({ enabled: flag }, { route: { type: "string" } })),
		["config.gitlab.workspaceCreation"],
	);
	assert.deepEqual(removedChartValues(base, { properties: { route: { type: "string" } } }), [
		"config",
	]);
	// A list's items and a conditional branch hold names that an operator sets, too.
	assert.deepEqual(
		removedChartValues(listSchema({ name: flag, section: flag }), listSchema({ name: flag })),
		["route[].section"],
	);
	assert.deepEqual(removedChartValues(listSchema({ name: flag }), { properties: {} }), ["route"]);
	assert.deepEqual(removedChartValues(branchSchema({ issuer: flag }), branchSchema({})), [
		"auth.issuer",
	]);
	// A group that only loses its condition keeps every name.
	assert.deepEqual(
		removedChartValues(
			{ properties: { auth: { properties: { mode: flag }, if: { required: ["mode"] } } } },
			{ properties: { auth: { properties: { mode: flag } } } },
		),
		[],
	);
});

void test("a removed chart value needs a note that names it in backticks", () => {
	const base = chartSchema({ enabled: flag, workspaceCreation: flag });
	const head = chartSchema({ enabled: flag });
	const note = "Remove `config.gitlab.workspaceCreation` before you upgrade.";
	assert.doesNotThrow(() => verifyRemovedChartValues(base, head, note));
	assert.doesNotThrow(() => verifyRemovedChartValues(base, base, ""));
	assert.doesNotThrow(
		() => verifyRemovedChartValues(head, base, ""),
		"an added value needs no note",
	);
	for (const notes of ["", "Remove config.gitlab.workspaceCreation.", "Remove `config.gitlab`."]) {
		assert.throws(
			() => verifyRemovedChartValues(base, head, notes),
			/no longer accepts config\.gitlab\.workspaceCreation/u,
		);
	}
});

void test("the pull request check compares the chart's schema with the merge base", async (t) => {
	const source = await readFile(".github/workflows/verify-changesets.yml", "utf8");
	assert.match(
		source,
		/if ! git diff --quiet "\$BASE_SHA"\.\.\.HEAD -- deploy\/chart\/hephaestus\/values\.schema\.json; then\n\s+node scripts\/verify-changesets\.ts --removed-chart-values "\$BASE_SHA" "\$\{changesets_changed\[@\]\}" "\$\{migration_changed\[@\]\}"/u,
	);

	const root = await mkdtemp(path.join(tmpdir(), "removed-chart-value-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	const environment = environmentForGitFixture();
	const git = (...args: string[]) =>
		execFileSync("git", args, { cwd: root, env: environment, stdio: "pipe" });
	const schemaFile = path.join(root, "deploy/chart/hephaestus/values.schema.json");
	await mkdir(path.dirname(schemaFile), { recursive: true });
	await writeFile(schemaFile, JSON.stringify(chartSchema({ enabled: flag, gone: flag })));
	git("init", "--quiet");
	git("add", ".");
	git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "--quiet", "-m", "base");
	const base = git("rev-parse", "HEAD").toString().trim();
	await writeFile(schemaFile, JSON.stringify(chartSchema({ enabled: flag })));
	const note = path.join(root, "note.md");
	const script = fileURLToPath(new URL("verify-changesets.ts", import.meta.url));
	const check = (...files: string[]) =>
		execFileSync(process.execPath, [script, "--removed-chart-values", base, ...files], {
			cwd: root,
			env: environment,
			stdio: "pipe",
		});
	assert.throws(() => check(), /no longer accepts config\.gitlab\.gone/u);
	await writeFile(note, "Remove `config.gitlab.gone`.\n");
	check(note);
});

void test("the release-note rules hold a branch to what CI requires, against origin/main", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "release-note-rules-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	const env = environmentForGitFixture();
	const git = (...args: string[]) =>
		execFileSync("git", args, { cwd: root, env, encoding: "utf8" }).trim();
	const write = async (file: string, content: string) => {
		await mkdir(path.dirname(path.join(root, file)), { recursive: true });
		await writeFile(path.join(root, file), content);
	};
	const problems = () => localReleaseNoteProblems(root).join("\n");
	git("init", "--quiet", "--initial-branch=main");
	git("config", "user.name", "Test");
	git("config", "user.email", "test@example.invalid");
	await write("MIGRATION.md", "# Migration\n");
	await write(".changeset/pending.md", "---\n---\n\nCI only. No shipped application changes.\n");
	await write(".migration/pending.md", "#### Pending\n");
	await write("webapp/src/a.ts", "export const a = 1;\n");
	git("add", ".");
	git("commit", "--quiet", "-m", "base");
	git("update-ref", "refs/remotes/origin/main", "HEAD");
	git("checkout", "--quiet", "-b", "feature");
	const attempt = async (change: () => Promise<void> | void, expected: RegExp) => {
		await change();
		git("add", "--all");
		git("commit", "--quiet", "-m", "attempt");
		assert.match(problems(), expected);
		git("reset", "--quiet", "--hard", "origin/main");
	};

	// What #3787 hit in CI after its local checks passed: a deleted pending changeset ...
	await attempt(() => {
		git("rm", "--quiet", ".changeset/pending.md");
	}, /Pending changesets may be edited .* not deleted or renamed: \.changeset\/pending\.md/u);
	// ... and shipped code whose pull request only edits a changeset that main already has.
	await attempt(async () => {
		await write("webapp/src/a.ts", "export const a = 2;\n");
		await write(
			".changeset/pending.md",
			"---\n---\n\nCI only. No shipped application changes, rewritten.\n",
		);
	}, /changes shipped code but carries no changeset: webapp\/src\/a\.ts/u);
	await attempt(async () => write("MIGRATION.md", "# Edited\n"), /Do not edit MIGRATION\.md/u);
	await attempt(() => {
		git("rm", "--quiet", ".migration/pending.md");
	}, /Pending migration fragments may be edited, but not deleted or renamed/u);

	await write("webapp/src/a.ts", "export const a = 2;\n");
	await write(".changeset/feature.md", '---\n"hephaestus": patch\n---\n\nThe value is two.\n');
	git("add", ".");
	git("commit", "--quiet", "-m", "with its note");
	assert.equal(problems(), "");
});
