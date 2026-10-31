/**
 * Holds the npm dependency counts of every lockfile to `security/dependency-baseline.json`.
 *
 * The counts must match exactly. Thus, a pull request that adds a package also changes the baseline,
 * and the review sees the cost of the addition beside it. A pull request that removes a package
 * lowers the baseline, so the next addition cannot reuse the space silently.
 * `docs/contributor/dependencies.md` gives the bar for an addition.
 *
 * Usage: `node scripts/check-dependency-baseline.ts [--write]`.
 */
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

import { parseAllDocuments } from "yaml";

import { asRecord, isRecord, parseJson } from "./lib/json.ts";

export const BASELINE_FILE = "security/dependency-baseline.json";

// Each lockfile that the repository installs from. The agent images resolve on their own.
export const LOCKFILES = [
	"pnpm-lock.yaml",
	"docker/agents/pi/pnpm-lock.yaml",
	"docker/agents/practice-scripts/pnpm-lock.yaml",
] as const;

export interface ImporterCounts {
	direct: { prod: number; dev: number };
	// Unique `name@version` packages reachable from the direct dependencies, the direct ones included.
	transitive: { prod: number; all: number };
}

export interface LockfileCounts {
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

// pnpm 12 writes the package manager's own resolution as a first YAML document. The project's
// resolution is the last one.
const projectLock = (text: string, file: string): Record<string, unknown> => {
	const documents = parseAllDocuments(text);
	if (!Array.isArray(documents) || documents.length === 0) {
		throw new TypeError(`${file} has no YAML document`);
	}
	return asRecord(documents.at(-1)?.toJS(), file);
};

export function countLockfile(text: string, file: string): LockfileCounts {
	const lock = projectLock(text, file);
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
	return { packages: Object.keys(packages).length, importers: counts };
}

export function measure(read: (file: string) => string = (file) => readFileSync(file, "utf8")) {
	return Object.fromEntries(
		LOCKFILES.map((file) => [file, countLockfile(read(file), file)]),
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

/** Every count that differs between the baseline and the measurement, as one line each. */
export function compare(baseline: unknown, measured: Baseline): string[] {
	const expected = flatten(baseline);
	const actual = flatten(measured);
	const fields = [...new Set([...expected.keys(), ...actual.keys()])].toSorted();
	return fields.flatMap((field) => {
		const before = expected.get(field);
		const after = actual.get(field);
		if (before === after) {
			return [];
		}
		const change =
			typeof before === "number" && typeof after === "number"
				? `${before} → ${after} (${after > before ? "+" : ""}${after - before})`
				: `${String(before)} → ${String(after)}`;
		return [`${field}: ${change}`];
	});
}

export const render = (counts: Baseline): string => `${JSON.stringify(counts, null, "\t")}\n`;

if (import.meta.main) {
	const measured = measure();
	if (process.argv.includes("--write")) {
		writeFileSync(BASELINE_FILE, render(measured));
		console.log(`Wrote ${BASELINE_FILE}.`);
	} else {
		const differences = compare(parseJson(readFileSync(BASELINE_FILE, "utf8")), measured);
		if (differences.length > 0) {
			console.error(
				[
					`The npm dependency counts differ from ${BASELINE_FILE}:`,
					...differences.map((line) => `  ${line}`),
					"",
					"An addition must meet the bar in docs/contributor/dependencies.md.",
					"Run `node scripts/check-dependency-baseline.ts --write` and commit the baseline with the change.",
				].join("\n"),
			);
			process.exitCode = 1;
		} else {
			console.log(`npm dependency counts match ${BASELINE_FILE}.`);
		}
	}
}
