import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { run } from "./lib/process.ts";

import {
	appendInclude,
	branchChangelog,
	parseDatabaseArguments,
	promoteDraft,
} from "./db-utils.ts";

const generated = `<?xml version="1.1" encoding="UTF-8" standalone="no"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
    <changeSet author="root (generated)" id="1788329906048-6">
        <dropForeignKeyConstraint baseTableName="consent_decision" constraintName="fk_consent_decision_notice"/>
    </changeSet>
    <changeSet author="root (generated)" id="1788329906048-3">
        <addNotNullConstraint columnDataType="timestamp(6) with timezone" columnName="updated_at" tableName="outline_collection" validate="true"/>
    </changeSet>
</databaseChangeLog>
`;

void test("a draft becomes a new changelog with sequential ids and one author", () => {
	const changelog = promoteDraft(generated, 1700000000000);
	assert.match(changelog, /^<\?xml version="1\.0" encoding="UTF-8"\?>\n<databaseChangeLog /);
	assert.match(changelog, /dbchangelog-latest\.xsd/);
	assert.deepEqual(
		[...changelog.matchAll(/<changeSet id="([^"]+)" author="hephaestus">/g)].map((m) => m[1]),
		["1700000000000-1", "1700000000000-2"],
	);
	assert.doesNotMatch(changelog, /root \(generated\)|version="1\.1"/);
	assert.match(changelog, /dropForeignKeyConstraint[\s\S]*addNotNullConstraint/);
	assert.ok(changelog.endsWith("</databaseChangeLog>\n"));
});

void test("a draft appends to the changelog this branch already added, continuing its numbering", () => {
	const existing = promoteDraft(generated, 1700000000000);
	const appended = promoteDraft(generated, 1700000000000, existing);
	assert.deepEqual(
		[...appended.matchAll(/<changeSet id="([^"]+)"/g)].map((m) => m[1]),
		["1700000000000-1", "1700000000000-2", "1700000000000-3", "1700000000000-4"],
	);
	assert.equal(appended.split("</databaseChangeLog>").length, 2);
});

void test("an empty draft is rejected rather than promoted to an empty changelog", () => {
	assert.throws(() => promoteDraft("<databaseChangeLog/>", 1700000000000), /no change sets/);
});

void test("master.xml gains the include at the end and never twice", () => {
	const master = `<databaseChangeLog>
    <include file="./changelog/1_changelog.xml" relativeToChangelogFile="true"/>
</databaseChangeLog>
`;
	const once = appendInclude(master, "2_changelog.xml");
	assert.equal(
		once,
		`<databaseChangeLog>
    <include file="./changelog/1_changelog.xml" relativeToChangelogFile="true"/>
    <include file="./changelog/2_changelog.xml" relativeToChangelogFile="true"/>
</databaseChangeLog>
`,
	);
	assert.equal(appendInclude(once, "2_changelog.xml"), once);
});

void test("a stacked schema draft accepts its parent branch explicitly", () => {
	assert.deepEqual(parseDatabaseArguments(["draft-changelog", "--base", "feat/accounts"]), {
		command: "draft-changelog",
		base: "feat/accounts",
		help: undefined,
	});
});

void test("database commands reject unknown or misplaced arguments before touching a database", () => {
	assert.throws(
		() => parseDatabaseArguments(["check-drift", "--base", "main"]),
		/only for draft-changelog/,
	);
	assert.throws(() => parseDatabaseArguments(["draft-changelog", "--base", ""]), /parent branch/);
	assert.throws(
		() => parseDatabaseArguments(["draft-changelog", "ignored"]),
		/one database command/,
	);
	assert.throws(() => parseDatabaseArguments(["draft-changelog", "--unknown"]));
});

void test("stacked changelog ownership includes staged drafts and rejects ambiguity and published history", async (t) => {
	const repository = await mkdtemp(join(tmpdir(), "hephaestus-changelog-"));
	t.after(() => rm(repository, { recursive: true, force: true }));
	const git = (...args: string[]) => run("git", args, { cwd: repository });
	const directory = "server/application/src/main/resources/db/changelog";
	await mkdir(join(repository, directory), { recursive: true });
	await git("init", "--initial-branch=main");
	await git("config", "user.name", "Changelog test");
	await git("config", "user.email", "changelog@example.test");
	await git("commit", "--allow-empty", "-m", "initial");
	await git("switch", "-c", "parent");
	const parent = `${directory}/1_changelog.xml`;
	await writeFile(join(repository, parent), "parent");
	await git("add", ".");
	await git("commit", "-m", "parent schema");
	await git("switch", "-c", "child");
	assert.equal(await branchChangelog("parent", repository), undefined);
	const child = `${directory}/2_changelog.xml`;
	await writeFile(join(repository, child), "child");
	assert.equal(await branchChangelog("parent", repository), join(repository, child));
	await git("add", ".");
	assert.equal(await branchChangelog("parent", repository), join(repository, child));
	await assert.rejects(branchChangelog(undefined, repository), /several changelogs/);
	const extra = `${directory}/3_changelog.xml`;
	await writeFile(join(repository, extra), "extra");
	await assert.rejects(branchChangelog("parent", repository), /several changelogs/);
	await rm(join(repository, extra));
	await assert.rejects(branchChangelog("missing-parent", repository));
	await git("commit", "-m", "child schema");
	await git("branch", "--force", "main", "HEAD");
	await assert.rejects(branchChangelog("parent", repository), /published on main/);
});
