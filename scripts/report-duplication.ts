/**
 * How much production code is duplicated, how much of it is new since a base commit, and how much
 * of the code added since then was moved rather than written. A report only: nothing fails on it.
 * docs/contributor/ci-cd.mdx § Duplicated and moved code.
 */
import { execFileSync } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { parseArgs } from "node:util";

import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { asArray, asRecord, asString, readJsonFile } from "./lib/json.ts";
import { installPinnedBinary, toolCache } from "./lib/pinned-binary.ts";
import { CAPTURE_LIMIT_BYTES, output } from "./lib/process.ts";

/** The production code of each tree. Generated clients, tests and stories are left out. */
export const AREAS = [
	{ area: "server", paths: ["server/application/src/main"] },
	{ area: "webapp", paths: ["webapp/src"] },
	{ area: "extension", paths: ["extension/src"] },
	{ area: "scripts", paths: ["scripts"] },
] as const;
const IGNORED = [
	"**/src/api/**",
	"**/*.gen.ts",
	"**/test/**",
	"**/*.test.ts",
	"**/*.test.tsx",
	// The database drills in scripts/ are tests named with a hyphen.
	"**/*-test.ts",
	"**/*.stories.tsx",
	"**/stories/**",
	"**/mocks/**",
];
const FORMATS = "java,typescript,tsx,javascript";
/** The file extensions of those formats, for the moved-code diff. */
const EXTENSIONS = ["java", "ts", "tsx", "js"];
/** jscpd's smallest clone, also the fewest logic lines a clone needs to count. */
const MIN_LINES = 5;

const asCount = (value: unknown, label: string): number => {
	if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
		throw new TypeError(`${label} must be a count`);
	}
	return value;
};

export interface Clone {
	lines: number;
	isNew: boolean;
	fragment: string;
}

export interface AreaReport {
	area: string;
	lines: number;
	clones: number;
	cloneLines: number;
	newClones: number;
	newCloneLines: number;
}

// A block of imports repeats in every file that needs the same types. It is not duplicated logic.
const IMPORT_LINE =
	/^\s*(?:import\b|package\b|export\s+(?:type\s+)?\{.*\}\s*from\b|\}\s*from\b|[\w$]+,\s*$|$)/u;

/**
 * Whether a clone holds at least `MIN_LINES` lines besides imports. A clone of an import block often
 * runs a line or two further, into a comment or an annotation, and is still import noise.
 */
export function isLogic(clone: Clone): boolean {
	return clone.fragment.split("\n").filter((line) => !IMPORT_LINE.test(line)).length >= MIN_LINES;
}

/** One area's totals from a jscpd JSON report, without clones made only of import lines. */
export function summarize(area: string, report: unknown): AreaReport {
	const root = asRecord(report, "jscpd report");
	const total = asRecord(asRecord(root.statistics, "statistics").total, "statistics.total");
	const clones = asArray(root.duplicates, "duplicates")
		.map((value, index) => {
			const clone = asRecord(value, `duplicates[${String(index)}]`);
			return {
				lines: asCount(clone.lines, "clone lines"),
				isNew: clone.isNew === true,
				fragment: asString(clone.fragment, "clone fragment"),
			};
		})
		.filter(isLogic);
	const summary = {
		area,
		lines: asCount(total.lines, "statistics.total.lines"),
		clones: 0,
		cloneLines: 0,
		newClones: 0,
		newCloneLines: 0,
	};
	for (const clone of clones) {
		summary.clones += 1;
		summary.cloneLines += clone.lines;
		if (clone.isNew) {
			summary.newClones += 1;
			summary.newCloneLines += clone.lines;
		}
	}
	return summary;
}

// `--color-moved=blocks` marks a moved block of at least 20 alphanumeric characters, so a moved
// closing brace alone does not count. The colors are set here, so a user's git config cannot change them.
const ADDED = "\u001B[32m+";
const MOVED = "\u001B[36m+";

/** Added lines and the added lines that git found moved, from `git diff --color=always`. */
export function countMoved(diff: string): { added: number; moved: number } {
	let added = 0;
	let moved = 0;
	for (const line of diff.split("\n")) {
		if (line.startsWith(MOVED)) {
			moved += 1;
			added += 1;
		} else if (line.startsWith(ADDED) && !line.startsWith(`${ADDED}++`)) {
			added += 1;
		}
	}
	return { added, moved };
}

const percent = (part: number, whole: number) =>
	whole === 0 ? "0.0%" : `${((100 * part) / whole).toFixed(1)}%`;

export function markdown(
	head: string,
	base: string,
	areas: AreaReport[],
	moved: { added: number; moved: number },
): string {
	const total = {
		area: "**Total**",
		lines: 0,
		clones: 0,
		cloneLines: 0,
		newClones: 0,
		newCloneLines: 0,
	};
	for (const area of areas) {
		total.lines += area.lines;
		total.clones += area.clones;
		total.cloneLines += area.cloneLines;
		total.newClones += area.newClones;
		total.newCloneLines += area.newCloneLines;
	}
	const rows = [...areas, total].map(
		(area) =>
			`| ${area.area} | ${String(area.lines)} | ${String(area.clones)} | ${String(area.cloneLines)} | ${percent(area.cloneLines, area.lines)} | ${String(area.newClones)} | ${String(area.newCloneLines)} |`,
	);
	return [
		"## Duplicated and moved code",
		"",
		`\`${head.slice(0, 10)}\`, compared with \`${base.slice(0, 10)}\`. Production code only. A clone counts only with 5 lines besides its imports.`,
		"",
		"| Area | Lines | Clones | Lines in clones | Share | New clones | Lines in new clones |",
		"|---|---|---|---|---|---|---|",
		...rows,
		"",
		`Of ${String(moved.added)} lines added to production code since the base, git found ${String(moved.moved)} (${percent(moved.moved, moved.added)}) moved from another place.`,
		"",
	].join("\n");
}

async function jscpd(): Promise<string> {
	const pin = await ciToolPin("jscpd");
	return installPinnedBinary({
		cache: toolCache("jscpd"),
		version: pin.version,
		asset: { name: pin.name, sha256: pin.sha256 },
		url: pin.url.slice(0, -pin.name.length),
		executable: process.platform === "win32" ? "jscpd.exe" : "jscpd",
		member: pin.member,
	});
}

async function main() {
	const { values } = parseArgs({
		options: {
			base: { type: "string" },
			since: { type: "string", default: "7 days ago" },
			out: { type: "string", default: "tmp/ci-metrics/duplication" },
		},
	});
	const headOutput = await output("git", ["rev-parse", "HEAD"]);
	const head = headOutput.trim();
	const baseOutput =
		values.base ?? (await output("git", ["rev-list", "-1", `--before=${values.since}`, "HEAD"]));
	const base = baseOutput.trim();
	if (base === "") {
		throw new Error(`No commit before ${values.since}. Fetch more history or pass --base.`);
	}
	const binary = await jscpd();
	const scratch = await mkdtemp(path.join(tmpdir(), "duplication-"));
	const areas: AreaReport[] = [];
	try {
		for (const { area, paths } of AREAS) {
			const directory = path.join(scratch, area);
			execFileSync(
				binary,
				[
					"--silent",
					"--reporters",
					"json",
					"--output",
					directory,
					"--format",
					FORMATS,
					"--min-tokens",
					"50",
					"--min-lines",
					String(MIN_LINES),
					"--ignore",
					IGNORED.join(","),
					"--baseline-from-ref",
					base,
					...paths,
				],
				{
					stdio: ["ignore", "ignore", "inherit"],
					maxBuffer: CAPTURE_LIMIT_BYTES,
					// jscpd needs no GitHub access, so it gets no token.
					env: { ...process.env, GITHUB_TOKEN: undefined, GH_TOKEN: undefined },
				},
			);
			areas.push(summarize(area, await readJsonFile(path.join(directory, "jscpd-report.json"))));
		}
	} finally {
		await rm(scratch, { recursive: true, force: true });
	}
	const diff = await output("git", [
		"-c",
		"color.diff.new=green",
		"-c",
		"color.diff.newMoved=cyan",
		"diff",
		"--color=always",
		"--color-moved=blocks",
		"--no-renames",
		`${base}..${head}`,
		"--",
		// The source files that jscpd reads, not the configuration and resources beside them.
		...AREAS.flatMap(({ paths }) =>
			paths.flatMap((root) => EXTENSIONS.map((extension) => `:(glob)${root}/**/*.${extension}`)),
		),
		...IGNORED.map((pattern) => `:(exclude,glob)${pattern}`),
	]);
	const moved = countMoved(diff);
	const summary = markdown(head, base, areas, moved);
	await mkdir(values.out, { recursive: true });
	await writeFile(
		path.join(values.out, "duplication.json"),
		`${JSON.stringify({ head, base, areas, moved }, null, 2)}\n`,
	);
	console.info(summary);
	const step = process.env.GITHUB_STEP_SUMMARY;
	if (step !== undefined && step !== "") {
		await writeFile(step, summary, { flag: "a" });
	}
}

if (import.meta.main) {
	await main();
}
