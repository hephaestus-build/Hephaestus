import { execFileSync } from "node:child_process";
import { XMLParser } from "fast-xml-parser";
import { SyntaxValidator } from "fast-xml-validator";

import nodePath from "node:path";

import { CHANGELOG_DIRECTORY, NEXT_CHANGELOG_DIRECTORY, migrationTime } from "./lib/changelog.ts";
import { isSet } from "./lib/env.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const directory = `${CHANGELOG_DIRECTORY}/`;
const master = "server/application/src/main/resources/db/master.xml";
export interface ChangelogSnapshot {
	blobs: ReadonlyMap<string, string>;
	master: string;
}

const parser = new XMLParser({
	ignoreAttributes: false,
	isArray: (name) => name === "include" || name === "includeAll",
});

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

export function violations(before: ChangelogSnapshot, after: ChangelogSnapshot): string[] {
	const errors: string[] = [];
	let oldIncludes: string[];
	let newIncludes: string[];
	try {
		oldIncludes = includes(before.master);
		newIncludes = includes(after.master, true);
	} catch (error) {
		return [`Invalid changelog: ${error instanceof Error ? error.message : String(error)}`];
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
	return { blobs, master: blobs.has(master) ? git("show", `${commit}:${master}`) : "" };
}

if (import.meta.main) {
	const [base, head = "HEAD"] = process.argv.slice(2);
	if (!isSet(base) || process.argv.length > 4) {
		throw new Error("Usage: node scripts/check-changelog-immutability.ts <base> [head]");
	}
	const before = snapshot(base);
	// The includeAll cutover moves unreleased files; its fixed prefix ends at the last published release.
	const baseline = before.master.includes("<includeAll") ? before : snapshot("v0.95.1");
	const errors = violations(baseline, snapshot(head));
	for (const error of errors) {
		console.error(`::error::${error}`);
	}
	if (errors.length > 0) {
		process.exitCode = 1;
	} else {
		console.log("Released migrations are unchanged.");
	}
}
