/**
 * Compares a build's bundle with the build of the commit it merges onto:
 * `node tools/bundle-budget/compare.ts <metadata> <base metadata> <base name> <base accepted-growth.json>`.
 * `scripts/verify-webapp-build.ts` makes both builds with one install. With `BUNDLE_BUDGET=update`,
 * it writes this change's acceptances, by `BUNDLE_ACCEPTED_BY`, to `accepted-growth.json`.
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";

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

const ACCEPTED = new URL("accepted-growth.json", import.meta.url);

const UPDATE_COMMAND = "BUNDLE_BUDGET=update vp run verification:webapp-build";

/** The acceptances in `file`; a base from before this file has none. */
function acceptancesIn(file: string | URL): Acceptance[] {
	if (!existsSync(file)) {
		return [];
	}
	const parsed = acceptedGrowth.safeParse(JSON.parse(readFileSync(file, "utf8")));
	return parsed.success ? parsed.data.accepted : [];
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
	const [headFile, baseFile, baseName, baseAccepted] = process.argv.slice(2);
	if (
		headFile === undefined ||
		baseFile === undefined ||
		baseName === undefined ||
		baseAccepted === undefined
	) {
		throw new Error(
			"Usage: node tools/bundle-budget/compare.ts <metadata> <base metadata> <base name> <base accepted-growth.json>",
		);
	}
	const head = sizesOf(headFile);
	const base = sizesOf(baseFile);
	process.stdout.write(`${summary(head, base, baseName).join("\n")}\n`);
	const acceptedBy = process.env.BUNDLE_ACCEPTED_BY;
	if (process.env.BUNDLE_BUDGET === "update" && acceptedBy !== undefined && acceptedBy !== "") {
		const accepted = acceptGrowth(head, base, acceptedBy);
		writeFileSync(ACCEPTED, `${JSON.stringify({ accepted }, null, "\t")}\n`);
		process.stdout.write(
			`Recorded ${accepted.length} accepted growth for ${acceptedBy} in ${ACCEPTED.pathname}\n`,
		);
	} else {
		const own = ownAcceptances(acceptancesIn(ACCEPTED), acceptancesIn(baseAccepted));
		const violations = growthViolations(head, base, own);
		if (violations.length > 0) {
			process.stderr.write(
				`${[
					...violations,
					`When the growth is intended, run \`${UPDATE_COMMAND}\` and commit webapp/tools/bundle-budget/accepted-growth.json.`,
				].join("\n")}\n`,
			);
			process.exitCode = 1;
		}
	}
}
