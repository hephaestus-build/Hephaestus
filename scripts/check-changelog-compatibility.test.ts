import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { pendingContractions, violations } from "./check-changelog-compatibility.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { stopEveryRoleEntries } from "./lib/migration-guide.ts";
import { previousRelease } from "./lib/previous-release.ts";

const changeSet = (id: string, changes: string): string =>
	`<changeSet id="${id}" author="hephaestus">${changes}</changeSet>`;
const changelog = (...sets: string[]): string =>
	`<?xml version="1.0" encoding="UTF-8"?>\n<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">${sets.join("")}</databaseChangeLog>`;
const check = (xml: string, contractions: readonly string[] = []): string[] =>
	violations({ changelogs: new Map([["new.xml", xml]]), contractions: new Set(contractions) });

void test("accepts additive change sets", () => {
	assert.deepEqual(
		check(
			changelog(
				changeSet(
					"1",
					`<createTable tableName="passkey"><column name="id" type="BIGINT"><constraints nullable="false" primaryKey="true"/></column></createTable>`,
				),
				changeSet(
					"2",
					`<addColumn tableName="account"><column name="note" type="TEXT"/><column name="enabled" type="BOOLEAN" defaultValueBoolean="false"><constraints nullable="false"/></column></addColumn>`,
				),
				changeSet("3", `<addNotNullConstraint tableName="passkey" columnName="label"/>`),
				changeSet(
					"4",
					`<addColumn tableName="account"><column name="level" type="INT"/></addColumn><addDefaultValue tableName="account" columnName="level" defaultValueNumeric="0"/><addNotNullConstraint tableName="account" columnName="level"/>`,
				),
				// A wider check, a rebuilt index and a renamed index leave the previous release working.
				changeSet(
					"5",
					`<sql><![CDATA[ALTER TABLE item_event DROP CONSTRAINT ck_reason; ALTER TABLE item_event ADD CONSTRAINT ck_reason CHECK (reason IN ('A', 'B'));
DROP INDEX CONCURRENTLY IF EXISTS idx_a; ALTER INDEX idx_b RENAME TO idx_c;
-- DROP TABLE in a comment is not a change.]]></sql>`,
				),
				changeSet(
					"6",
					`<addColumn tableName="workspace"><column name="mode" type="TEXT"/></addColumn><rollback><dropColumn tableName="workspace" columnName="mode"/></rollback>`,
				),
				// Constraints on a new table, or on a new column that the previous release leaves NULL, bind nothing it writes.
				changeSet(
					"7",
					`<addUniqueConstraint tableName="passkey" columnNames="label"/><addForeignKeyConstraint baseTableName="passkey" baseColumnNames="account_id" referencedTableName="account" referencedColumnNames="id" constraintName="fk" onDelete="CASCADE"/><createIndex tableName="passkey" indexName="ix" unique="true"><column name="label"/></createIndex><dropIndex tableName="passkey" indexName="ix"/>`,
				),
				changeSet(
					"8",
					`<addUniqueConstraint tableName="account" columnNames="note"/><createIndex tableName="account" indexName="ix_level"><column name="level"/></createIndex>`,
				),
				changeSet(
					"9",
					`<sql>ALTER TABLE account ADD COLUMN tier TEXT NOT NULL DEFAULT 'free', ADD COLUMN memo TEXT, ADD CONSTRAINT ck CHECK (memo IS NOT NULL OR tier = 'free')</sql>`,
				),
				changeSet(
					"10",
					`<addColumn tableName="account"><column name="handle" type="TEXT"><constraints unique="true"/></column><column name="team_id" type="BIGINT"><constraints references="team(id)" foreignKeyName="fk_team" deleteCascade="true"/></column></addColumn>`,
				),
			),
		),
		[],
	);
});

void test("rejects each change that the previous release cannot run beside", () => {
	const errors = check(
		changelog(
			changeSet("1", `<dropTable tableName="chat_thread"/>`),
			changeSet(
				"2",
				`<dropColumn tableName="workspace"><column name="a"/><column name="b"/></dropColumn>`,
			),
			changeSet("3", `<renameTable oldTableName="account" newTableName="person"/>`),
			changeSet(
				"4",
				`<renameColumn tableName="feedback_copy" oldColumnName="chat_message_id" newColumnName="entry_id"/>`,
			),
			changeSet(
				"5",
				`<modifyDataType tableName="account" columnName="name" newDataType="VARCHAR(512)"/>`,
			),
			changeSet("6", `<addNotNullConstraint tableName="account" columnName="name"/>`),
			changeSet(
				"7",
				`<addColumn tableName="account"><column name="tier" type="TEXT"/></addColumn><addNotNullConstraint tableName="account" columnName="tier"/>`,
			),
			changeSet(
				"8",
				`<addColumn tableName="account"><column name="kind" type="TEXT"><constraints nullable="false"/></column></addColumn>`,
			),
			changeSet("9", `<addUniqueConstraint tableName="account" columnNames="name"/>`),
			changeSet(
				"10",
				`<addForeignKeyConstraint baseTableName="account" baseColumnNames="team_id" referencedTableName="team" referencedColumnNames="id" constraintName="fk" onDelete="CASCADE"/>`,
			),
			changeSet(
				"11",
				`<createIndex tableName="account" indexName="ux" unique="true"><column name="name"/></createIndex>`,
			),
			changeSet("12", `<dropIndex tableName="account" indexName="ux_account_name"/>`),
			changeSet(
				"13",
				`<dropUniqueConstraint tableName="account" constraintName="uk_account_name"/>`,
			),
			changeSet("14", `<sqlFile path="db/backfill.sql"/>`),
		),
	);
	assert.deepEqual(errors, [
		"new.xml change set 1: dropTable chat_thread, which the previous release still uses. Drop only an object in the pending contractions of the latest release.",
		"new.xml change set 2: dropColumn workspace.a, which the previous release still uses. Drop only an object in the pending contractions of the latest release.",
		"new.xml change set 2: dropColumn workspace.b, which the previous release still uses. Drop only an object in the pending contractions of the latest release.",
		"new.xml change set 3: renameTable account",
		"new.xml change set 4: renameColumn feedback_copy.chat_message_id",
		"new.xml change set 5: modifyDataType account.name",
		"new.xml change set 6: addNotNullConstraint account.name, which the previous release can write as NULL",
		"new.xml change set 7: addNotNullConstraint account.tier without a default value",
		"new.xml change set 8: addColumn account.kind is NOT NULL without a default value",
		"new.xml change set 9: addUniqueConstraint on account, whose rows the previous release writes without that rule",
		"new.xml change set 10: addForeignKeyConstraint on account, whose rows the previous release writes without that rule",
		"new.xml change set 11: createIndex unique on account, whose rows the previous release writes without that rule",
		"new.xml change set 12: dropIndex ux_account_name on account, which the previous release can rely on, for example in an upsert",
		"new.xml change set 13: dropUniqueConstraint uk_account_name on account, which the previous release can rely on, for example in an upsert",
		"new.xml change set 14: sqlFile db/backfill.sql, which the guard cannot read. Use inline SQL.",
	]);
});

void test("rejects raw SQL that drops, renames, retypes or tightens", () => {
	for (const sql of [
		"DROP TABLE IF EXISTS chat_thread",
		"ALTER TABLE workspace DROP COLUMN allow_internet",
		"ALTER TABLE account RENAME TO person",
		"alter table only account rename column name to label",
		"ALTER TABLE account ALTER COLUMN name TYPE text",
		"ALTER TABLE account ALTER name SET DATA TYPE text",
		"ALTER TABLE account ALTER COLUMN name SET NOT NULL",
		"ALTER TABLE account ADD COLUMN memo TEXT, ADD COLUMN tier TEXT NOT NULL",
		"ALTER TABLE account ADD tier TEXT NOT NULL",
	]) {
		assert.equal(check(changelog(changeSet("1", `<sql>${sql}</sql>`))).length, 1, sql);
	}
});

void test("rejects a foreign key that the previous release can break by a delete, also on new objects", () => {
	const reference =
		"without a delete that follows the reference. The previous release can delete a referenced row. Use onDelete CASCADE or SET NULL.";
	assert.deepEqual(
		check(
			changelog(
				changeSet(
					"1",
					`<createTable tableName="disclosure"><column name="workspace_id" type="BIGINT"><constraints references="workspace(id)" foreignKeyName="fk_w"/></column></createTable>`,
				),
				changeSet(
					"2",
					`<addForeignKeyConstraint baseTableName="disclosure" baseColumnNames="workspace_id" referencedTableName="workspace" referencedColumnNames="id" constraintName="fk_r" onDelete="RESTRICT"/>`,
				),
				changeSet(
					"3",
					`<addForeignKeyConstraint baseTableName="disclosure" baseColumnNames="workspace_id" referencedTableName="workspace" referencedColumnNames="id" constraintName="fk_n" onDelete="SET NULL"/>`,
				),
				changeSet(
					"4",
					`<addColumn tableName="account"><column name="team_id" type="BIGINT"><constraints foreignKeyName="fk_t" referencedTableName="team" referencedColumnNames="id"/></column></addColumn>`,
				),
				// The previous release never deletes from a table it does not know.
				changeSet(
					"5",
					`<createTable tableName="subject"><column name="disclosure_id" type="BIGINT"><constraints references="disclosure(id)" foreignKeyName="fk_d"/></column></createTable><addForeignKeyConstraint baseTableName="subject" baseColumnNames="disclosure_id" referencedTableName="disclosure" referencedColumnNames="id" constraintName="fk_s"/>`,
				),
			),
		),
		[
			`new.xml change set 1: createTable disclosure.workspace_id references another table ${reference}`,
			`new.xml change set 2: addForeignKeyConstraint fk_r from disclosure to workspace ${reference}`,
			`new.xml change set 4: addColumn account.team_id references another table ${reference}`,
		],
	);
});

void test("rejects an inline unique column with a default on a table of the previous release", () => {
	assert.deepEqual(
		check(
			changelog(
				changeSet(
					"1",
					`<addColumn tableName="account"><column name="code" type="TEXT" defaultValue="none"><constraints unique="true"/></column></addColumn>`,
				),
			),
		),
		[
			"new.xml change set 1: addColumn constraint on account, whose rows the previous release writes without that rule",
		],
	);
});

void test("refuses a changelog that is not XML", () => {
	assert.deepEqual(
		violations({
			changelogs: new Map([["new.yaml", "databaseChangeLog: []"]]),
			contractions: new Set(),
		}),
		["new.yaml: not an XML changelog, which the guard cannot read"],
	);
});

void test("permits dropping an object in the pending contractions of the latest release", () => {
	const xml = changelog(
		changeSet("1", `<dropTable tableName="consent_notice"/>`),
		changeSet("2", `<dropColumn tableName="llm_model" columnName="supports_reasoning"/>`),
		changeSet("3", `<dropColumn tableName="llm_model" columnName="name"/>`),
	);
	assert.deepEqual(check(xml, ["consent_notice", "llm_model.supports_reasoning"]), [
		"new.xml change set 3: dropColumn llm_model.name, which the previous release still uses. Drop only an object in the pending contractions of the latest release.",
	]);
});

void test("reads the pending contractions from the first table after their heading", () => {
	const objects = pendingContractions(
		[
			"### Pending contractions",
			"",
			"| Object | Changelog that stopped writing it | Exclusion to remove |",
			"| --- | --- | --- |",
			"| `consent_notice` table | `1789067582535_changelog.xml` | `table:consent_notice` |",
			"| `llm_model.supports_reasoning`, `workspace_llm_model.supports_reasoning` columns | `1789135709162_changelog.xml` | `column:supports_reasoning` |",
			"",
			"## CI validation",
		].join("\n"),
	);
	assert.deepEqual(
		objects,
		new Set([
			"consent_notice",
			"llm_model.supports_reasoning",
			"workspace_llm_model.supports_reasoning",
		]),
	);
	assert.deepEqual(pendingContractions("## Other\n\n| `table` | x |"), new Set());
});

void test("the migration workflow page lists no pending contraction for 1.0", () => {
	const page = readFileSync(
		new URL("../docs/contributor/database-migration.mdx", import.meta.url),
		"utf8",
	);
	assert.deepEqual(pendingContractions(page), new Set());
});

void test("finds the stop-every-role entries of the upgrade from a previous version", () => {
	const guide = [
		"### Next release",
		"",
		"### v1.2.0",
		"",
		"#### 🔴 Stop every role for the ledger upgrade",
		"",
		"### v1.1.0",
		"",
		"#### 🔴 Stop every role for an older upgrade",
		"",
		"## Automatic vs Manual Migrations",
	].join("\n");
	const fragments = [
		"#### 🔴 Stop every role and deploy matching runtime roles\n\nText.",
		"#### 🔴 Update log queries\n\nStop every role is not a heading here.",
	];
	assert.deepEqual(stopEveryRoleEntries(guide, fragments, "1.1.0"), [
		"#### 🔴 Stop every role for the ledger upgrade",
		"#### 🔴 Stop every role and deploy matching runtime roles",
	]);
	assert.deepEqual(stopEveryRoleEntries(guide, [], "1.2.0"), []);
	assert.deepEqual(stopEveryRoleEntries(guide, [], "1.0.9").length, 2);
	assert.throws(() => stopEveryRoleEntries(guide, [], "v1.2.0"), /not an X\.Y\.Z version/u);
});

void test("judges a release commit against the release before it", () => {
	const tags = ["v0.9.0", "v0.10.0", "v0.10.1-rc.1", "v0.10.1", "latest"];
	assert.equal(previousRelease(tags, []), "v0.10.1");
	assert.equal(previousRelease(tags, ["v0.10.1"]), "v0.10.0");
	assert.equal(previousRelease([], []), undefined);
});

void test("judges the changelogs added since the latest release tag", (context) => {
	const directory = mkdtempSync(path.join(tmpdir(), "changelog-compatibility-"));
	context.after(() => rmSync(directory, { recursive: true, force: true }));
	const env = environmentForGitFixture();
	const git = (...args: string[]) => {
		const result = spawnSync("git", args, { cwd: directory, env, encoding: "utf8" });
		assert.equal(result.status, 0, result.stderr);
	};
	const write = (file: string, content: string) => {
		mkdirSync(path.dirname(path.join(directory, file)), { recursive: true });
		writeFileSync(path.join(directory, file), content);
	};
	const commit = (message: string) => {
		git("add", "-A");
		git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", message);
	};
	const guard = () =>
		spawnSync(
			process.execPath,
			[path.join(import.meta.dirname, "check-changelog-compatibility.ts")],
			{ cwd: directory, env, encoding: "utf8" },
		);
	const changelogs = "server/application/src/main/resources/db/changelog/";
	git("init", "-q");
	write("MIGRATION.md", "### Next release\n\n### v1.0.0\n\n## Automatic vs Manual Migrations\n");
	write(".migration/README.md", "# Migration fragments\n");
	write(
		"docs/contributor/database-migration.mdx",
		"### Pending contractions\n\n| Object | Changelog |\n| --- | --- |\n| `old_table` table | `1_changelog.xml` |\n",
	);
	write(`${changelogs}1_changelog.xml`, changelog(changeSet("1-1", `<dropTable tableName="x"/>`)));
	commit("release");
	git("tag", "v1.0.0");

	write(
		`${changelogs}2_changelog.xml`,
		changelog(changeSet("2-1", `<dropTable tableName="old_table"/>`)),
	);
	commit("contract");
	const contracted = guard();
	assert.equal(contracted.status, 0, contracted.stdout + contracted.stderr);
	assert.match(contracted.stdout, /New changelogs since v1\.0\.0: .*2_changelog\.xml\./u);

	write(
		`${changelogs}3_changelog.xml`,
		changelog(changeSet("3-1", `<dropColumn tableName="account" columnName="name"/>`)),
	);
	commit("drop");
	const dropped = guard();
	assert.equal(dropped.status, 1, dropped.stdout + dropped.stderr);
	assert.match(
		dropped.stderr,
		/::error::.*3_changelog\.xml change set 3-1: dropColumn account\.name/u,
	);
	assert.doesNotMatch(dropped.stderr, /1_changelog/u);

	write(
		".migration/account-name.md",
		"#### 🔴 Stop every role for the account name upgrade\n\nText.\n",
	);
	commit("stop every role");
	const stopped = guard();
	assert.equal(stopped.status, 0, stopped.stdout + stopped.stderr);
	// Tagged as the next release, the head is still judged against v1.0.0, not against itself.
	git("tag", "v1.1.0");
	const tagged = guard();
	assert.match(tagged.stdout, /New changelogs since v1\.0\.0: .*3_changelog\.xml\./u);
	assert.match(
		stopped.stdout,
		/::warning::Mechanical N-on-N\+1 coverage is off for this release, because it stops every role: #### 🔴 Stop every role for the account name upgrade/u,
	);
	assert.match(stopped.stdout, /3_changelog\.xml change set 3-1: dropColumn account\.name/u);
});
