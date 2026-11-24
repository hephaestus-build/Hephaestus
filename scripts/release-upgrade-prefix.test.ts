import assert from "node:assert/strict";
import { test } from "node:test";

import {
	assertForwardReferenceRefused,
	assertPrefixHistory,
	firstMigration,
	prefixChangelog,
	prefixHistorySql,
} from "./lib/release-upgrade-prefix.ts";

const firstFile = "20260102000100-add-stage.xml";
const first = firstMigration(["20260103000000-other.xml", "README.md", firstFile], (file) => {
	assert.equal(file, firstFile);
	return `<databaseChangeLog>
  <changeSet id="add-stage" author="author1">
    <addColumn tableName="example"><column name="stage" type="INTEGER"/></addColumn>
  </changeSet>
  <changeSet id="add-state" author="author2"><sql>SELECT 1</sql></changeSet>
</databaseChangeLog>`;
});

const failure = `Migration failed for changeset file:/upgrade-prefix.xml::upgrade-test-forward-reference::upgrade-test:
Reason: liquibase.exception.DatabaseException: ERROR: column "stage" does not exist`;

void test("the prefix uses the first migration in Liquibase order and its real change set identities", () => {
	assert.ok(prefixChangelog(first, false).includes(`file="db/changelog/next/${firstFile}"`));
	assert.ok(prefixChangelog(first, true).includes("ON example (stage)"));
	assert.ok(prefixHistorySql(first).includes(`'db/changelog/next/${firstFile}'`));
});

void test("accepts the refusal only for the injected change set and its missing column", () => {
	assert.doesNotThrow(() => assertForwardReferenceRefused(first, 1, failure));
	assert.doesNotThrow(() =>
		assertForwardReferenceRefused(first, 1, JSON.stringify({ message: failure })),
	);
	assert.doesNotThrow(() =>
		assertForwardReferenceRefused(first, 1, failure.replace("::upgrade-test:", "::upgrade-test.")),
	);
	for (const status of [0, null]) {
		assert.throws(
			() => assertForwardReferenceRefused(first, status, failure),
			/upgrade-test-forward-reference/u,
		);
	}
	for (const unrelated of [
		failure.replace("upgrade-prefix.xml", "other-prefix.xml"),
		failure.replace("::upgrade-test:", "::upgrade-test-other:"),
		failure.replace("::upgrade-test:", "::upgrade-test.other:"),
		failure.replace("::upgrade-test-forward-reference", "::some-other-test"),
		failure.replace('"stage"', '"other_column"'),
	]) {
		assert.throws(
			() => assertForwardReferenceRefused(first, 1, unrelated),
			/upgrade-test-forward-reference/u,
		);
	}
});

void test("requires canonical baseline identities and refuses a later change set on the broken prefix", () => {
	const baseline = `db/changelog/1.0.0/baseline.xml::1.0.0-audit::hephaestus
db/changelog/1.0.0/baseline.xml::1.0.0-schema::hephaestus
db/changelog/1.0.0/baseline.xml::1.0.0-seed::hephaestus
db/changelog/1.0.0/baseline.xml::1.0.0-tag::hephaestus`;
	const later = `db/changelog/next/${firstFile}::add-stage::author1`;
	assert.doesNotThrow(() => assertPrefixHistory(first, baseline, true));
	assert.doesNotThrow(() =>
		assertPrefixHistory(
			first,
			`${later}\n${baseline}\ndb/changelog/next/${firstFile}::add-state::author2`,
			false,
		),
	);
	assert.throws(
		() =>
			assertPrefixHistory(
				first,
				baseline.replace("db/changelog/", "classpath:db/changelog/"),
				true,
			),
		/Wrong migration history/u,
	);
	assert.throws(
		() => assertPrefixHistory(first, `${baseline}\n${later}`, true),
		/Wrong migration history/u,
	);
	assert.throws(() => assertPrefixHistory(first, baseline, false), /Wrong migration history/u);
});
