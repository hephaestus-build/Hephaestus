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

interface OpenClaim {
	found: ReadonlySet<string>;
	matches: Matcher | null;
	holds: boolean;
	confirmed: Set<string>;
}

function matcherOf({ pattern, literal, ignoreCase }: SearchClaim): Matcher | null {
	if (literal) {
		const needle = ignoreCase ? pattern.toLowerCase() : pattern;
		return (line) => (ignoreCase ? line.toLowerCase() : line).includes(needle);
	}
	// `rg` takes the flag inside the pattern too, and JavaScript takes it only outside.
	const inline = pattern.startsWith("(?i)");
	try {
		const expression = new RegExp(
			inline ? pattern.slice(4) : pattern,
			ignoreCase || inline ? "iu" : "u",
		);
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

/** Records what the loop found in one file. A match that the tools side left out ends the claim. */
function compare(claim: OpenClaim, file: string, lines: readonly string[]): void {
	const { matches } = claim;
	if (matches === null) {
		return;
	}
	for (const [index, line] of lines.entries()) {
		if (matches(line)) {
			const location = `${file}:${String(index + 1)}`;
			claim.confirmed.add(location);
			claim.holds &&= claim.found.has(location);
		}
	}
}

/** For each claim, whether the loop and the tools side found the same matches. Past `maxBytes`, none is proved. */
export function proved({ root, maxBytes, claims }: SearchJob): boolean[] {
	const open: OpenClaim[] = claims.map((claim) => ({
		found: new Set(claim.found),
		matches: matcherOf(claim),
		holds: true,
		confirmed: new Set(),
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
				compare(claim, file, lines);
			}
		}
	}
	// A match that only the tools side found means that the two engines read the pattern differently, so the loop's
	// silence about the rest proves nothing.
	return open.map(
		(claim) => claim.matches !== null && claim.holds && claim.found.size === claim.confirmed.size,
	);
}

if (parentPort !== null) {
	// The loop that started this thread wrote the job.
	// oxlint-disable-next-line typescript/no-unsafe-assignment
	const job: SearchJob = workerData;
	parentPort.postMessage(proved(job), []);
}
