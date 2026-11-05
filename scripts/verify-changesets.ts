import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

import { readChangesets } from "@changesets/read";

import { CHART } from "./lib/deploy-topology.ts";
import { isSet } from "./lib/env.ts";
import { asArray, asRecord, asString, isRecord, parseJson, readJsonFile } from "./lib/json.ts";
import { migrationGuideSections } from "./lib/migration-guide.ts";
import { CLAUDE_SESSION_PATTERN } from "./lib/model-attribution.ts";
import { parseVersion } from "./lib/release-version.ts";
import { releaseNoteProblems } from "./release-note-rules.ts";

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

/**
 * Whether a `major` changeset would still cut 1.0.0: the version is 0.x or a 1.0.0 release candidate. A major
 * merged then could outlive prerelease mode and make the first release after 1.0.0 a 2.0.0, so only
 * `release-1-0-0` may carry one. An unreadable version counts as before.
 */
export function beforeFirstStable(version: string): boolean {
	const parsed = parseVersion(version);
	return (
		parsed === undefined ||
		parsed.major === 0 ||
		(parsed.major === 1 && parsed.minor === 0 && parsed.patch === 0 && parsed.rc !== undefined)
	);
}

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
		// The Version PR keeps a consumed changeset under .changeset/pre/, under the same name.
		if (preOne && bump === "major" && path.posix.basename(file, ".md") !== FIRST_STABLE_CHANGESET) {
			throw new Error(
				`${file}: until 1.0.0 is out, a major changeset is reserved for the 1.0 release, the changeset ${FIRST_STABLE_CHANGESET}; use minor (.changeset/README.md)`,
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
	verifyChangesets(
		status,
		files,
		beforeFirstStable(asString(root.version, "package.json version")),
	);
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

interface ValueLimits {
	maximum?: number;
	minimum?: number;
	values?: unknown[];
}

/**
 * Each name that a JSON schema accepts, as a dotted path, and what each path accepts. `[]` marks the
 * items of a list. A `$ref` resolves against every definition in the file, because the catalog schema
 * nests its own. Branches of one path merge to what any of them accepts.
 */
const schemaValues = (schema: unknown) => {
	const root = asRecord(schema, "schema");
	const definitions = new Map<string, unknown>();
	const collect = (raw: unknown): void => {
		if (!isRecord(raw) && !Array.isArray(raw)) {
			return;
		}
		for (const key of ["definitions", "$defs"]) {
			const named = isRecord(raw) ? raw[key] : undefined;
			for (const [name, value] of Object.entries(isRecord(named) ? named : {})) {
				definitions.set(name, value);
			}
		}
		for (const value of Object.values(raw)) {
			collect(value);
		}
	};
	collect(root);
	const names = new Set<string>();
	const limits = new Map<string, ValueLimits>();
	const record = (name: string, node: Record<string, unknown>): void => {
		const known = limits.get(name) ?? {};
		const { maximum, minimum } = node;
		const values: unknown[] | undefined = Array.isArray(node.enum) ? node.enum : undefined;
		limits.set(name, {
			...known,
			...(typeof maximum === "number" && { maximum: Math.max(maximum, known.maximum ?? maximum) }),
			...(typeof minimum === "number" && { minimum: Math.min(minimum, known.minimum ?? minimum) }),
			...(values !== undefined && { values: [...(known.values ?? []), ...values] }),
		});
	};
	const walk = (raw: unknown, prefix: string): void => {
		if (!isRecord(raw)) {
			return;
		}
		const node =
			typeof raw.$ref === "string" ? definitions.get(raw.$ref.split("/").at(-1) ?? "") : raw;
		if (!isRecord(node)) {
			return;
		}
		record(prefix, node);
		for (const [name, child] of Object.entries(isRecord(node.properties) ? node.properties : {})) {
			const key = prefix === "" ? name : `${prefix}.${name}`;
			names.add(key);
			walk(child, key);
		}
		walk(node.items, `${prefix}[]`);
		for (const branch of [node.then, node.else, node.allOf, node.anyOf, node.oneOf].flat()) {
			walk(branch, prefix);
		}
	};
	walk(root, "");
	return { names, limits };
};

/** Each name that the base schema has and the head schema lacks: `a` once for a removed group `a.*`. */
export const removedChartValues = (base: unknown, head: unknown): string[] => {
	const { names: kept } = schemaValues(head);
	const removed = [...schemaValues(base).names].filter((name) => !kept.has(name));
	return removed.filter(
		(name) =>
			!removed.some((other) => name.startsWith(`${other}.`) || name.startsWith(`${other}[]`)),
	);
};

/** Each kept value that accepts less than before: a lower maximum, a higher minimum or a lost enum value. */
export const narrowedChartValues = (base: unknown, head: unknown): string[] => {
	const { limits: kept } = schemaValues(head);
	return [...schemaValues(base).limits].flatMap(([name, before]) => {
		const after = kept.get(name);
		if (after === undefined) {
			return [];
		}
		const lines: string[] = [];
		if (after.maximum !== undefined && (before.maximum ?? Infinity) > after.maximum) {
			lines.push(`${name}: maximum ${String(before.maximum)} became ${after.maximum}`);
		}
		if (after.minimum !== undefined && (before.minimum ?? -Infinity) < after.minimum) {
			lines.push(`${name}: minimum ${String(before.minimum)} became ${after.minimum}`);
		}
		const lostValues = (before.values ?? []).filter(
			(value) => after.values !== undefined && !after.values.includes(value),
		);
		return [
			...lines,
			...lostValues.map((value) => `${name}: value ${JSON.stringify(value)} is gone`),
		];
	});
};

/**
 * The chart refuses a value that its schema lacks, so an operator who still sets a removed value
 * cannot upgrade. The repository cannot read an operator's values, so it requires the warning.
 */
export const verifyRemovedChartValues = (base: unknown, head: unknown, notes: string): void => {
	const unnamed = removedChartValues(base, head).filter((key) => !notes.includes(`\`${key}\``));
	if (unnamed.length > 0) {
		throw new Error(
			`The chart no longer accepts ${unnamed.join(", ")}: name each value in backticks in this pull request's changeset or migration fragment, with the action an operator takes`,
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

const git = (...command: string[]) =>
	execFileSync("git", command, { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 });

async function main(): Promise<void> {
	const [statusJson, ...arguments_] = process.argv.slice(2);
	if (!isSet(statusJson)) {
		throw new Error(
			"usage: verify-changesets.ts --local | --release-notes <base> | --removed-chart-values <base> <files...> | <changeset-status-json> <files...>",
		);
	}
	if (statusJson === "--local") {
		if (arguments_.length > 0) {
			throw new Error("--local reads all pending changesets and takes no file arguments");
		}
		await verifyLocalChangesets(process.cwd());
		return;
	}
	if (statusJson === "--release-notes") {
		const [base] = arguments_;
		if (!isSet(base)) {
			throw new Error("--release-notes needs the base commit");
		}
		const problems = releaseNoteProblems(base);
		if (problems.length > 0) {
			throw new Error(problems.join("\n"));
		}
		return;
	}
	if (statusJson === "--removed-chart-values") {
		const [base, ...notes] = arguments_;
		if (!isSet(base)) {
			throw new Error("--removed-chart-values needs the base commit");
		}
		const schema = path.posix.join(CHART, "values.schema.json");
		const mergeBase = git("merge-base", base, "HEAD").trim();
		verifyRemovedChartValues(
			parseJson(git("show", `${mergeBase}:${schema}`)),
			parseJson(readFileSync(schema, "utf8")),
			notes.map((file) => readFileSync(file, "utf8")).join("\n"),
		);
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
