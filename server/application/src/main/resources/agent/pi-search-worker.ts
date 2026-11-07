// pi-search-worker.ts — the loop's own search of a checkout, on a thread that the loop can end.
//
// A pattern comes from a model, and a regular expression can run for ever, so this runs apart from the loop.

import { createHash } from "node:crypto";
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

/**
 * For each claim, whether it stands, and the checkout digest of the bytes that the proof searched. A command in the
 * sandbox can change the checkout, so admission accepts a search only when this digest equals the one that the server
 * records from its own copy when it captures the checkout (`JobEvidenceFiles.checkoutDigests`). Null when the proof read nothing to
 * the end.
 */
export interface SearchProof {
	proved: boolean[];
	checkoutDigest: string | null;
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
	// A flag inside the pattern can turn off what `matcherOf` set, and JavaScript reads it otherwise.
	if (/\(\?[a-zA-Z-]+[:)]/u.test(pattern)) {
		return null;
	}
	// A `]` that opens a set is a member to `rg`, and to JavaScript it ends an empty set.
	if (/(?:^|[^\\])(?:\\\\)*\[\^?\]/u.test(pattern)) {
		return null;
	}
	let out = "";
	let inSet = false;
	for (let at = 0; at < pattern.length; at += 1) {
		const character = pattern.charAt(at);
		if (character === "\\") {
			const next = pattern.charAt(at + 1);
			at += 1;
			if (inSet && "WDSP".includes(next)) {
				return null;
			}
			out += (inSet ? INSIDE_A_SET : OUTSIDE_A_SET)[next] ?? `\\${next}`;
		} else {
			// `rg` reads these as set operators, and JavaScript as members of the set.
			if (inSet && ["&&", "--", "~~"].includes(pattern.slice(at, at + 2))) {
				return null;
			}
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
	if (file[0] === 0xff && file[1] === 0xfe) {
		return new TextDecoder("utf-16le").decode(file.subarray(2));
	}
	if (file[0] === 0xfe && file[1] === 0xff) {
		return new TextDecoder("utf-16be").decode(file.subarray(2));
	}
	// Only the first mark counts: after a UTF-8 mark, `rg` reads UTF-8 whatever follows.
	const bytes = file.subarray(0, 3).equals(UTF8_MARK) ? file.subarray(3) : file;
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

/**
 * The checkout digest: the SHA-256 of one line `<path>\0<SHA-256 of the file>\n` for each regular file under the root
 * outside `.git`, in the byte order of the paths. The server computes the same digest
 * (`JobEvidenceFiles.checkoutDigests`), and `pi-verified-search.spec.ts` pins both to one value.
 */
export function checkoutDigest(files: ReadonlyMap<string, string>): string {
	const digest = createHash("sha256");
	const paths = [...files.keys()].toSorted((a, b) =>
		Buffer.compare(Buffer.from(a), Buffer.from(b)),
	);
	for (const file of paths) {
		digest.update(`${file}\0${files.get(file) ?? ""}\n`);
	}
	return digest.digest("hex");
}

/** `rg --hidden` reads `.git`, and a receipt speaks only for the checkout's files. */
export const checkoutMatches = (found: readonly string[]) =>
	found.filter((location) => !/(?:^|\/)\.git\//u.test(location));

/** For each claim, whether the loop and the tools side found the same matches. Past `maxBytes`, none is proved. */
export function proved({ root, maxBytes, claims }: SearchJob): SearchProof {
	const open: OpenClaim[] = claims.map((claim) => ({
		found: new Set(checkoutMatches(claim.found)),
		matches: matcherOf(claim),
		holds: true,
		confirmed: new Set(),
	}));
	let seen = 0;
	const digests = new Map<string, string>();
	for (const file of filesOf(root)) {
		const absolute = path.join(root, file);
		seen += statSync(absolute).size;
		if (seen > maxBytes) {
			return { proved: claims.map(() => false), checkoutDigest: null };
		}
		const bytes = readFileSync(absolute);
		digests.set(file, createHash("sha256").update(bytes).digest("hex"));
		const text = textOf(bytes);
		if (text !== null) {
			const lines = text.split("\n");
			for (const claim of open) {
				compare(claim, file, lines);
			}
		}
	}
	// A match that only the tools side found means that the two engines read the pattern differently, so the loop's
	// silence about the rest proves nothing.
	return {
		proved: open.map(
			(claim) => claim.matches !== null && claim.holds && claim.found.size === claim.confirmed.size,
		),
		checkoutDigest: checkoutDigest(digests),
	};
}

if (parentPort !== null) {
	// The loop that started this thread wrote the job.
	// oxlint-disable-next-line typescript/no-unsafe-assignment
	const job: SearchJob = workerData;
	parentPort.postMessage(proved(job), []);
}
