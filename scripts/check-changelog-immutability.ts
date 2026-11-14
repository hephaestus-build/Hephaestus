import { execFileSync } from "node:child_process";
import { XMLParser } from "fast-xml-parser";
import { SyntaxValidator } from "fast-xml-validator";

import nodePath from "node:path";

import { CHANGELOG_DIRECTORY, NEXT_CHANGELOG_DIRECTORY, migrationTime } from "./lib/changelog.ts";
import { isSet } from "./lib/env.ts";
import { asArray, asRecord, asString, isRecord } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const directory = `${CHANGELOG_DIRECTORY}/`;
const master = "server/application/src/main/resources/db/master.xml";
const legacyBaseline = "./changelog/0000000000000_baseline_v0_77_4.xml";
const baseline = "./changelog/1.0.0/baseline.xml";
/** The release whose schema the 1.0 baseline holds. */
const baselineRelease = "v1.0.0-rc.1";
export interface ChangelogSnapshot {
	blobs: ReadonlyMap<string, string>;
	master: string;
	/** The content of a changelog file in this snapshot. */
	read: (path: string) => string;
}

const parser = new XMLParser({
	ignoreAttributes: false,
	isArray: (name) => name === "include" || name === "includeAll",
});

// Every element is a list, so a change set with one child and one with many read the same way.
const changeSetParser = new XMLParser({
	ignoreAttributes: false,
	isArray: (_name, _path, _leaf, isAttribute) => !isAttribute,
});

const children = (parent: Record<string, unknown>, name: string): unknown[] => {
	const value = parent[name];
	return Array.isArray(value) ? value : [];
};

const asRecordOrEmpty = (value: unknown): Record<string, unknown> => (isRecord(value) ? value : {});

const sqlText = (value: unknown): string => {
	const text = typeof value === "string" ? value : asRecordOrEmpty(value)["#text"];
	return typeof text === "string" ? text : "";
};

const hasContent = (value: unknown): boolean =>
	typeof value === "string"
		? value.trim() !== ""
		: Object.keys(asRecordOrEmpty(value)).some((key) => !key.startsWith("@_"));

/** A statement that changes rows, outside comments and dollar-quoted function bodies. */
const changesRows = (sql: string): boolean =>
	sql
		.replaceAll(/--[^\n]*/gu, "")
		.replaceAll(/\/\*[\s\S]*?\*\//gu, "")
		.replaceAll(/\$(?<tag>[A-Za-z_]*)\$[\s\S]*?\$\k<tag>\$/gu, "")
		.split(";")
		.map((statement) => statement.trim())
		.some(
			(statement) =>
				/^(?:update|delete|insert|merge|truncate)\b/iu.test(statement) ||
				(/^with\b/iu.test(statement) && /\b(?:update|delete|insert)\b/iu.test(statement)),
		);

/**
 * Raw SQL and custom changes have no inverse that Liquibase can derive, so each states its rollback: real statements,
 * or a `<stop>` that names the backup to restore. A data correction also states which rows are wrong and halts on a
 * database whose columns it cannot read. docs/contributor/database-migration.mdx § Data corrections owns the rule.
 */
export function recoveryProblems(file: string, xml: string): string[] {
	SyntaxValidator.validate(xml);
	const document = asRecordOrEmpty(changeSetParser.parse(xml));
	const root = asRecordOrEmpty(children(document, "databaseChangeLog")[0]);
	return children(root, "changeSet").flatMap((value) => {
		const set = asRecordOrEmpty(value);
		const id = typeof set["@_id"] === "string" ? set["@_id"] : "";
		const raw = ["sql", "sqlFile", "customChange"].filter((name) => children(set, name).length > 0);
		if (raw.length === 0) {
			return [];
		}
		const problems: string[] = [];
		if (!children(set, "rollback").some(hasContent)) {
			problems.push(
				`${raw.join(" and ")} without a <rollback>. Write the reverse statements, or <rollback><stop> that names the backup to restore.`,
			);
		}
		if (children(set, "sql").some((sql) => changesRows(sqlText(sql)))) {
			if (!children(set, "comment").some(hasContent)) {
				problems.push(
					"a data correction without a <comment> that states which rows are wrong and why.",
				);
			}
			const halts = children(set, "preConditions").some((conditions) => {
				const onFail = asRecordOrEmpty(conditions)["@_onFail"];
				return hasContent(conditions) && (onFail === undefined || onFail === "HALT");
			});
			if (!halts) {
				problems.push("a data correction without a HALT precondition on the columns it reads.");
			}
		}
		return problems.map((problem) => `${file} change set ${id}: ${problem}`);
	});
}

const canonical = (value: unknown) =>
	JSON.stringify(
		Object.entries(asRecord(value, "includeAll attributes")).toSorted(([a], [b]) =>
			a.localeCompare(b),
		),
	);

function includes(xml: string, requireAutomatic = false): string[] {
	if (!xml) {
		return [];
	}
	SyntaxValidator.validate(xml);
	const document: unknown = parser.parse(xml);
	const root = asRecord(document, "changelog document").databaseChangeLog;
	if (root === "") {
		return [];
	}
	const changelog = asRecord(root, "databaseChangeLog");
	if (changelog.changeSet !== undefined) {
		throw new Error("Keep change sets in timestamped files, not master.xml");
	}
	const automatic = asArray(changelog.includeAll ?? [], "automatic includes");
	if (automatic.length > 0 || requireAutomatic) {
		const expected = {
			"@_path": "./changelog/next",
			"@_relativeToChangelogFile": "true",
			"@_errorIfMissingOrEmpty": "false",
			"@_endsWithFilter": ".xml",
			"@_maxDepth": "1",
		};

		if (
			automatic.length !== 1 ||
			canonical(automatic[0]) !== canonical(expected) ||
			!/<includeAll\b[^>]*\/>\s*<\/databaseChangeLog>\s*$/u.test(
				xml.replaceAll(/<!--[\s\S]*?-->/gu, ""),
			)
		) {
			throw new Error("End master.xml with the one flat XML includeAll for ./changelog/next");
		}
	}
	return asArray(changelog.include ?? [], "changelog includes").map((value) => {
		const attributes = asRecord(value, "include attributes");
		asString(attributes["@_file"], "include file");
		return JSON.stringify(Object.entries(attributes).toSorted(([a], [b]) => a.localeCompare(b)));
	});
}

const includedFile = (include: string): unknown =>
	asArray(JSON.parse(include), "include")
		.map((entry) => asArray(entry, "include attribute"))
		.find(([name]) => name === "@_file")?.[1];

/** Whether the base is the 0.x chain and the head includes the 1.0 baseline in its place. */
function replacesLegacyChain(
	oldIncludes: readonly string[],
	newIncludes: readonly string[],
): boolean {
	return (
		oldIncludes.map(includedFile).includes(legacyBaseline) &&
		newIncludes.length === 1 &&
		newIncludes[0] ===
			JSON.stringify([
				["@_file", baseline],
				["@_relativeToChangelogFile", "true"],
			])
	);
}

/**
 * The one exception to "a released migration never changes": the 1.0 baseline replaces the chain that built the schema
 * of its release. Deleted are exactly that release's files, as it shipped them. Every later file stays byte for byte,
 * so `next/` may keep growing. Added are files beside the baseline only. The rule needs the 0.x baseline in the base,
 * so it expires when the replacement merges. docs/contributor/database-migration.mdx § The 1.0 baseline owns the rule.
 */
function replacementErrors(
	before: ChangelogSnapshot,
	after: ChangelogSnapshot,
	release: ChangelogSnapshot,
): string[] {
	const errors: string[] = [];
	const replaced = new Set(release.blobs.keys());
	for (const [path, blob] of release.blobs) {
		if (!path.startsWith(directory)) {
			continue;
		}
		if (before.blobs.get(path) !== blob) {
			errors.push(`The 1.0 baseline must replace ${path} as ${baselineRelease} shipped it.`);
		}
		if (after.blobs.has(path)) {
			errors.push(`The 1.0 baseline must delete ${path}, which ${baselineRelease} holds.`);
		}
	}
	for (const [path, blob] of before.blobs) {
		if (path.startsWith(directory) && !replaced.has(path) && after.blobs.get(path) !== blob) {
			errors.push(`Released migration changed: ${path}`);
		}
	}
	for (const path of after.blobs.keys()) {
		if (
			path.startsWith(directory) &&
			!before.blobs.has(path) &&
			!path.startsWith(`${directory}1.0.0/`)
		) {
			errors.push(`The 1.0 baseline may add files only beside itself: ${path}`);
		}
	}
	return errors;
}

export function violations(
	before: ChangelogSnapshot,
	after: ChangelogSnapshot,
	release: () => ChangelogSnapshot = () => snapshot(baselineRelease),
): string[] {
	const errors: string[] = [];
	let oldIncludes: string[];
	let newIncludes: string[];
	try {
		oldIncludes = includes(before.master);
		newIncludes = includes(after.master, true);
	} catch (error) {
		return [`Invalid changelog: ${error instanceof Error ? error.message : String(error)}`];
	}
	if (replacesLegacyChain(oldIncludes, newIncludes)) {
		return replacementErrors(before, after, release());
	}
	for (const [path, blob] of before.blobs) {
		if (path.startsWith(directory) && after.blobs.get(path) !== blob) {
			errors.push(`Released migration changed: ${path}`);
		}
	}
	if (
		newIncludes.length !== oldIncludes.length ||
		oldIncludes.some((include, index) => newIncludes[index] !== include)
	) {
		errors.push(
			"The released explicit include prefix must not change; add migrations through includeAll.",
		);
	}
	const previous = [...before.blobs.keys()]
		.filter((file) => file.startsWith(`${NEXT_CHANGELOG_DIRECTORY}/`))
		.toSorted()
		.at(-1);
	for (const file of after.blobs.keys()) {
		if (!file.startsWith(directory) || before.blobs.has(file)) {
			continue;
		}
		try {
			if (nodePath.posix.dirname(file) !== NEXT_CHANGELOG_DIRECTORY) {
				throw new Error(`New migrations belong in ${NEXT_CHANGELOG_DIRECTORY}: ${file}`);
			}
			migrationTime(file);
			if (previous !== undefined && file <= previous) {
				throw new Error(`New migration sorts before existing history: ${file} <= ${previous}`);
			}
			errors.push(...recoveryProblems(file, after.read(file)));
		} catch (error) {
			errors.push(error instanceof Error ? error.message : String(error));
		}
	}

	return errors;
}

function git(...args: string[]): string {
	return execFileSync("git", args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
}

function snapshot(revision: string): ChangelogSnapshot {
	const commit = git("rev-parse", "--verify", "--end-of-options", `${revision}^{commit}`).trim();
	const entries = git("ls-tree", "-r", "-z", commit, "--", directory, master);
	const blobs = new Map<string, string>();
	for (const entry of entries.split("\0").filter(Boolean)) {
		const [metadata, path] = entry.split("\t");
		const blob = metadata?.split(" ")[2];
		if (path !== undefined && blob !== undefined) {
			blobs.set(path, blob);
		}
	}
	const read = (path: string) => git("show", `${commit}:${path}`);
	return { blobs, master: blobs.has(master) ? read(master) : "", read };
}

if (import.meta.main) {
	const [base, head = "HEAD"] = process.argv.slice(2);
	if (!isSet(base) || process.argv.length > 4) {
		throw new Error("Usage: node scripts/check-changelog-immutability.ts <base> [head]");
	}
	const errors = violations(snapshot(base), snapshot(head));
	for (const error of errors) {
		console.error(`::error::${error}`);
	}
	if (errors.length > 0) {
		process.exitCode = 1;
	} else {
		console.log("Released migrations are unchanged.");
	}
}
