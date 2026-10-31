import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { XMLParser } from "fast-xml-parser";
import { SyntaxValidator } from "fast-xml-validator";

import { asArray, asRecord, asString, isRecord } from "./json.ts";

const parser = new XMLParser({
	ignoreAttributes: false,
	isArray: (name) =>
		name === "include" || name === "includeAll" || name === "changeSet" || name === "sql",
});
const literal =
	/^[\t ]*INSERT INTO _cutover_program VALUES\('(?<sourceSha>[a-f0-9]{64})',convert_from\(decode\('(?<source>[A-Za-z0-9+/=]+)','base64'\),'UTF8'\),'(?<targetSha>[a-f0-9]{64})',convert_from\(decode\('(?<target>[A-Za-z0-9+/=]+)','base64'\),'UTF8'\)\);[\t ]*$/u;
const sha256 = (source: Uint8Array): string => createHash("sha256").update(source).digest("hex");

function recordTarget(line: string, targets: Map<string, Buffer>): void {
	const match = literal.exec(line);
	if (match === null) {
		return;
	}
	const groups = asRecord(match.groups, "migration literal");
	const source = Buffer.from(asString(groups.source, "migration source"), "base64");
	const target = Buffer.from(asString(groups.target, "migration target"), "base64");
	if (sha256(source) !== groups.sourceSha || sha256(target) !== groups.targetSha) {
		throw new Error("Migration program literal does not match its source or target SHA-256");
	}
	targets.set(sha256(target), target);
}

function targetsInSql(sql: string, targets: Map<string, Buffer>): void {
	let commentDepth = 0;
	let quoted = false;
	let dollarDelimiter: string | undefined;
	for (const line of sql.split("\n")) {
		if (commentDepth === 0 && !quoted && dollarDelimiter === undefined) {
			recordTarget(line, targets);
		}
		for (let i = 0; i < line.length; i += 1) {
			const pair = line.slice(i, i + 2);
			if (commentDepth > 0) {
				if (pair === "/*") {
					commentDepth += 1;
					i += 1;
				} else if (pair === "*/") {
					commentDepth -= 1;
					i += 1;
				}
			} else if (dollarDelimiter !== undefined) {
				if (line.startsWith(dollarDelimiter, i)) {
					i += dollarDelimiter.length - 1;
					dollarDelimiter = undefined;
				}
			} else if (quoted) {
				if (pair === "''") {
					i += 1;
				} else if (line[i] === "'") {
					quoted = false;
				}
			} else if (pair === "--") {
				break;
			} else if (pair === "/*") {
				commentDepth += 1;
				i += 1;
			} else if (line[i] === "'") {
				quoted = true;
			} else if (line[i] === "$") {
				const delimiter = /^\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$/u.exec(line.slice(i))?.[0];
				if (delimiter !== undefined) {
					dollarDelimiter = delimiter;
					i += delimiter.length - 1;
				}
			}
		}
	}
}

/** Only wired executable SQL owns a migration-only resource; a fixture or quoted example does not. */
export async function migrationOwnedPracticeScripts(
	resourcesRoot: string,
	scripts: ReadonlySet<string>,
): Promise<ReadonlySet<string>> {
	const targets = new Map<string, Buffer>();
	const seen = new Set<string>();
	async function visit(file: string): Promise<void> {
		if (seen.has(file)) {
			return;
		}
		seen.add(file);
		const xml = await readFile(file, "utf8");
		SyntaxValidator.validate(xml);
		const document: unknown = parser.parse(xml);
		const raw = asRecord(document, file).databaseChangeLog;
		if (raw === "") {
			return;
		}
		const changelog = asRecord(raw, `${file} databaseChangeLog`);
		for (const value of asArray(changelog.include ?? [], "changelog includes")) {
			const include = asRecord(value, "changelog include");
			const base =
				include["@_relativeToChangelogFile"] === "true" ? path.dirname(file) : resourcesRoot;
			await visit(path.resolve(base, asString(include["@_file"], "changelog include file")));
		}
		for (const value of asArray(changelog.includeAll ?? [], "automatic changelog includes")) {
			const include = asRecord(value, "automatic changelog include");
			if (include["@_maxDepth"] !== "1" || include["@_endsWithFilter"] !== ".xml") {
				throw new Error("Migration ownership supports the repository's flat XML includeAll");
			}
			const base =
				include["@_relativeToChangelogFile"] === "true" ? path.dirname(file) : resourcesRoot;
			const directory = path.resolve(base, asString(include["@_path"], "includeAll path"));
			const entries = await readdir(directory, { withFileTypes: true }).catch((error: unknown) => {
				if (
					isRecord(error) &&
					error.code === "ENOENT" &&
					include["@_errorIfMissingOrEmpty"] === "false"
				) {
					return [];
				}
				throw error;
			});
			const files = entries
				.filter((entry) => entry.isFile() && entry.name.endsWith(".xml"))
				.map((entry) => entry.name)
				.toSorted();
			for (const fileName of files) {
				await visit(path.join(directory, fileName));
			}
		}

		for (const value of asArray(changelog.changeSet ?? [], "changelog changesets")) {
			const changeSet = asRecord(value, "changelog changeset");
			for (const sql of asArray(changeSet.sql ?? [], "changeset SQL")) {
				targetsInSql(
					typeof sql === "string" ? sql : asString(asRecord(sql, "SQL")["#text"], "SQL text"),
					targets,
				);
			}
		}
	}
	await visit(path.join(resourcesRoot, "db/master.xml"));
	const owned = new Set<string>();
	for (const script of scripts) {
		const bytes = await readFile(path.join(resourcesRoot, script));
		if (targets.get(sha256(bytes))?.equals(bytes) === true) {
			owned.add(script);
		}
	}
	return owned;
}
