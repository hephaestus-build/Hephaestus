import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { XMLParser } from "fast-xml-parser";
import { SyntaxValidator } from "fast-xml-validator";

import { isRecord } from "./lib/json.ts";
import { STOP_EVERY_ROLE_HEADING, stopEveryRoleEntries } from "./lib/migration-guide.ts";
import { previousReleaseOf } from "./lib/previous-release.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

// docs/contributor/database-migration.mdx § Expand and contract owns these rules.
const directory = "server/application/src/main/resources/db/changelog/";
const policy = "docs/contributor/database-migration.mdx";
const rule = `${policy}#expand-and-contract`;

/** What the guard reads from the tree of the latest release and from the head. */
export interface CompatibilityInput {
	/** Changelog files that the latest release does not have, by path. */
	readonly changelogs: ReadonlyMap<string, string>;
	/** Tables and `table.column` names in the latest release's pending contractions. */
	readonly contractions: ReadonlySet<string>;
}

type Element = Record<string, unknown>;

// Every element is a list, so a change set with one change and one with many read the same way.
const parser = new XMLParser({
	ignoreAttributes: false,
	isArray: (_name, _path, _leaf, isAttribute) => !isAttribute,
});

// An element with only text, such as a plain `<sql>`, parses to its text.
const elements = (parent: Element, name: string): Element[] => {
	const value = parent[name];
	return Array.isArray(value)
		? value.flatMap((entry) => {
				if (typeof entry === "string") {
					return [{ "#text": entry }];
				}
				return isRecord(entry) ? [entry] : [];
			})
		: [];
};

const attribute = (element: Element, name: string): string => {
	const value = element[`@_${name}`];
	return typeof value === "string" ? value : "";
};

function text(element: Element): string {
	const value = element["#text"];
	if (Array.isArray(value)) {
		return value.filter((part) => typeof part === "string").join("\n");
	}
	return typeof value === "string" ? value : "";
}

const hasDefault = (column: Element): boolean =>
	Object.keys(column).some((key) => key.startsWith("@_defaultValue"));

const notNull = (column: Element): boolean =>
	elements(column, "constraints").some(
		(constraints) => attribute(constraints, "nullable") === "false",
	);

/** Raw SQL that the previous release cannot run beside. Rename a constraint or index freely. */
const DESTRUCTIVE_SQL: readonly [RegExp, string][] = [
	[/\bdrop\s+table\b/iu, "DROP TABLE"],
	[/\bdrop\s+column\b/iu, "DROP COLUMN"],
	[/\balter\s+table\s+(?:if\s+exists\s+)?(?:only\s+)?\S+\s+rename\s+(?!constraint\b)/iu, "RENAME"],
	[/\balter\s+(?:column\s+)?\S+\s+(?:set\s+data\s+)?type\b/iu, "ALTER ... TYPE"],
	[/\bset\s+not\s+null\b/iu, "SET NOT NULL"],
];

/** Each `ADD COLUMN` clause of raw SQL, up to the next clause or statement. */
const addedColumnClauses = (sql: string): string[] =>
	sql
		.split(/\badd\s+(?!constraint\b|primary\b|unique\b|foreign\b|check\b|exclude\b|value\b)/iu)
		.slice(1)
		.map((clause) => clause.split(/;|,\s*(?:add|alter|drop|rename)\b/iu)[0] ?? "");

const withoutComments = (sql: string): string =>
	sql.replaceAll(/--[^\n]*/gu, "").replaceAll(/\/\*[\s\S]*?\*\//gu, "");

interface ChangeSet {
	readonly file: string;
	readonly id: string;
	readonly changes: Element;
}

function changeSets(file: string, xml: string): ChangeSet[] {
	try {
		SyntaxValidator.validate(xml);
	} catch (error) {
		throw new Error(`${file}: ${error instanceof Error ? error.message : String(error)}`, {
			cause: error,
		});
	}
	const document: unknown = parser.parse(xml);
	const root = isRecord(document) ? elements(document, "databaseChangeLog")[0] : undefined;
	if (root === undefined) {
		throw new Error(`${file}: no databaseChangeLog`);
	}
	// A rollback and a precondition never run during an upgrade.
	return elements(root, "changeSet").map((changes) => ({
		file,
		id: attribute(changes, "id"),
		changes,
	}));
}

/** What every new changelog together adds: the previous release knows none of it, so it never writes it. */
interface Additions {
	readonly tables: ReadonlySet<string>;
	readonly columns: ReadonlySet<string>;
	readonly defaults: ReadonlySet<string>;
}

function additions(sets: readonly ChangeSet[]): Additions {
	const tables = new Set<string>();
	const columns = new Set<string>();
	const defaults = new Set<string>();
	for (const { changes } of sets) {
		for (const table of elements(changes, "createTable")) {
			tables.add(attribute(table, "tableName"));
		}
		for (const added of elements(changes, "addColumn")) {
			for (const column of elements(added, "column")) {
				const name = `${attribute(added, "tableName")}.${attribute(column, "name")}`;
				columns.add(name);
				if (hasDefault(column)) {
					defaults.add(name);
				}
			}
		}
		for (const value of elements(changes, "addDefaultValue")) {
			defaults.add(`${attribute(value, "tableName")}.${attribute(value, "columnName")}`);
		}
	}
	return { tables, columns, defaults };
}

function contractionProblems(changes: Element, contractions: ReadonlySet<string>): string[] {
	const dropped = [
		...elements(changes, "dropTable").map(
			(table) => ["dropTable", attribute(table, "tableName")] as const,
		),
		...elements(changes, "dropColumn").flatMap((drop) =>
			[
				attribute(drop, "columnName"),
				...elements(drop, "column").map((column) => attribute(column, "name")),
			]
				.filter(Boolean)
				.map((column) => ["dropColumn", `${attribute(drop, "tableName")}.${column}`] as const),
		),
	];
	return dropped
		.filter(([, object]) => !contractions.has(object))
		.map(
			([change, object]) =>
				`${change} ${object}, which the previous release still uses. Drop only an object in the pending contractions of the latest release.`,
		);
}

const renameProblems = (changes: Element): string[] => [
	...elements(changes, "renameTable").map(
		(renamed) => `renameTable ${attribute(renamed, "oldTableName")}`,
	),
	...elements(changes, "renameColumn").map(
		(renamed) =>
			`renameColumn ${attribute(renamed, "tableName")}.${attribute(renamed, "oldColumnName")}`,
	),
	...elements(changes, "modifyDataType").map(
		(modified) =>
			`modifyDataType ${attribute(modified, "tableName")}.${attribute(modified, "columnName")}`,
	),
];

function notNullProblems(changes: Element, added: Additions): string[] {
	const problems: string[] = [];
	for (const constraint of elements(changes, "addNotNullConstraint")) {
		const table = attribute(constraint, "tableName");
		const column = `${table}.${attribute(constraint, "columnName")}`;
		if (added.tables.has(table)) {
			continue;
		}
		if (!added.columns.has(column)) {
			problems.push(`addNotNullConstraint ${column}, which the previous release can write as NULL`);
		} else if (!added.defaults.has(column)) {
			problems.push(`addNotNullConstraint ${column} without a default value`);
		}
	}
	for (const addition of elements(changes, "addColumn")) {
		const table = attribute(addition, "tableName");
		for (const column of elements(addition, "column")) {
			const name = `${table}.${attribute(column, "name")}`;
			if (!added.tables.has(table) && notNull(column) && !added.defaults.has(name)) {
				problems.push(`addColumn ${name} is NOT NULL without a default value`);
			}
		}
	}
	return problems;
}

const columns = (value: string): string[] =>
	value
		.split(",")
		.map((column) => column.trim())
		.filter(Boolean);

const inlineConstraints = (column: Element): Element[] => elements(column, "constraints");

const inlineForeignKey = (constraints: Element): boolean =>
	["references", "foreignKeyName", "referencedTableName"].some(
		(name) => attribute(constraints, name) !== "",
	);

/**
 * A foreign key binds the referenced rows too, also when the referencing table or column is new: the previous release
 * does not know it and can delete or update a referenced row. Only a delete that follows the reference is safe, or a
 * referenced table that the previous release does not know.
 */
function foreignKeyProblems(changes: Element, added: Additions): string[] {
	const referencedTable = (constraints: Element): string =>
		attribute(constraints, "referencedTableName") ||
		(attribute(constraints, "references").split("(")[0] ?? "").trim();
	const constraint = elements(changes, "addForeignKeyConstraint")
		.filter((change) => !["CASCADE", "SET NULL"].includes(attribute(change, "onDelete")))
		.filter((change) => !added.tables.has(attribute(change, "referencedTableName")))
		.map(
			(change) =>
				`addForeignKeyConstraint ${attribute(change, "constraintName")} from ${attribute(change, "baseTableName")} to ${attribute(change, "referencedTableName")}`,
		);
	const inline = [
		...elements(changes, "createTable").map((change) => [change, "createTable"] as const),
		...elements(changes, "addColumn").map((change) => [change, "addColumn"] as const),
	].flatMap(([change, kind]) =>
		elements(change, "column")
			.filter((column) =>
				inlineConstraints(column).some(
					(constraints) =>
						inlineForeignKey(constraints) &&
						attribute(constraints, "deleteCascade") !== "true" &&
						!added.tables.has(referencedTable(constraints)),
				),
			)
			.map(
				(column) =>
					`${kind} ${attribute(change, "tableName")}.${attribute(column, "name")} references another table`,
			),
	);
	return [...constraint, ...inline].map(
		(problem) =>
			`${problem} without a delete that follows the reference. The previous release can delete a referenced row. Use onDelete CASCADE or SET NULL.`,
	);
}

/** Tightens the rows that the previous release writes, unless it applies to objects that release does not know. */
function constraintProblems(changes: Element, added: Additions): string[] {
	// The previous release writes NULL into a new column without a default, which no constraint below rejects.
	const unknown = (table: string, names: readonly string[]) =>
		added.tables.has(table) ||
		(names.length > 0 &&
			names.every(
				(name) => added.columns.has(`${table}.${name}`) && !added.defaults.has(`${table}.${name}`),
			));
	const tightened = [
		...elements(changes, "addUniqueConstraint").map(
			(change) =>
				[
					"addUniqueConstraint",
					attribute(change, "tableName"),
					columns(attribute(change, "columnNames")),
				] as const,
		),
		...elements(changes, "addForeignKeyConstraint").map(
			(change) =>
				[
					"addForeignKeyConstraint",
					attribute(change, "baseTableName"),
					columns(attribute(change, "baseColumnNames")),
				] as const,
		),
		...elements(changes, "addColumn").flatMap((change) =>
			elements(change, "column")
				.filter((column) =>
					inlineConstraints(column).some(
						(constraints) =>
							attribute(constraints, "unique") === "true" || inlineForeignKey(constraints),
					),
				)
				.map(
					(column) =>
						[
							"addColumn constraint",
							attribute(change, "tableName"),
							[attribute(column, "name")],
						] as const,
				),
		),
		...elements(changes, "createIndex")
			.filter((change) => attribute(change, "unique") === "true")
			.map(
				(change) =>
					[
						"createIndex unique",
						attribute(change, "tableName"),
						elements(change, "column").map((column) => attribute(column, "name")),
					] as const,
			),
	];
	const dropped = [
		...elements(changes, "dropIndex").map(
			(change) =>
				["dropIndex", attribute(change, "tableName"), attribute(change, "indexName")] as const,
		),
		...elements(changes, "dropUniqueConstraint").map(
			(change) =>
				[
					"dropUniqueConstraint",
					attribute(change, "tableName"),
					attribute(change, "constraintName"),
				] as const,
		),
	];
	return [
		...tightened
			.filter(([, table, names]) => !unknown(table, names))
			.map(
				([change, table]) =>
					`${change} on ${table}, whose rows the previous release writes without that rule`,
			),
		...dropped
			.filter(([, table]) => !added.tables.has(table))
			.map(
				([change, table, name]) =>
					`${change} ${name} on ${table}, which the previous release can rely on, for example in an upsert`,
			),
	];
}

const sqlProblems = (changes: Element): string[] => [
	...elements(changes, "sql").flatMap((sql) => {
		const statement = withoutComments(text(sql));
		const destructive = DESTRUCTIVE_SQL.filter(([pattern]) => pattern.test(statement)).map(
			([, name]) => name,
		);
		const notNullColumn = addedColumnClauses(statement).some(
			(clause) => /\bnot\s+null\b/iu.test(clause) && !/\bdefault\b/iu.test(clause),
		);
		return [
			...destructive,
			...(notNullColumn ? ["ADD COLUMN … NOT NULL without DEFAULT"] : []),
		].map(
			(name) =>
				`raw SQL ${name}. Use a Liquibase change type, so that the guard can check the object.`,
		);
	}),
	...elements(changes, "sqlFile").map(
		(file) => `sqlFile ${attribute(file, "path")}, which the guard cannot read. Use inline SQL.`,
	),
];

/**
 * The change sets in the new changelogs that the previous release cannot run beside. The guard reads each change set
 * alone: it does not model the order of change sets, so a later change set cannot repair an earlier one.
 */
export function violations({ changelogs, contractions }: CompatibilityInput): string[] {
	const unreadable = [...changelogs.keys()]
		.filter((file) => !file.endsWith(".xml"))
		.map((file) => `${file}: not an XML changelog, which the guard cannot read`);
	const sets = [...changelogs]
		.filter(([file]) => file.endsWith(".xml"))
		.flatMap(([file, xml]) => changeSets(file, xml));
	const added = additions(sets);
	return [
		...unreadable,
		...sets.flatMap(({ file, id, changes }) =>
			[
				...contractionProblems(changes, contractions),
				...renameProblems(changes),
				...notNullProblems(changes, added),
				...constraintProblems(changes, added),
				...foreignKeyProblems(changes, added),
				...sqlProblems(changes),
			].map((problem) => `${file} change set ${id}: ${problem}`),
		),
	];
}

/** The objects in the first table after the pending contractions heading: table names and `table.column`. */
export function pendingContractions(mdx: string): Set<string> {
	const section = mdx.split(/^### Pending contractions$/mu)[1]?.split(/^#{2,3} /mu)[0] ?? "";
	const objects = new Set<string>();
	for (const row of section.split("\n").filter((line) => line.startsWith("|"))) {
		const object = row.split("|")[1] ?? "";
		for (const match of object.matchAll(/`(?<name>[a-z0-9_.]+)`/gu)) {
			const name = match.groups?.name;
			if (name !== undefined) {
				objects.add(name);
			}
		}
	}
	return objects;
}

function git(...args: string[]): string {
	return execFileSync("git", args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
}

const files = (revision: string, path: string): string[] =>
	git("ls-tree", "-r", "--name-only", "-z", revision, "--", path).split("\0").filter(Boolean);

const show = (revision: string, path: string): string => git("show", `${revision}:${path}`);

if (import.meta.main) {
	const usage = "Usage: node scripts/check-changelog-compatibility.ts [--base vX.Y.Z] [head]";
	const args = process.argv.slice(2);
	const baseIndex = args.indexOf("--base");
	const base = baseIndex === -1 ? undefined : args.splice(baseIndex, 2)[1];
	const [head = "HEAD", ...rest] = args;
	if (rest.length > 0 || (baseIndex !== -1 && base === undefined)) {
		throw new Error(usage);
	}
	const release = base ?? previousReleaseOf(head);
	if (release === undefined) {
		throw new Error(`No stable release tag is merged into ${head}. ${usage}`);
	}
	const released = new Set(files(release, directory));
	const changelogs = new Map(
		files(head, directory)
			.filter((file) => !released.has(file))
			.map((file) => [file, show(head, file)]),
	);
	const contractions = files(release, policy).length > 0 ? show(release, policy) : "";
	const errors = violations({ changelogs, contractions: pendingContractions(contractions) });
	const fragments = files(head, ".migration/")
		.filter((file) => file.endsWith(".md") && !file.endsWith("/README.md"))
		.map((file) => show(head, file));
	const stops = stopEveryRoleEntries(show(head, "MIGRATION.md"), fragments, release.slice(1));
	console.log(
		`New changelogs since ${release}: ${changelogs.size === 0 ? "none" : [...changelogs.keys()].join(", ")}.`,
	);
	if (stops.length > 0) {
		// One loud line: the waiver covers the whole release, not named objects.
		const line = `Mechanical N-on-N+1 coverage is off for this release, because it stops every role: ${stops.join("; ")}`;
		console.log(`::warning::${line}`);
		const summary = process.env.GITHUB_STEP_SUMMARY;
		if (summary !== undefined && summary !== "") {
			appendFileSync(summary, `- ${line}\n`);
		}
	}
	if (errors.length > 0 && stops.length > 0) {
		console.log("The guard permits these change sets for that reason:");
		for (const error of errors) {
			console.log(`  ${error}`);
		}
	} else if (errors.length > 0) {
		for (const error of errors) {
			console.error(`::error::${error}`);
		}
		console.error(
			`The previous release runs on the new schema during a rollout and after a rollback. See ${rule}. If the release cannot keep that rule, add a .migration fragment whose heading starts with "${STOP_EVERY_ROLE_HEADING}".`,
		);
		process.exitCode = 1;
	} else {
		console.log("The previous release can run on every new change set.");
	}
}
