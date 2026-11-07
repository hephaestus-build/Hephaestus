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

const WORD = String.raw`\p{Alphabetic}\p{M}\p{Nd}\p{Pc}\p{Join_Control}`;
const BOUNDARY = `(?:(?<=[${WORD}])(?![${WORD}])|(?<![${WORD}])(?=[${WORD}]))`;
const NOT_BOUNDARY = `(?:(?<=[${WORD}])(?=[${WORD}])|(?<![${WORD}])(?![${WORD}]))`;

/** `rg`'s classes are Unicode, and JavaScript's `\w`, `\d`, `\b` and `\s` are not: a word in another script would go unseen. */
const OUTSIDE_A_SET: Readonly<Record<string, string>> = {
	w: `[${WORD}]`,
	W: `[^${WORD}]`,
	d: String.raw`\p{Nd}`,
	D: String.raw`\P{Nd}`,
	s: String.raw`\p{White_Space}`,
	S: String.raw`\P{White_Space}`,
	b: BOUNDARY,
	B: NOT_BOUNDARY,
};
const INSIDE_A_SET: Readonly<Record<string, string>> = {
	w: WORD,
	d: String.raw`\p{Nd}`,
	s: String.raw`\p{White_Space}`,
};

/** The pattern as JavaScript must read it to agree with `rg`, or null where the two cannot agree. */
function translated(pattern: string): string | null {
	// `rg` searches line by line, and a POSIX class means something else to JavaScript.
	if (pattern.includes("[[:") || /\\[nr]/u.test(pattern.replaceAll(String.raw`\\`, ""))) {
		return null;
	}
	let out = "";
	let inSet = false;
	for (let at = 0; at < pattern.length; at += 1) {
		const character = pattern.charAt(at);
		if (character === "\\") {
			const next = pattern.charAt(at + 1);
			at += 1;
			if (inSet && (next === "W" || next === "D" || next === "S")) {
				return null;
			}
			out += (inSet ? INSIDE_A_SET : OUTSIDE_A_SET)[next] ?? `\\${next}`;
		} else {
			inSet = (inSet || character === "[") && character !== "]";
			out += character;
		}
	}
	return out;
}

function matcherOf({ pattern, literal, ignoreCase }: SearchClaim): Matcher | null {
	if (pattern.includes("\n")) {
		return null;
	}
	if (literal && !ignoreCase) {
		return (line) => line.includes(pattern);
	}
	if (literal) {
		// `rg` folds case as Unicode does, and `toLowerCase` does not: `ſ` is an `s` to it.
		const expression = new RegExp(
			pattern.replaceAll(/[\\^$.*+?()[\]{}|]/gu, String.raw`\$&`),
			"iu",
		);
		return (line) => expression.test(line);
	}
	// `rg` takes the flag inside the pattern too, and JavaScript takes it only outside.
	const inline = pattern.startsWith("(?i)");
	const source = translated(inline ? pattern.slice(4) : pattern);
	if (source === null) {
		return null;
	}
	try {
		// A line has no `\n`, and `rg`'s `.` stops at nothing else: JavaScript's would stop at `\r` too.
		const expression = new RegExp(source, ignoreCase || inline ? "ius" : "us");
		return (line) => expression.test(line);
	} catch {
		// `rg` and JavaScript differ here, so this pattern proves nothing.
		return null;
	}
}

/** Where `rg` looks for a NUL before it reads on: one in this window makes the file binary, and it skips the file. */
const BINARY_WINDOW = 64 * 1024;
const UTF8_MARK = Buffer.from([0xef, 0xbb, 0xbf]);

/**
 * A file's text as `rg` reads it, so that both search the same bytes. It follows a byte order mark, skips a file with a
 * NUL near its start, and reads a later NUL as the end of the file. Text that `rg` does not search is no match that the
 * tools side could hide.
 */
function textOf(file: Buffer): string | null {
	const bytes = file.subarray(0, 3).equals(UTF8_MARK) ? file.subarray(3) : file;
	if (bytes[0] === 0xff && bytes[1] === 0xfe) {
		return bytes.toString("utf16le", 2);
	}
	if (bytes[0] === 0xfe && bytes[1] === 0xff) {
		return Buffer.from(bytes.subarray(2, bytes.length - (bytes.length % 2)))
			.swap16()
			.toString("utf16le");
	}
	const nul = bytes.indexOf(0);
	if (nul === -1) {
		return bytes.toString("utf8");
	}
	return nul < BINARY_WINDOW ? null : bytes.toString("utf8", 0, nul);
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
		// `rg --hidden` reads `.git`, and a receipt speaks only for the checkout's files.
		found: new Set(claim.found.filter((location) => !/(?:^|\/)\.git\//u.test(location))),
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
		const text = textOf(readFileSync(absolute));
		if (text !== null) {
			const lines = text.split("\n");
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
