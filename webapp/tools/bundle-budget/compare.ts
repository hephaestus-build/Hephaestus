/**
 * Compares a build's bundle with the build of the commit it merges onto:
 * `node tools/bundle-budget/compare.ts <metadata> <base metadata> <base name> <base accepted-growth directory>`.
 * `scripts/verify-webapp-build.ts` makes both builds with one install. With `BUNDLE_BUDGET=update`,
 * it writes this change's acceptances, by `BUNDLE_ACCEPTED_BY`, to its own file in `accepted-growth/`.
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
	type Acceptance,
	acceptedGrowth,
	acceptGrowth,
	type BundleSizes,
	bundleMetadata,
	growthViolations,
	measure,
	ownAcceptances,
	TOLERANCE_BYTES,
} from "./bundle-budget.ts";

const UPDATE_COMMAND =
	'BUNDLE_BUDGET=update BUNDLE_ACCEPTED_REASON="<why the growth is worth it>" vp run verification:webapp-build';

/** The acceptances in one change's file. */
function acceptancesOf(file: string): Acceptance[] {
	const parsed = acceptedGrowth.safeParse(JSON.parse(readFileSync(file, "utf8")));
	if (!parsed.success) {
		throw new Error(`${file} is not an accepted-growth file: ${parsed.error.message}`);
	}
	return parsed.data.accepted;
}

/** The `.json` files of `directory`, one per change; a base from before the directory has none. */
function changeFiles(directory: string): string[] {
	return existsSync(directory)
		? readdirSync(directory)
				.filter((name) => name.endsWith(".json"))
				.sort()
		: [];
}

/** The acceptances of every change in `directory`, as one set. */
export function acceptancesIn(directory: string): Acceptance[] {
	return changeFiles(directory).flatMap((name) => acceptancesOf(path.join(directory, name)));
}

/** The file of the change that `acceptedBy` names: `pr-<number>.json`, or the branch's name. */
export function acceptanceFile(acceptedBy: string): string {
	const pullRequest = /\/pull\/(?<number>\d+)$/u.exec(acceptedBy)?.groups?.number;
	if (pullRequest !== undefined) {
		return `pr-${pullRequest}.json`;
	}
	const slug = acceptedBy
		.replace(/^branch /u, "")
		.toLowerCase()
		.replaceAll(/[^a-z0-9]+/gu, "-")
		.replaceAll(/^-|-$/gu, "");
	if (slug === "") {
		throw new Error(`Cannot name an accepted-growth file after '${acceptedBy}'`);
	}
	return `${slug}.json`;
}

/**
 * Writes this change's acceptances to its own file. It removes the files of earlier changes whose
 * acceptances the base build already holds: their growth is in the base, so they grant nothing. A
 * removed file is in the base, so a parallel change never adds or edits it. Git history keeps it.
 */
export function recordAcceptances(
	directory: string,
	acceptedBy: string,
	accepted: readonly Acceptance[],
	base: readonly Acceptance[],
): { file: string; removed: string[] } {
	mkdirSync(directory, { recursive: true });
	const own = acceptanceFile(acceptedBy);
	const removed = changeFiles(directory).filter(
		(name) =>
			name !== own && ownAcceptances(acceptancesOf(path.join(directory, name)), base).length === 0,
	);
	for (const name of removed) {
		rmSync(path.join(directory, name));
	}
	const file = path.join(directory, own);
	if (accepted.length === 0) {
		rmSync(file, { force: true });
	} else {
		writeFileSync(file, `${JSON.stringify({ accepted }, null, "\t")}\n`);
	}
	return { file, removed };
}

function sizesOf(file: string): BundleSizes {
	return measure(bundleMetadata.parse(JSON.parse(readFileSync(file, "utf8"))).chunks).sizes;
}

const signed = (bytes: number) => (bytes > 0 ? `+${bytes}` : String(bytes));

/** What the change does to each load, largest growth first. */
export function summary(head: BundleSizes, base: BundleSizes, baseName: string): string[] {
	const changed = Object.entries(head.lazy)
		.map(([facade, now]) => [facade, now - (base.lazy[facade] ?? 0)] as const)
		.filter(([, delta]) => delta !== 0)
		.sort(([, a], [, b]) => b - a)
		.slice(0, 5);
	return [
		`Bundle against ${baseName}; a change may grow each load by ${TOLERANCE_BYTES} gzip bytes:`,
		`  entry ${head.entry} (${signed(head.entry - base.entry)})`,
		...changed.map(([facade, delta]) => `  ${facade} ${head.lazy[facade] ?? 0} (${signed(delta)})`),
	];
}

if (import.meta.main) {
	// One file per change, so parallel changes that each accept growth never edit the same file. The
	// check reads all files as one set.
	const ACCEPTED = fileURLToPath(new URL("accepted-growth/", import.meta.url));
	const [headFile, baseFile, baseName, baseAccepted] = process.argv.slice(2);
	if (
		headFile === undefined ||
		baseFile === undefined ||
		baseName === undefined ||
		baseAccepted === undefined
	) {
		throw new Error(
			"Usage: node tools/bundle-budget/compare.ts <metadata> <base metadata> <base name> <base accepted-growth directory>",
		);
	}
	const head = sizesOf(headFile);
	const base = sizesOf(baseFile);
	process.stdout.write(`${summary(head, base, baseName).join("\n")}\n`);
	const acceptedBy = process.env.BUNDLE_ACCEPTED_BY;
	if (process.env.BUNDLE_BUDGET === "update" && acceptedBy !== undefined && acceptedBy !== "") {
		const reason = process.env.BUNDLE_ACCEPTED_REASON?.trim() ?? "";
		if (reason === "") {
			throw new Error(`Say why the growth is worth it: ${UPDATE_COMMAND}`);
		}
		const accepted = acceptGrowth(head, base, acceptedBy, reason);
		const { file, removed } = recordAcceptances(
			ACCEPTED,
			acceptedBy,
			accepted,
			acceptancesIn(baseAccepted),
		);
		process.stdout.write(
			`Recorded ${accepted.length} accepted growth for ${acceptedBy} in ${file}${removed.length > 0 ? `; removed the spent ${removed.join(", ")}` : ""}\n`,
		);
	} else {
		const own = ownAcceptances(acceptancesIn(ACCEPTED), acceptancesIn(baseAccepted));
		const violations = growthViolations(head, base, own);
		if (violations.length > 0) {
			process.stderr.write(
				`${[
					...violations,
					`When the growth is intended, run \`${UPDATE_COMMAND}\` and commit what it changes in webapp/tools/bundle-budget/accepted-growth/.`,
				].join("\n")}\n`,
			);
			process.exitCode = 1;
		}
	}
}
