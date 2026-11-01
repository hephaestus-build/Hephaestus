import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

import { readChangesets } from "@changesets/read";

import { isSet } from "./lib/env.ts";
import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";
import { migrationGuideSections } from "./lib/migration-guide.ts";
import { CLAUDE_SESSION_PATTERN } from "./lib/model-attribution.ts";
import { parseVersion } from "./lib/release-version.ts";

const changesetEntries = (status: unknown): Map<string, Record<string, unknown>> => {
	const entries = asArray(
		asRecord(status, "changeset status").changesets,
		"changeset status.changesets",
	);
	return new Map(
		entries.map((value, index) => {
			const entry = asRecord(value, `changeset status.changesets[${index}]`);
			return [asString(entry.id, `changeset status.changesets[${index}].id`), entry];
		}),
	);
};

export const verifyMigrationFragment = (file: string, summary: string, content: string): void => {
	if (!summary.includes("**Operators:**")) {
		throw new Error(`${file}: matching changeset summary must contain **Operators:**`);
	}
	// Fenced code blocks may contain `# comment` lines (see MIGRATION.md v0.74.0); only prose
	// outside them is subject to the heading rules.
	const prose = content.replaceAll(/^```[^\n]*\n[\s\S]*?^```[^\S\n]*$/gmu, "");
	const entryHeadings = prose.match(/^#### /gmu) ?? [];
	if (entryHeadings.length !== 1 || !/^#### 🔴 \S/mu.test(prose) || /^#{1,3} /mu.test(prose)) {
		throw new Error(`${file}: must contain exactly one #### 🔴 entry and no level 1-3 headings`);
	}
};

export const verifyChangesetMigration = (
	file: string,
	summary: string,
	fragment: string | undefined,
): void => {
	if (summary.includes("**Operators:**") && fragment === undefined) {
		throw new Error(`${file}: **Operators:** requires .migration/${path.basename(file)}`);
	}
	if (fragment !== undefined) {
		verifyMigrationFragment(`.migration/${path.basename(file)}`, summary, fragment);
	}
};

/**
 * The one changeset that may carry a major bump while the version is 0.x: it cuts 1.0.0.
 * `docs/contributor/release-management.mdx` § Cut 1.0.0 gives the procedure.
 */
const FIRST_STABLE_CHANGESET = "release-1-0-0";

const changesetId = (file: string): string =>
	path.posix.relative(".changeset", file).replace(/\.md$/u, "");

export const verifyChangesets = (
	status: unknown,
	files: readonly string[],
	preOne = true,
): void => {
	const byId = changesetEntries(status);

	for (const file of files) {
		const entry = byId.get(changesetId(file));
		if (!entry) {
			throw new Error(`${file}: Changesets did not parse this file`);
		}

		const summary = asString(entry.summary, `${file} summary`).trim();
		const releases = asArray(entry.releases, `${file} releases`);
		if (summary === "") {
			throw new Error(
				releases.length === 0
					? `${file}: an empty changeset must explain why no release note is needed`
					: `${file}: a release changeset needs a user- or operator-facing summary`,
			);
		}
		// A changeset summary is a release note: it carries no trailer at all, not even a human
		// co-author's, so `Co-authored-by:` is refused outright rather than only the model-named
		// shape `scripts/lib/model-attribution.ts` matches.
		if (/^co-authored-by:/imu.test(summary) || CLAUDE_SESSION_PATTERN.test(summary)) {
			throw new Error(
				`${file}: release notes must not contain Co-authored-by or Claude-Session metadata`,
			);
		}
		if (releases.length === 0) {
			continue;
		}
		if (releases.length !== 1) {
			throw new Error(`${file}: must release only the root hephaestus package`);
		}

		const release = asRecord(releases[0], `${file} release`);
		const name = asString(release.name, `${file} release name`);
		const bump = asString(release.type, `${file} release type`);
		if (name !== "hephaestus") {
			throw new Error(`${file}: must release only the root hephaestus package`);
		}
		if (preOne && bump === "major" && changesetId(file) !== FIRST_STABLE_CHANGESET) {
			throw new Error(
				`${file}: pre-1.0 major changesets are reserved for the deliberate 1.0 release, the changeset ${FIRST_STABLE_CHANGESET}; use minor`,
			);
		}
		if (bump === "major" && !summary.includes("**Operators:**")) {
			throw new Error(`${file}: a major changeset must contain **Operators:**`);
		}
	}
};

async function verifyParsedChangesets(
	status: unknown,
	files: readonly string[],
	rootDir: string,
): Promise<void> {
	const root = asRecord(await readJsonFile(path.join(rootDir, "package.json")), "package.json");
	verifyChangesets(status, files, asString(root.version, "package.json version").startsWith("0."));
	const entries = changesetEntries(status);
	const consumed: string[] = [];
	for (const file of files) {
		const entry = entries.get(changesetId(file));
		if (entry) {
			const summary = asString(entry.summary, `${file} summary`);
			const fragmentFile = path.join(rootDir, ".migration", path.basename(file));
			const fragment = existsSync(fragmentFile) ? readFileSync(fragmentFile, "utf8") : undefined;
			// In pre mode, the Version PR keeps each consumed changeset under .changeset/pre/ and moves its
			// fragment into the candidate's MIGRATION.md section, so that section must hold its entry.
			if (
				isConsumedPrerelease(file) &&
				fragment === undefined &&
				summary.includes("**Operators:**")
			) {
				consumed.push(file);
				continue;
			}
			verifyChangesetMigration(file, summary, fragment);
		}
	}
	if (consumed.length > 0) {
		verifyConsumedMigrationEntries(
			consumed,
			asString(root.version, "package.json version"),
			readFileSync(path.join(rootDir, "MIGRATION.md"), "utf8"),
		);
	}
}

const isConsumedPrerelease = (file: string): boolean => changesetId(file).startsWith("pre/");

/**
 * Each fragment holds exactly one `#### 🔴` entry, and `release:version` moves each one into the
 * `### v<version>` section of its candidate. MIGRATION.md keeps no slug, so this counts: the candidate
 * sections of the current prerelease line must hold an entry for every consumed operator changeset.
 */
export const verifyConsumedMigrationEntries = (
	files: readonly string[],
	version: string,
	migration: string,
): void => {
	const current = parseVersion(version);
	if (current?.rc === undefined) {
		throw new Error(
			`${files[0] ?? ".changeset/pre"}: a consumed prerelease changeset needs a prerelease package version, not ${version}`,
		);
	}
	const base = `${current.major}.${current.minor}.${current.patch}`;
	const entries = migrationGuideSections(migration)
		.filter((section) => {
			const candidate = section.heading.startsWith("### v")
				? parseVersion(section.heading.slice(5))
				: undefined;
			return (
				candidate?.rc !== undefined &&
				`${candidate.major}.${candidate.minor}.${candidate.patch}` === base
			);
		})
		.flatMap((section) =>
			section.content
				.replaceAll(/^```[^\n]*\n[\s\S]*?^```[^\S\n]*$/gmu, "")
				.split("\n")
				.filter((line) => line.startsWith("#### 🔴 ")),
		);
	if (entries.length < files.length) {
		throw new Error(
			`${files.join(", ")}: **Operators:** was consumed in pre mode, but MIGRATION.md's ${base} candidate sections hold ${entries.length} #### 🔴 entries for ${files.length} consumed operator changesets`,
		);
	}
};

export async function verifyLocalChangesets(rootDir: string): Promise<void> {
	const changesets = await readChangesets(rootDir);
	await verifyParsedChangesets(
		{ changesets },
		changesets.map(({ id }) => `.changeset/${id}.md`),
		rootDir,
	);
}

async function main(): Promise<void> {
	const [statusJson, ...arguments_] = process.argv.slice(2);
	if (!isSet(statusJson)) {
		throw new Error("usage: verify-changesets.ts --local | <changeset-status-json> <files...>");
	}
	if (statusJson === "--local") {
		if (arguments_.length > 0) {
			throw new Error("--local reads all pending changesets and takes no file arguments");
		}
		await verifyLocalChangesets(process.cwd());
		return;
	}
	const status = await readJsonFile(statusJson);
	if (arguments_[0] === "--migration") {
		const entries = changesetEntries(status);
		for (const file of arguments_.slice(1)) {
			const entry = entries.get(path.basename(file, ".md"));
			if (!entry) {
				throw new Error(`${file}: requires a changeset with the same slug`);
			}
			const summary = asString(entry.summary, `${file} summary`);
			verifyMigrationFragment(file, summary, readFileSync(file, "utf8"));
		}
	} else {
		await verifyParsedChangesets(status, arguments_, process.cwd());
	}
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		console.error(`::error::${error instanceof Error ? error.message : String(error)}`);
		process.exitCode = 1;
	}
}
