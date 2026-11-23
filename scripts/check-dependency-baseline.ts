/**
 * Holds the npm dependency counts of every lockfile to `security/dependency-baseline.json`.
 *
 * A count above the baseline fails. Thus, an addition beyond the baseline also raises it in its pull
 * request, and the review sees the cost of the addition beside it. A count below the baseline passes with a
 * notice, so removals, Renovate's included, merge in parallel; any later change can lower the
 * baseline. An exact match would serialize every pull request that changes a count.
 * `docs/contributor/dependencies.mdx` gives the bar for an addition.
 *
 * A package that the repository removed on purpose stays out: a lockfile that holds a name from
 * `security/retired-dependencies.json` fails, whatever the counts, so a removal cannot come back at
 * the same or a lower count.
 *
 * Usage: `node scripts/check-dependency-baseline.ts [--write]`.
 */
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

import { parseAllDocuments } from "yaml";

import { asRecord, isRecord, parseJson } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

export const BASELINE_FILE = "security/dependency-baseline.json";
export const RETIRED_FILE = "security/retired-dependencies.json";

/**
 * Each pnpm lockfile in the checkout, committed or new: the workspace's, and the agent images', which
 * resolve on their own. Found by name, so a new install root enters the counts and the retired check.
 */
export function lockfiles(cwd = process.cwd()): string[] {
	return execFileSync(
		"git",
		[
			"ls-files",
			"-z",
			"--cached",
			"--others",
			"--exclude-standard",
			"--",
			":(glob)**/pnpm-lock.yaml",
		],
		{ cwd, encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES },
	)
		.split("\0")
		.filter(Boolean)
		.toSorted();
}

export interface ImporterCounts {
	direct: { prod: number; dev: number };
	// Unique `name@version` packages reachable from the direct dependencies, the direct ones included.
	transitive: { prod: number; all: number };
}

export interface LockfileCounts {
	// Packages of pnpm's env document: the package manager and the config dependencies, which run
	// during an install. A lockfile without that document has none.
	envPackages: number;
	packages: number;
	importers: Record<string, ImporterCounts>;
}

export type Baseline = Record<string, LockfileCounts>;

const PROD_FIELDS = ["dependencies", "optionalDependencies"] as const;

// A snapshot key is `name@version`, followed by `(peer@version)` groups for each peer it resolved.
const packageId = (snapshotKey: string): string => snapshotKey.replace(/\(.*$/u, "");

// A dependency reference is a version, a version with peers, or a full key when it is an alias
// (`vite: '@voidzero-dev/vite-plus-core@1.3.0'`).
const snapshotKey = (name: string, reference: string): string =>
	/^(?:@[^/@]+\/)?[^@]+@/u.test(packageId(reference)) ? reference : `${name}@${reference}`;

const references = (entry: Record<string, unknown>, fields: readonly string[]) =>
	fields.flatMap((field) => {
		const value = entry[field];
		return isRecord(value) ? Object.entries(value) : [];
	});

// pnpm 12 writes an env document first, with the package manager and the config dependencies. The
// project's resolution is the last document.
const lockDocuments = (text: string, file: string) => {
	const documents = parseAllDocuments(text);
	if (!Array.isArray(documents) || documents.length === 0) {
		throw new TypeError(`${file} has no YAML document`);
	}
	const parsed = documents.map((document, index) => asRecord(document.toJS(), `${file}#${index}`));
	const lock = parsed.at(-1);
	if (lock === undefined) {
		throw new TypeError(`${file} has no YAML document`);
	}
	return { env: parsed.length > 1 ? parsed[0] : undefined, lock };
};

export function countLockfile(text: string, file: string): LockfileCounts {
	const { env, lock } = lockDocuments(text, file);
	const importers = asRecord(lock.importers, `${file}#importers`);
	const snapshots = isRecord(lock.snapshots) ? lock.snapshots : {};
	const packages = isRecord(lock.packages) ? lock.packages : {};

	// The packages reachable from an importer's dependencies. A workspace link (`link:../shared`) is
	// the linked importer, whose production dependencies are reachable too.
	const closure = (importer: string, roots: [string, unknown][]): number => {
		const seen = new Set<string>();
		const ids = new Set<string>();
		const pending: string[] = [];
		const visit = (from: string, dependencies: [string, unknown][]) => {
			for (const [name, value] of dependencies) {
				const reference = isRecord(value) ? value.version : value;
				if (typeof reference !== "string") {
					continue;
				}
				if (!reference.startsWith("link:")) {
					pending.push(snapshotKey(name, reference));
					continue;
				}
				const linked = path.posix.join(from, reference.slice("link:".length));
				const entry = importers[linked];
				if (!isRecord(entry)) {
					throw new TypeError(`${file}: ${from} links ${linked}, which is not an importer`);
				}
				if (!seen.has(`link:${linked}`)) {
					seen.add(`link:${linked}`);
					visit(linked, references(entry, PROD_FIELDS));
				}
			}
		};
		visit(importer, roots);
		for (let key = pending.pop(); key !== undefined; key = pending.pop()) {
			if (seen.has(key)) {
				continue;
			}
			seen.add(key);
			ids.add(packageId(key));
			const snapshot = snapshots[key];
			if (!isRecord(snapshot)) {
				throw new TypeError(`${file}: ${key} is referenced but has no snapshot`);
			}
			for (const [name, reference] of references(snapshot, PROD_FIELDS)) {
				if (typeof reference === "string") {
					pending.push(snapshotKey(name, reference));
				}
			}
		}
		return ids.size;
	};

	const counts: Record<string, ImporterCounts> = {};
	for (const [importer, value] of Object.entries(importers).toSorted(([a], [b]) =>
		a.localeCompare(b),
	)) {
		const entry = asRecord(value, `${file}#importers.${importer}`);
		const prod = references(entry, PROD_FIELDS);
		const dev = references(entry, ["devDependencies"]);
		counts[importer] = {
			direct: { prod: prod.length, dev: dev.length },
			transitive: { prod: closure(importer, prod), all: closure(importer, [...prod, ...dev]) },
		};
	}
	const envPackages =
		env !== undefined && isRecord(env.packages) ? Object.keys(env.packages).length : 0;
	return { envPackages, packages: Object.keys(packages).length, importers: counts };
}

// A package key is `name@version` or `@scope/name@version`; the version can hold `@` (a tarball URL).
const packageName = (key: string): string => {
	const id = packageId(key);
	const at = id.indexOf("@", id.startsWith("@") ? 1 : 0);
	return at === -1 ? id : id.slice(0, at);
};

/**
 * Every package name a lockfile installs: its env document's and its project's, by resolved name.
 * An npm alias (`foo: npm:bar@1`) resolves to `bar@1` there, so it cannot hide a retired name.
 */
export function lockedNames(text: string, file: string): Set<string> {
	const { env, lock } = lockDocuments(text, file);
	const names = new Set<string>();
	for (const packages of [env?.packages, lock.packages]) {
		for (const [key, entry] of isRecord(packages) ? Object.entries(packages) : []) {
			names.add(packageName(key));
			// A package can ship others inside its tarball; pnpm lists their names, not their versions.
			const bundled = isRecord(entry) ? entry.bundledDependencies : undefined;
			for (const name of Array.isArray(bundled) ? bundled : []) {
				if (typeof name === "string") {
					names.add(name);
				}
			}
		}
	}
	// A workspace package that satisfies a dependency is linked under the dependency's name, with no
	// entry in `packages`.
	for (const importer of isRecord(lock.importers) ? Object.values(lock.importers) : []) {
		const dependencies = isRecord(importer)
			? references(importer, [...PROD_FIELDS, "devDependencies"])
			: [];
		for (const [name, value] of dependencies) {
			const reference = isRecord(value) ? value.version : value;
			if (typeof reference === "string" && reference.startsWith("link:")) {
				names.add(name);
			}
		}
	}
	return names;
}

/** The retired packages that a lockfile installs again, each with the reason it was retired. */
export function retiredInLockfiles(
	retired: unknown,
	read: (file: string) => string = (file) => readFileSync(file, "utf8"),
	files: readonly string[] = lockfiles(),
): string[] {
	const reasons = asRecord(retired, RETIRED_FILE);
	const found: string[] = [];
	for (const file of files) {
		const names = lockedNames(read(file), file);
		for (const [name, reason] of Object.entries(reasons)) {
			if (typeof reason !== "string" || reason.trim() === "") {
				throw new TypeError(`${RETIRED_FILE}: ${name} needs the reason it was retired`);
			}
			if (names.has(name)) {
				found.push(`${file} installs ${name}, which is retired: ${reason}`);
			}
		}
	}
	return found;
}

export function measure(
	read: (file: string) => string = (file) => readFileSync(file, "utf8"),
	files: readonly string[] = lockfiles(),
) {
	return Object.fromEntries(
		files.map((file) => [file, countLockfile(read(file), file)]),
	) satisfies Baseline;
}

const flatten = (value: unknown, prefix = ""): Map<string, unknown> =>
	new Map(
		isRecord(value)
			? Object.entries(value).flatMap(([key, child]) => [
					...flatten(child, prefix === "" ? key : `${prefix} › ${key}`),
				])
			: [[prefix, value]],
	);

const describe = (field: string, before: unknown, after: unknown): string =>
	typeof before === "number" && typeof after === "number"
		? `${field}: ${before} → ${after} (${after > before ? "+" : ""}${after - before})`
		: `${field}: ${String(before)} → ${String(after)}`;

/**
 * The counts above the baseline, which fail, and the counts below it, which can lower it. A lockfile
 * or importer that the baseline does not name is a rise; one that the lockfiles no longer have is a fall.
 */
export function compare(
	baseline: unknown,
	measured: Baseline,
): { rises: string[]; falls: string[] } {
	const expected = flatten(baseline);
	const actual = flatten(measured);
	const rises: string[] = [];
	const falls: string[] = [];
	for (const field of [...new Set([...expected.keys(), ...actual.keys()])].toSorted()) {
		const before = expected.get(field);
		const after = actual.get(field);
		if (before === after) {
			continue;
		}
		const fell =
			after === undefined ||
			(typeof before === "number" && typeof after === "number" && after < before);
		(fell ? falls : rises).push(describe(field, before, after));
	}
	return { rises, falls };
}

export const render = (counts: Baseline): string => `${JSON.stringify(counts, null, "\t")}\n`;

if (import.meta.main) {
	const returned = retiredInLockfiles(parseJson(readFileSync(RETIRED_FILE, "utf8")));
	if (returned.length > 0) {
		console.error(
			[
				`A lockfile installs a package from ${RETIRED_FILE}:`,
				...returned.map((line) => `  ${line}`),
				"",
				"Use the replacement. To take a retired package back, remove its entry and meet the bar in",
				"docs/contributor/dependencies.mdx in the same pull request.",
			].join("\n"),
		);
		process.exitCode = 1;
	}
	const measured = measure();
	if (process.argv.includes("--write")) {
		writeFileSync(BASELINE_FILE, render(measured));
		console.log(`Wrote ${BASELINE_FILE}.`);
	} else {
		const { rises, falls } = compare(parseJson(readFileSync(BASELINE_FILE, "utf8")), measured);
		if (rises.length > 0) {
			console.error(
				[
					`The npm dependency counts rise above ${BASELINE_FILE}:`,
					...rises.map((line) => `  ${line}`),
					"",
					"An addition must meet the bar in docs/contributor/dependencies.mdx.",
					"Run `node scripts/check-dependency-baseline.ts --write` and commit the baseline with the change.",
				].join("\n"),
			);
			process.exitCode = 1;
		} else if (falls.length > 0) {
			console.log(
				[
					`The npm dependency counts are below ${BASELINE_FILE}; the baseline can be lowered:`,
					...falls.map((line) => `  ${line}`),
					"Run `node scripts/check-dependency-baseline.ts --write` to lower it.",
				].join("\n"),
			);
		} else {
			console.log(`npm dependency counts match ${BASELINE_FILE}.`);
		}
	}
}
