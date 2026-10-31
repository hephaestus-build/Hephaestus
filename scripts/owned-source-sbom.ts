import { execFileSync } from "node:child_process";
import { lstatSync } from "node:fs";
import path from "node:path";

import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asArray, asString, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";
import {
	OWNED_FIELDS,
	ownedCandidates,
	ownedFileComponent,
	ownedHeaderValues,
	readOwnedFile,
	scanOwnedCode,
} from "./owned-code-sbom.ts";

const publicRoots = ["webapp/public", "extension/public"];
const assetFields = [...OWNED_FIELDS, "Files"];
const metadataFile = (file: string) =>
	file === "NOTICE.md" || /^LICENSE(?:-[A-Z0-9-]+)?$/u.test(file);

function assetFiles(notice: string, commit: string | undefined, directory: string): string[] {
	const folder = path.posix.dirname(notice);
	const git = (args: string[]) =>
		execFileSync("git", ["--literal-pathspecs", ...args], {
			cwd: directory,
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
			env: environmentWithoutGitRepository(),
		});
	const entries = (
		commit === undefined
			? git(["ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", folder])
			: git(["ls-tree", "-r", "-z", commit, "--", folder])
	)
		.split("\0")
		.filter(Boolean);
	const files = new Set<string>();
	for (const entry of entries) {
		const file = commit === undefined ? entry : entry.slice(entry.indexOf("\t") + 1);
		if (path.posix.dirname(file) !== folder) {
			throw new Error(`${notice}: asset notice directories must be flat`);
		}
		const stats =
			commit === undefined
				? lstatSync(path.join(directory, file), { throwIfNoEntry: false })
				: undefined;
		if (commit === undefined && stats === undefined) {
			continue;
		}
		const regular =
			commit === undefined
				? stats?.isFile() === true
				: ["100644", "100755"].includes(entry.slice(0, 6));
		if (!regular) {
			throw new Error(`${notice}: ${file} is not a regular asset file`);
		}
		if (metadataFile(path.posix.basename(file))) {
			continue;
		}
		files.add(path.posix.basename(file));
	}
	return [...files].toSorted();
}

/** Directory notices describe opaque static files without changing their shipped bytes. */
export function scanOwnedAssets(commit?: string, directory = process.cwd()) {
	const components: ReturnType<typeof ownedFileComponent>[] = [];
	for (const notice of ownedCandidates(publicRoots, commit, directory)) {
		if (path.posix.basename(notice) !== "NOTICE.md") {
			throw new Error(`${notice}: static asset ownership belongs in a sibling NOTICE.md`);
		}
		const available = new Set(assetFiles(notice, commit, directory));
		const text = new TextDecoder("utf-8", { fatal: true }).decode(
			readOwnedFile(notice, commit, directory),
		);
		const markers = [...text.matchAll(/^[\t ]*\*?[\t ]*owned[\t ]*from:/gimu)];
		if (markers.length === 0) {
			throw new Error(`${notice}: missing asset ownership blocks`);
		}
		const declared = new Set<string>();
		for (const [index, marker] of markers.entries()) {
			const block = text.slice(marker.index, markers[index + 1]?.index ?? text.length);
			const values = ownedHeaderValues(notice, block, assetFields);
			const files = asArray(parseJson(asString(values[4], `${notice}: Files`)), `${notice}: Files`);
			if (files.length === 0) {
				throw new Error(`${notice}: Files must name at least one asset`);
			}
			for (const item of files) {
				const file = asString(item, `${notice}: asset basename`);
				if (!available.has(file)) {
					throw new Error(`${notice}: asset does not exist: ${file}`);
				}
				if (declared.has(file)) {
					throw new Error(`${notice}: asset declared more than once: ${file}`);
				}
				declared.add(file);
				const source = `${path.posix.dirname(notice)}/${file}`;
				components.push(
					ownedFileComponent(source, readOwnedFile(source, commit, directory), values),
				);
			}
		}
		for (const file of available) {
			if (!declared.has(file)) {
				throw new Error(`${notice}: asset has no ownership entry: ${file}`);
			}
		}
	}
	return components;
}

export function scanOwnedSource(commit?: string, directory = process.cwd()) {
	return [...scanOwnedCode(commit, directory), ...scanOwnedAssets(commit, directory)];
}

if (import.meta.main) {
	console.log(`owned-source SBOM: ${scanOwnedSource().length} files have ownership entries`);
}
