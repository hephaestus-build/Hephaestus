import { execFileSync } from "node:child_process";
import { XMLParser } from "fast-xml-parser";
import { SyntaxValidator } from "fast-xml-validator";

import { isSet } from "./lib/env.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const directory = "server/application/src/main/resources/db/changelog/";
const master = "server/application/src/main/resources/db/master.xml";
export interface ChangelogSnapshot {
	blobs: ReadonlyMap<string, string>;
	master: string;
}

const parser = new XMLParser({ ignoreAttributes: false, isArray: (name) => name === "include" });

function includes(xml: string): string[] {
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
	if (changelog.includeAll !== undefined) {
		throw new Error("Use explicit includes, not includeAll.");
	}
	return asArray(changelog.include ?? [], "changelog includes").map((value) => {
		const attributes = asRecord(value, "include attributes");
		asString(attributes["@_file"], "include file");
		return JSON.stringify(Object.entries(attributes).toSorted(([a], [b]) => a.localeCompare(b)));
	});
}

export function violations(before: ChangelogSnapshot, after: ChangelogSnapshot): string[] {
	const errors: string[] = [];
	let oldIncludes: string[];
	let newIncludes: string[];
	try {
		oldIncludes = includes(before.master);
		newIncludes = includes(after.master);
	} catch (error) {
		return [`Invalid changelog: ${error instanceof Error ? error.message : String(error)}`];
	}
	for (const [path, blob] of before.blobs) {
		if (path.startsWith(directory) && after.blobs.get(path) !== blob) {
			errors.push(`Released migration changed: ${path}`);
		}
	}
	if (oldIncludes.some((include, index) => newIncludes[index] !== include)) {
		errors.push("master.xml is append-only; existing includes must not change or move.");
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
	return { blobs, master: blobs.has(master) ? git("show", `${commit}:${master}`) : "" };
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
