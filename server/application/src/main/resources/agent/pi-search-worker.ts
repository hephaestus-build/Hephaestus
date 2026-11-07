// pi-search-worker.ts — the loop's own search of a checkout, on a thread that the loop can end.
//
// A pattern comes from a model, and a regular expression can run for ever, so this runs apart from the loop.

import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { parentPort, workerData } from "node:worker_threads";

export interface SearchClaim {
	pattern: string;
	literal: boolean;
	ignoreCase: boolean;
	/** What the tools side reported, as `path:line` from the root. */
	found: readonly string[];
}

export interface SearchJob {
	root: string;
	maxBytes: number;
	claims: readonly SearchClaim[];
}

type Matcher = (line: string) => boolean;

function matcherOf({ pattern, literal, ignoreCase }: SearchClaim): Matcher | null {
	if (literal) {
		const needle = ignoreCase ? pattern.toLowerCase() : pattern;
		return (line) => (ignoreCase ? line.toLowerCase() : line).includes(needle);
	}
	try {
		const expression = new RegExp(pattern, ignoreCase ? "iu" : "u");
		return (line) => expression.test(line);
	} catch {
		// `rg` and JavaScript differ here, so this pattern proves nothing.
		return null;
	}
}

function* filesOf(root: string, directory = ""): Generator<string> {
	for (const entry of readdirSync(path.join(root, directory), { withFileTypes: true })) {
		const relative = path.join(directory, entry.name);
		if (entry.isDirectory()) {
			if (entry.name !== ".git") {
				yield* filesOf(root, relative);
			}
		} else if (entry.isFile()) {
			yield relative;
		}
	}
}

function leavesNothingOut(
	{ found, matches }: { found: ReadonlySet<string>; matches: Matcher | null },
	file: string,
	lines: readonly string[],
): boolean {
	return (
		matches === null ||
		lines.every((line, index) => !matches(line) || found.has(`${file}:${String(index + 1)}`))
	);
}

/** For each claim, whether the loop found no match that the tools side left out. Past `maxBytes`, none is proved. */
export function proved({ root, maxBytes, claims }: SearchJob): boolean[] {
	const open = claims.map((claim) => ({
		found: new Set(claim.found),
		matches: matcherOf(claim),
		holds: true,
	}));
	let seen = 0;
	for (const file of filesOf(root)) {
		const absolute = path.join(root, file);
		seen += statSync(absolute).size;
		if (seen > maxBytes) {
			return claims.map(() => false);
		}
		const bytes = readFileSync(absolute);
		if (!bytes.includes(0)) {
			const lines = bytes.toString("utf8").split("\n");
			for (const claim of open) {
				claim.holds &&= leavesNothingOut(claim, file, lines);
			}
		}
	}
	return open.map((claim) => claim.matches !== null && claim.holds);
}

if (parentPort !== null) {
	// The loop that started this thread wrote the job.
	// oxlint-disable-next-line typescript/no-unsafe-assignment
	const job: SearchJob = workerData;
	parentPort.postMessage(proved(job), []);
}
