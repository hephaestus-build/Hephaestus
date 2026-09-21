import assert from "node:assert/strict";
import { test } from "node:test";

import { appendInclude, promoteDraft } from "./db-utils.ts";

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
	const changelog = promoteDraft(generated, 1_700_000_000_000);
	assert.match(changelog, /^<\?xml version="1\.0" encoding="UTF-8"\?>\n<databaseChangeLog /u);
	assert.match(changelog, /dbchangelog-latest\.xsd/u);
	assert.deepEqual(
		[...changelog.matchAll(/<changeSet id="(?<id>[^"]+)" author="hephaestus">/gu)].map(
			(m) => m.groups?.id,
		),
		["1700000000000-1", "1700000000000-2"],
	);
	assert.doesNotMatch(changelog, /root \(generated\)|version="1\.1"/u);
	assert.match(changelog, /dropForeignKeyConstraint[\s\S]*addNotNullConstraint/u);
	assert.ok(changelog.endsWith("</databaseChangeLog>\n"));
});

void test("a draft appends to the changelog this branch already added, continuing its numbering", () => {
	const existing = promoteDraft(generated, 1_700_000_000_000);
	const appended = promoteDraft(generated, 1_700_000_000_000, existing);
	assert.deepEqual(
		[...appended.matchAll(/<changeSet id="(?<id>[^"]+)"/gu)].map((m) => m.groups?.id),
		["1700000000000-1", "1700000000000-2", "1700000000000-3", "1700000000000-4"],
	);
	assert.equal(appended.split("</databaseChangeLog>").length, 2);
});

void test("an empty draft is rejected rather than promoted to an empty changelog", () => {
	assert.throws(() => promoteDraft("<databaseChangeLog/>", 1_700_000_000_000), /no change sets/u);
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
