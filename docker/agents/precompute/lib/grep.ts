import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { createInterface } from "node:readline";

import { globFilesSync } from "./files.ts";

import { isInDiff } from "./diff-parser.ts";
import type { DiffFile, Hint } from "./types.ts";

export interface GrepMatch {
	file: string;
	line: number;
	content: string;
}

export interface GrepOptions {
	glob?: string;
	maxResults?: number;
	fixedString?: boolean;
}

const GLOB_GREP_BATCH_SIZE = 256;

function parseGrepLine(line: string, dir: string): GrepMatch | null {
	// Path and line number are required groups; the trailing content group matches empty for a blank
	// matched line, so it defaults instead of rejecting the whole match.
	const {
		file,
		lineNumber,
		content = "",
	} = /^(?<file>.+?):(?<lineNumber>\d+):(?<content>.*)$/u.exec(line)?.groups ?? {};
	if (file === undefined || lineNumber === undefined) {
		return null;
	}

	return {
		file: path.relative(dir, file),
		line: Number.parseInt(lineNumber, 10),
		content: content.trim(),
	};
}

async function collectGrepMatches(
	args: string[],
	dir: string,
	maxResults: number,
): Promise<GrepMatch[]> {
	const [command, ...commandArgs] = args;
	if (command === undefined || command === "") {
		throw new Error("grep command is empty");
	}
	const child = spawn(command, commandArgs, {
		stdio: ["ignore", "pipe", "ignore"],
	});
	let stoppedEarly = false;
	const completed = Promise.withResolvers<undefined>();
	child.once("error", completed.reject);
	child.once("close", (code) => {
		if (stoppedEarly || code === 0 || code === 1) {
			completed.resolve(undefined);
		} else {
			completed.reject(new Error(`grep exited with status ${String(code)}`));
		}
	});
	const matches: GrepMatch[] = [];
	const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });

	try {
		for await (const line of lines) {
			const match = parseGrepLine(line, dir);
			if (!match) {
				continue;
			}
			matches.push(match);
			if (matches.length >= maxResults) {
				stoppedEarly = true;
				child.kill();
				break;
			}
		}
	} finally {
		lines.close();
		await completed.promise;
	}

	return matches.slice(0, maxResults);
}

async function collectMatchesForGlob(
	pattern: string,
	dir: string,
	grepArgs: string[],
	glob: string,
	maxResults: number,
): Promise<GrepMatch[]> {
	const matches: GrepMatch[] = [];
	let batch: string[] = [];

	for (const file of globFilesSync(glob, dir)) {
		batch.push(path.join(dir, file));
		if (batch.length < GLOB_GREP_BATCH_SIZE) {
			continue;
		}

		const remaining = maxResults - matches.length;
		matches.push(
			...(await collectGrepMatches([...grepArgs, "--", pattern, ...batch], dir, remaining)),
		);
		if (matches.length >= maxResults) {
			return matches.slice(0, maxResults);
		}
		batch = [];
	}

	if (batch.length === 0) {
		return matches;
	}

	const remaining = maxResults - matches.length;
	if (remaining <= 0) {
		return matches.slice(0, maxResults);
	}

	matches.push(
		...(await collectGrepMatches([...grepArgs, "--", pattern, ...batch], dir, remaining)),
	);
	return matches.slice(0, maxResults);
}

function shouldIncludeDiscoveredFile(file: string): boolean {
	const segments = file.split("/");
	return !segments.some(
		(segment) => segment === "node_modules" || segment === ".build" || segment.startsWith("."),
	);
}

/**
 * Run grep on a directory. Returns structured matches.
 *
 * @param pattern — regex pattern (or fixed string if fixedString=true)
 * @param dir — directory to search
 * @param opts.glob — file glob filter; basename-only globs (e.g. "*.swift") are auto-expanded to recursive ("**\/*.swift")
 * @param opts.maxResults — cap results (default 500)
 * @param opts.fixedString — use -F instead of -E (default false)
 */
export async function grep(
	pattern: string,
	dir: string,
	opts: GrepOptions = {},
): Promise<GrepMatch[]> {
	const { glob, maxResults = 500, fixedString = false } = opts;
	if (maxResults <= 0) {
		return [];
	}

	const grepArgs = fixedString ? ["grep", "-H", "-n", "-F"] : ["grep", "-H", "-n", "-E"];

	if (glob !== undefined && glob !== "") {
		// A basename-only glob matches only the root dir; prepend "**/" for recursion.
		const recursiveGlob = glob.includes("/") ? glob : `**/${glob}`;
		return collectMatchesForGlob(pattern, dir, grepArgs, recursiveGlob, maxResults);
	}

	return collectGrepMatches(
		["grep", "-r", ...grepArgs.slice(1), "--", pattern, dir],
		dir,
		maxResults,
	);
}

export function matchesToHints(
	matches: GrepMatch[],
	pattern: string,
	diffFiles: Map<string, DiffFile>,
	flagFn?: (match: GrepMatch) => Record<string, boolean | number | string>,
): Hint[] {
	return matches.map((m) => ({
		file: m.file,
		line: m.line,
		pattern,
		context: m.content,
		inDiff: isInDiff(diffFiles, m.file, m.line),
		flags: flagFn ? flagFn(m) : {},
	}));
}

export async function readFileLines(file: string): Promise<Map<number, string>> {
	try {
		const content = await readFile(file, "utf8");
		const lines = new Map<number, string>();
		for (const [i, line] of content.split("\n").entries()) {
			lines.set(i + 1, line);
		}
		return lines;
	} catch (error) {
		console.error(`[precompute] readFileLines failed for ${file}: ${String(error)}`);
		return new Map();
	}
}

export function findFiles(dir: string, extension: string): string[] {
	const pattern = `**/*.${extension}`;
	return globFilesSync(pattern, dir)
		.filter((file) => shouldIncludeDiscoveredFile(file))
		.map((file) => path.join(dir, file));
}

export function findSwiftFiles(dir: string): string[] {
	return findFiles(dir, "swift");
}
