import { execFileSync } from "node:child_process";

import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const directory = "server/application/src/main/resources/db/changelog/";
const master = "server/application/src/main/resources/db/master.xml";
const archive = "docs/db/archive/v0.77.4/";
const baseline = "0000000000000_baseline_v0_77_4.xml";
// The only permitted rewrite retires this exact released master, not a future migration chain.
const releasedMasterBlob = "5ac453b04033d85484ebf4c85d7f39e11581afc5";

export interface ChangelogSnapshot {
	blobs: ReadonlyMap<string, string>;
	master: string;
}

function includes(xml: string): string[] {
	return xml.match(/<include\b[^>]*\/>/g) ?? [];
}

export function violations(before: ChangelogSnapshot, after: ChangelogSnapshot): string[] {
	const errors: string[] = [];
	const oldIncludes = includes(before.master);
	const newIncludes = includes(after.master);
	const transition =
		before.blobs.get(master) === releasedMasterBlob &&
		!before.blobs.has(`${archive}archive-master.xml`) &&
		after.blobs.get(`${archive}archive-master.xml`) === releasedMasterBlob &&
		newIncludes.length === 1 &&
		newIncludes[0] === `<include file="./changelog/${baseline}" relativeToChangelogFile="true"/>` &&
		!/<includeAll\b/.test(after.master) &&
		after.blobs.has(`${directory}${baseline}`);

	for (const [path, blob] of before.blobs) {
		if (path.startsWith("docs/db/archive/")) {
			if (after.blobs.get(path) !== blob) errors.push(`Archived migration changed: ${path}`);
		} else if (path.startsWith(directory) && after.blobs.get(path) !== blob) {
			const archivedPath = `${archive}changelog/${path.slice(directory.length)}`;
			if (!transition || after.blobs.has(path) || after.blobs.get(archivedPath) !== blob)
				errors.push(`Released migration changed without a byte-identical archive: ${path}`);
		}
	}

	if (transition) {
		for (const path of before.blobs.keys()) {
			if (path.startsWith(directory) && after.blobs.has(path))
				errors.push(`Retired migration remains on the production classpath: ${path}`);
		}
	} else if (oldIncludes.some((include, index) => newIncludes[index] !== include)) {
		errors.push("master.xml is append-only; existing includes must not change or move.");
	}
	return errors;
}

function snapshot(revision: string): ChangelogSnapshot {
	const git = (...args: string[]): string =>
		execFileSync("git", args, { encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES });
	const commit = git("rev-parse", "--verify", "--end-of-options", `${revision}^{commit}`).trim();
	const entries = git("ls-tree", "-r", "-z", commit, "--", directory, master, "docs/db/archive/");
	const blobs = new Map<string, string>();
	for (const entry of entries.split("\0").filter(Boolean)) {
		const [metadata, path] = entry.split("\t");
		const blob = metadata?.split(" ")[2];
		if (path !== undefined && blob !== undefined) blobs.set(path, blob);
	}
	return { blobs, master: blobs.has(master) ? git("show", `${commit}:${master}`) : "" };
}

if (import.meta.main) {
	const [base, head = "HEAD"] = process.argv.slice(2);
	if (!base || process.argv.length > 4)
		throw new Error("Usage: node scripts/check-changelog-immutability.ts <base> [head]");
	const errors = violations(snapshot(base), snapshot(head));
	for (const error of errors) console.error(`::error::${error}`);
	if (errors.length > 0) process.exitCode = 1;
	else console.log("Released migrations and archived history are unchanged.");
}
