import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { migrationGuideSections, renderMigrationGuide } from "./lib/migration-guide.ts";
import { output } from "./lib/process.ts";

const script = path.join(import.meta.dirname, "sync-release-version.ts");
const syncReleaseVersion = async (cwd: string) => output(process.execPath, [script], { cwd });
const template = await readFile(new URL("templates/migration-guide.md", import.meta.url), "utf8");
const guide = (history: string) => template.replace("<!-- VERSION_HISTORY -->\n\n", () => history);

void test("refreshes current instructions without changing a byte of real release history", async () => {
	const original = await readFile(new URL("../MIGRATION.md", import.meta.url), "utf8");
	const rendered = renderMigrationGuide(original);
	const before = migrationGuideSections(original);
	const after = migrationGuideSections(rendered);
	const originalStart = before.find((section) => section.heading === "### Next release");
	const originalEnd = before.find(
		(section) => section.heading === "## Automatic vs Manual Migrations",
	);
	const start = after.find((section) => section.heading === "### Next release");
	const end = after.find((section) => section.heading === "## Automatic vs Manual Migrations");
	assert.ok(originalStart && originalEnd && start && end);
	assert.equal(
		rendered.slice(start.start, end.start),
		original.slice(originalStart.start, originalEnd.start),
	);
	const current = rendered.slice(0, start.start) + rendered.slice(end.start);
	assert.doesNotMatch(
		current,
		/(?:github\.com\/(?:repos\/)?ls1intum\/[Hh]ephaestus|ls1intum\.github\.io\/Hephaestus)/u,
	);
	assert.ok(!current.includes("<!-- VERSION_HISTORY -->"));
	for (const url of [
		"https://api.github.com/repos/hephaestus-build/Hephaestus/releases/latest",
		...["releases", "issues", "discussions"].map(
			(page) => `https://github.com/hephaestus-build/Hephaestus/${page}`,
		),
		...["compatibility-policy", "production-setup", "backup-restore"].map(
			(page) => `https://docs.hephaestus.build/admin/${page}`,
		),
	]) {
		assert.ok(current.includes(url), `missing current reference: ${url}`);
	}
	assert.equal(renderMigrationGuide(rendered), rendered);
});

void test("refreshes an empty history without inventing a release, including on regeneration", async (t) => {
	const history = "### Next release\n\n";
	const original = `Old instructions\n\n${history}## Automatic vs Manual Migrations\nOld help\n`;
	const cwd = await mkdtemp(path.join(tmpdir(), "migration-guide-"));
	t.after(async () => {
		await rm(cwd, { recursive: true, force: true });
	});
	await mkdir(path.join(cwd, "docs/admin"), { recursive: true });
	await writeFile(path.join(cwd, "package.json"), '{"version":"99.0.0"}');
	await writeFile(path.join(cwd, "README.md"), "VERSION=0.0.0 # the release you are installing\n");
	await writeFile(
		path.join(cwd, "docs/admin/install.mdx"),
		"VERSION=0.0.0 # the release you are installing\n",
	);
	await writeFile(path.join(cwd, "MIGRATION.md"), original);
	await syncReleaseVersion(cwd);
	assert.equal(await readFile(path.join(cwd, "MIGRATION.md"), "utf8"), guide(history));
	await syncReleaseVersion(cwd);
	assert.equal(await readFile(path.join(cwd, "MIGRATION.md"), "utf8"), guide(history));
});

void test("preserves heading-shaped code and historical identities literally", () => {
	const history =
		"### Next release\n\n### v0.74.0\n\n```md\n### Next release\n## Automatic vs Manual Migrations\n```\n\nghcr.io/ls1intum/hephaestus/agent-pi:0.74.0\nhttps://github.com/ls1intum/Hephaestus/.github/workflows/release.yml@refs/heads/main\n";
	assert.equal(renderMigrationGuide(guide(history)), guide(history));
});

void test("rejects missing, duplicate, or reversed history boundaries instead of dropping content", () => {
	const start = "### Next release\n";
	const end = "## Automatic vs Manual Migrations\n";
	for (const input of ["", start, end, end + start, start + start + end, start + end + end]) {
		assert.throws(() => renderMigrationGuide(input), /expected exactly one/u);
	}
});

void test("synchronizes every release-owned version reference", async (t) => {
	const cwd = await mkdtemp(path.join(tmpdir(), "sync-release-version-"));
	t.after(async () => {
		await rm(cwd, { recursive: true, force: true });
	});
	await mkdir(path.join(cwd, "docs/admin"), { recursive: true });
	await mkdir(path.join(cwd, ".migration"));
	await writeFile(path.join(cwd, "package.json"), '{"version":"0.75.0"}');
	await writeFile(
		path.join(cwd, "docs/admin/install.mdx"),
		"VERSION=0.74.0 # the release you are installing\n",
	);
	await writeFile(
		path.join(cwd, "README.md"),
		"```bash\n  VERSION=0.74.0 # the release you are installing\n```\n",
	);
	await writeFile(
		path.join(cwd, "MIGRATION.md"),
		guide(
			"### Next release\n\n#### 🔴 Existing action\n\nDo it.\n\n### v0.76.0\n\n#### 🔴 Newer unreleased action\n\nFold me.\n\n### v0.75.0\n\n#### 🔴 Same-version unreleased action\n\nFold me too.\n\n### v0.74.0\n\nOld.\n",
		),
	);
	await writeFile(
		path.join(cwd, ".migration/z-last.md"),
		"#### 🔴 Z action\n\nDo Z: keep `$$VAR`, `$&`, and `$'` literal.\n",
	);
	await writeFile(
		path.join(cwd, ".migration/a-first.md"),
		"#### 🔴 A action\n\nDo A.\n\n```md\n### Next release\n## Automatic vs Manual Migrations\n### v0.75.0\n```\n",
	);
	await writeFile(path.join(cwd, ".migration/README.md"), "instructions\n");

	await syncReleaseVersion(cwd);

	assert.equal(
		await readFile(path.join(cwd, "docs/admin/install.mdx"), "utf8"),
		"VERSION=0.75.0 # the release you are installing\n",
	);
	assert.equal(
		await readFile(path.join(cwd, "README.md"), "utf8"),
		"```bash\n  VERSION=0.75.0 # the release you are installing\n```\n",
	);
	const stamped = guide(
		"### Next release\n\n### v0.75.0\n\n#### 🔴 Existing action\n\nDo it.\n\n#### 🔴 Newer unreleased action\n\nFold me.\n\n#### 🔴 Same-version unreleased action\n\nFold me too.\n\n#### 🔴 A action\n\nDo A.\n\n```md\n### Next release\n## Automatic vs Manual Migrations\n### v0.75.0\n```\n\n#### 🔴 Z action\n\nDo Z: keep `$$VAR`, `$&`, and `$'` literal.\n\n### v0.74.0\n\nOld.\n",
	);
	assert.equal(await readFile(path.join(cwd, "MIGRATION.md"), "utf8"), stamped);
	assert.deepEqual(await readdir(path.join(cwd, ".migration")), ["README.md"]);

	await syncReleaseVersion(cwd);
	assert.equal(await readFile(path.join(cwd, "MIGRATION.md"), "utf8"), stamped);
	assert.deepEqual(await readdir(path.join(cwd, ".migration")), ["README.md"]);

	await writeFile(path.join(cwd, ".migration/late.md"), "#### 🔴 Late action\n");
	await syncReleaseVersion(cwd);
	assert.equal(
		await readFile(path.join(cwd, "MIGRATION.md"), "utf8"),
		stamped.replace("\n### v0.74.0", "\n#### 🔴 Late action\n\n### v0.74.0"),
	);
	assert.deepEqual(await readdir(path.join(cwd, ".migration")), ["README.md"]);

	// A same-version heading below released history is a real conflict, not prior output.
	const guideWithGhost = await readFile(path.join(cwd, "MIGRATION.md"), "utf8");
	await writeFile(
		path.join(cwd, "MIGRATION.md"),
		guideWithGhost.replace("Old.\n", "Old.\n\n### v0.75.0\n\nGhost.\n"),
	);
	await assert.rejects(syncReleaseVersion(cwd), /already contains ### v0\.75\.0/u);
	await writeFile(path.join(cwd, "MIGRATION.md"), `### Next release\n\n${stamped}`);
	await assert.rejects(syncReleaseVersion(cwd), /exactly one ### Next release/u);
});
