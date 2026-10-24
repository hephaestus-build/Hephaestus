import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import path from "node:path";

import { output } from "./lib/process.ts";

const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const MAX_DAYS = 14;
const DAY_MS = 24 * 60 * 60 * 1000;

const JAVA_TEST = /^server\/application\/src\/test\/java\/.*\.java$/u;
const TS_TEST = /\.(?:test|spec)\.tsx?$/u;
// TypeScript 7 ships no JavaScript compiler API, so the scan reads the whole text: the formatter
// wraps a long call after its opening parenthesis, and a per-line pattern misses it.
const TS_SKIP =
	/\b(?<base>describe|it|test|bench|suite)(?<chain>(?:\.(?!(?:skip|fixme|todo)\b)\w+)*)\.(?:skip|fixme|todo)\b(?!If)/gu;
const TS_PREFIXED = /\bx(?:describe|it|test)\s*\(/gu;
const JAVA_SKIP = /@(?:org\.junit\.jupiter\.api\.)?Disabled\b(?!If|On|For)/gu;
const MARKER =
	/quarantine #(?<issue>\d+) @(?<owner>[A-Za-z\d](?:[A-Za-z\d-]*[A-Za-z\d])?) until (?<until>\d{4}-\d{2}-\d{2})/u;

export interface Finding {
	readonly file: string;
	readonly line: number;
	readonly problem: string;
}

interface Skip {
	/** Offset of the skip in the file. */
	readonly at: number;
	/** The text that may carry the marker besides the line above: a Java annotation's arguments. */
	readonly arguments: string;
}

/**
 * Whether Playwright's `test.skip(…)` or `test.fixme(…)` ending at `end` skips whatever the
 * environment. Without arguments, with a title, with `true`, or with a body as its second argument,
 * it skips always. A condition, such as `test.skip(!process.env.KEY, "…")`, gates on the
 * environment. Vitest's `.skip`, `.todo` and every chained form take no condition.
 */
export function isUnconditional(text: string, end: number): boolean {
	const rest = text.slice(end).replace(/^\s*/u, "");
	if (!rest.startsWith("(")) {
		return true;
	}
	const args = rest.slice(1).replace(/^(?:\s|\/\/[^\n]*\n|\/\*[\s\S]*?\*\/)*/u, "");
	if (args.startsWith(")") || /^(?:["'`]|true\b)/u.test(args)) {
		return true;
	}
	const comma = /^[^,()]*,\s*/u.exec(args);
	return comma !== null && /^(?:\(|async\b|function\b)/u.test(args.slice(comma[0].length));
}

function tsSkips(text: string): Skip[] {
	const skips: Skip[] = [];
	for (const match of text.matchAll(TS_SKIP)) {
		const playwright = match.groups?.base === "test" && match.groups.chain === "";
		if (!playwright || isUnconditional(text, match.index + match[0].length)) {
			skips.push({ at: match.index, arguments: "" });
		}
	}
	for (const match of text.matchAll(TS_PREFIXED)) {
		skips.push({ at: match.index, arguments: "" });
	}
	return skips;
}

/** The text of a Java annotation's arguments, up to the parenthesis that closes them. */
function annotationArguments(after: string): string {
	const open = /^\s*\(/u.exec(after);
	if (open === null) {
		return "";
	}
	let quoted = false;
	for (let index = open[0].length; index < after.length; index += 1) {
		const character = after[index];
		if (character === "\\" && quoted) {
			index += 1;
		} else if (character === '"') {
			quoted = !quoted;
		} else if (character === ")" && !quoted) {
			return after.slice(open[0].length, index);
		}
	}
	return "";
}

function javaSkips(text: string): Skip[] {
	return [...text.matchAll(JAVA_SKIP)].map((match) => ({
		at: match.index,
		arguments: annotationArguments(text.slice(match.index + match[0].length)),
	}));
}

/**
 * Unconditional skips that name no issue and owner, or whose end date is no calendar day, has
 * passed or lies more than 14 days ahead.
 */
export function quarantineFindings(file: string, content: string, today: Date): Finding[] {
	const skips = file.endsWith(".java") ? javaSkips(content) : tsSkips(content);
	const lines = content.split("\n");
	const start = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate());
	return skips
		.toSorted((a, b) => a.at - b.at)
		.flatMap(({ at, arguments: args }): Finding[] => {
			const line = content.slice(0, at).split("\n").length;
			const marker =
				MARKER.exec(args) ??
				MARKER.exec(lines[line - 1] ?? "") ??
				MARKER.exec(lines[line - 2] ?? "");
			const issue = marker?.groups?.issue;
			const end = marker?.groups?.until;
			if (issue === undefined || end === undefined) {
				return [
					{
						file,
						line,
						problem: "skips a test without `quarantine #<issue> @<owner> until <YYYY-MM-DD>`",
					},
				];
			}
			const until = Date.parse(`${end}T00:00:00Z`);
			// `Date.parse` rolls an impossible day over into the next month: 2027-02-30 is 2027-03-02.
			if (!Number.isFinite(until) || new Date(until).toISOString().slice(0, 10) !== end) {
				return [{ file, line, problem: `quarantine of #${issue} names no calendar date (${end})` }];
			}
			if (until < start) {
				return [{ file, line, problem: `quarantine of #${issue} expired on ${end}` }];
			}
			if (until - start > MAX_DAYS * DAY_MS) {
				return [
					{ file, line, problem: `quarantine of #${issue} runs past ${MAX_DAYS} days (${end})` },
				];
			}
			return [];
		});
}

// This gate's own fixtures spell out the skips that it rejects.
const OWN_FIXTURES = "scripts/check-quarantine.test.ts";

export function isTestSource(file: string): boolean {
	return (
		file !== OWN_FIXTURES &&
		(JAVA_TEST.test(file) || (TS_TEST.test(file) && !file.includes("node_modules/")))
	);
}

if (import.meta.main) {
	const stdout = await output(
		"git",
		["ls-files", "-z", "--cached", "--others", "--exclude-standard"],
		{
			cwd: REPO_ROOT,
			env: { GIT_DIR: undefined, GIT_INDEX_FILE: undefined, GIT_WORK_TREE: undefined },
		},
	);
	// A file deleted in the work tree is still listed from the index until it is staged.
	const files = stdout
		.split("\0")
		.filter((file) => isTestSource(file) && existsSync(path.join(REPO_ROOT, file)));
	if (files.length === 0) {
		throw new Error("No test sources found; refusing to pass without checking anything.");
	}
	const today = new Date();
	const perFile = await Promise.all(
		files.map(async (file) =>
			quarantineFindings(file, await readFile(path.join(REPO_ROOT, file), "utf8"), today),
		),
	);
	const findings = perFile.flat();
	for (const finding of findings) {
		process.stderr.write(`${finding.file}:${finding.line} ${finding.problem}\n`);
	}
	if (findings.length > 0) {
		process.stderr.write("See docs/contributor/testing.mdx § Flaky tests.\n");
		process.exitCode = 1;
	} else {
		process.stdout.write(
			`check-quarantine: ${files.length} test files, no unowned or expired skip.\n`,
		);
	}
}
