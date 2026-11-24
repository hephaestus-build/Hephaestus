import { attribute, changelogParser, elements } from "./changelog-xml.ts";
import { migrationTime } from "./changelog.ts";
import { asRecord } from "./json.ts";

const BASELINE = "db/changelog/1.0.0/baseline.xml";
const FORWARD_REFERENCE = "upgrade-test-forward-reference";
const CHANGE_SET_REFUSAL =
	/(?:^|[\s"'/:])upgrade-prefix\.xml::upgrade-test-forward-reference::upgrade-test(?=:(?!:)|\.(?=[\s"']|$)|[\s"']|$)/mu;

interface FirstMigration {
	resource: string;
	changes: readonly string[];
	table: string;
	column: string;
}

const baselineHistory = ["audit", "schema", "seed", "tag"].map(
	(id) => `${BASELINE}::1.0.0-${id}::hephaestus`,
);

/** db/master.xml includes the XML files under next in lexical filename order. */
export function firstMigration(
	files: readonly string[],
	readFile: (file: string) => string,
): FirstMigration {
	const [file] = files.filter((name) => name.endsWith(".xml")).toSorted();
	if (file === undefined) {
		throw new Error("The migration prefix requires an XML changelog in db/changelog/next");
	}
	migrationTime(file);
	const resource = `db/changelog/next/${file}`;
	const root = asRecord(changelogParser.parse(readFile(file)), resource);
	const changes = elements(elements(root, "databaseChangeLog")[0] ?? {}, "changeSet");
	const addedColumn = changes.flatMap((change) => elements(change, "addColumn"))[0] ?? {};
	const table = attribute(addedColumn, "tableName");
	const column = attribute(elements(addedColumn, "column")[0] ?? {}, "name");
	if (![table, column].every((name) => /^[a-z][a-z0-9_]*$/u.test(name))) {
		throw new Error(`${resource} must declare a table and an added column for the broken prefix`);
	}
	return {
		resource,
		changes: changes.map(
			(change) => `${resource}::${attribute(change, "id")}::${attribute(change, "author")}`,
		),
		table,
		column,
	};
}

export function prefixHistorySql(first: FirstMigration): string {
	return `
		SELECT filename || '::' || id || '::' || author
		FROM databasechangelog
		WHERE filename IN ('${BASELINE}', '${first.resource}') OR id = '${FORWARD_REFERENCE}'
	`;
}

/**
 * Both runs use the candidate image's real baseline and first migration. The broken run tries to
 * index a column before the first migration adds it.
 */
export function prefixChangelog(first: FirstMigration, broken: boolean): string {
	const fault = broken
		? `<changeSet id="${FORWARD_REFERENCE}" author="upgrade-test">
            <sql>CREATE INDEX upgrade_test_forward_reference ON ${first.table} (${first.column})</sql>
        </changeSet>`
		: "";
	return `<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
    <include file="${BASELINE}"/>
    ${fault}
    <include file="${first.resource}"/>
</databaseChangeLog>`;
}

export function assertForwardReferenceRefused(
	first: FirstMigration,
	status: number | null,
	output: string,
): void {
	if (
		status === null ||
		status === 0 ||
		!CHANGE_SET_REFUSAL.test(output) ||
		// The PostgreSQL message can appear in an ECS JSON message or in plain stderr.
		!output.replaceAll(String.raw`\"`, '"').includes(`column "${first.column}" does not exist`)
	) {
		throw new Error(`The broken migration prefix must fail at ${FORWARD_REFERENCE}:\n${output}`);
	}
}

export function assertPrefixHistory(first: FirstMigration, history: string, broken: boolean): void {
	const expected = (broken ? baselineHistory : [...baselineHistory, ...first.changes]).toSorted();
	if (history.split("\n").toSorted().join("\n") !== expected.join("\n")) {
		throw new Error(
			`Wrong migration history after the ${broken ? "broken" : "valid"} prefix.\nExpected:\n${expected.join("\n")}\nRecorded:\n${history}`,
		);
	}
}
