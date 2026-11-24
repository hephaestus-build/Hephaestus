const BASELINE = "db/changelog/1.0.0/baseline.xml";
const FIRST_MIGRATION = "db/changelog/next/20261101110000-durable-practice-staging.xml";
const FORWARD_REFERENCE = "upgrade-test-forward-reference";

export const PREFIX_HISTORY_SQL = `
	SELECT filename || '::' || id || '::' || author
	FROM databasechangelog
	WHERE filename IN ('${BASELINE}', '${FIRST_MIGRATION}') OR id = '${FORWARD_REFERENCE}'
	ORDER BY filename, id
`;

const baselineHistory = ["audit", "schema", "seed", "tag"].map(
	(id) => `${BASELINE}::1.0.0-${id}::hephaestus`,
);
const firstMigrationHistory = ["1", "2", "3", "4"].map(
	(id) => `${FIRST_MIGRATION}::1793362753961-${id}::hephaestus`,
);

/**
 * Both runs use the candidate image's real baseline and first migration. The broken run tries to
 * index a column before the first migration adds it.
 */
export function prefixChangelog(broken: boolean): string {
	const fault = broken
		? `<changeSet id="${FORWARD_REFERENCE}" author="upgrade-test">
            <sql>CREATE INDEX upgrade_test_forward_reference ON review_item (staged_attempt)</sql>
        </changeSet>`
		: "";
	return `<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
    <include file="${BASELINE}"/>
    ${fault}
    <include file="${FIRST_MIGRATION}"/>
</databaseChangeLog>`;
}

export function assertForwardReferenceRefused(status: number | null, output: string): void {
	if (
		status === null ||
		status === 0 ||
		!output.includes(`::${FORWARD_REFERENCE}::upgrade-test`) ||
		// The PostgreSQL message can appear in an ECS JSON message or in plain stderr.
		!/column \\?"staged_attempt\\?" does not exist/u.test(output)
	) {
		throw new Error(`The broken migration prefix must fail at ${FORWARD_REFERENCE}:\n${output}`);
	}
}

export function assertPrefixHistory(history: string, broken: boolean): void {
	const expected = broken ? baselineHistory : [...baselineHistory, ...firstMigrationHistory];
	if (history !== expected.join("\n")) {
		throw new Error(
			`Wrong migration history after the ${broken ? "broken" : "valid"} prefix.\nExpected:\n${expected.join("\n")}\nRecorded:\n${history}`,
		);
	}
}
