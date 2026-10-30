import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { githubReleaseBody } from "./github-release-notes.ts";
import { output } from "./lib/process.ts";

const release = {
	release: "v0.95.0",
	commit: "a".repeat(40),
	repository: "owner/repo",
	migrations: true,
};

void test("ordinary descriptions retain every note and the migration warning", () => {
	const notes = "## 0.95.0\n\nAll operator-visible changes.\n";
	const body = githubReleaseBody({ ...release, notes });
	assert.ok(body.endsWith(notes));
	assert.match(body, /schema-migrations=true/u);
	assert.ok(body.includes(`/blob/${release.commit}/MIGRATION.md`));
	assert.equal(
		githubReleaseBody({ ...release, migrations: false, notes }),
		`<!-- hephaestus:schema-migrations=false -->\n${notes}`,
	);
});

void test("the GitHub boundary preserves full notes or points to the complete asset", () => {
	const prefix = githubReleaseBody({ ...release, notes: "" });
	const room = 125_000 - Buffer.byteLength(prefix);
	const notes = "a".repeat(room);
	assert.equal(Buffer.byteLength(githubReleaseBody({ ...release, notes })), 125_000);
	const tooLong = githubReleaseBody({ ...release, notes: `${notes}a` });
	assert.ok(Buffer.byteLength(tooLong) < 125_000);
	assert.match(tooLong, /releases\/download\/v0\.95\.0\/release-notes\.md/u);
	assert.ok(tooLong.includes(`/blob/${release.commit}/CHANGELOG.md`));
	assert.ok(tooLong.startsWith(prefix));
	assert.ok(
		Buffer.byteLength(githubReleaseBody({ ...release, notes: "🔴".repeat(125_000) })) < 125_000,
	);
});

void test("large notes travel through files without changing their complete bytes", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "github-release-notes-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const notesFile = path.join(directory, "notes.md");
	const bodyFile = path.join(directory, "body.md");
	const notes = "An operator-visible change.\n".repeat(100_000);
	await writeFile(notesFile, notes);
	await output(process.execPath, ["scripts/github-release-notes.ts", notesFile, bodyFile], {
		env: {
			RELEASE: release.release,
			COMMIT: release.commit,
			GITHUB_REPOSITORY: release.repository,
			MIGRATIONS: "true",
		},
	});
	assert.equal(await readFile(notesFile, "utf8"), notes);
	assert.match(await readFile(bodyFile, "utf8"), /Download the full notes/u);
});

void test("malformed release identities cannot become Markdown links", () => {
	for (const change of [{ release: "latest" }, { commit: "main" }, { repository: "owner/repo)" }]) {
		assert.throws(
			() => githubReleaseBody({ ...release, notes: "notes", ...change }),
			/Release notes need/u,
		);
	}
});
