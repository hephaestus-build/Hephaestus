import { spawn } from "node:child_process";
import path from "node:path";
import { createInterface } from "node:readline";

import { isSafeRelativePath } from "../../lib/core/contract.ts";
import { globFilesSync } from "./files.ts";

export interface GrepMatch {
	file: string;
	line: number;
	content: string;
}

export interface GrepOptions {
	glob?: string;
	maxResults?: number;
	fixedString?: boolean;
	paths?: readonly string[];
	/** How the search runs; a child of this process unless the sandbox gives another way. */
	run?: LineRunner;
}

const GLOB_GREP_BATCH_SIZE = 256;

function parseGrepLine(line: string, dir: string): GrepMatch | null {
	const separator = line.indexOf("\0");
	const { lineNumber, content } =
		/^(?<lineNumber>\d+):(?<content>.*)$/su.exec(line.slice(separator + 1))?.groups ?? {};
	if (separator === -1 || lineNumber === undefined || content === undefined) {
		return null;
	}

	return {
		file: path.relative(dir, line.slice(0, separator)),
		line: Number.parseInt(lineNumber, 10),
		content: content.trim(),
	};
}

/**
 * Runs a search and hands over each line of its output until `onLine` says stop. In the sandbox the search runs on the
 * tools side, so this may be another process's answer: a line is parsed and bounded like any other.
 */
export type LineRunner = (
	argv: readonly string[],
	onLine: (line: string) => boolean,
	signal?: AbortSignal,
) => Promise<void>;

/** The search as a child of this process. */
export const spawnedLines: LineRunner = async (argv, onLine, signal) => {
	const [command, ...commandArgs] = argv;
	if (command === undefined || command === "") {
		throw new Error("grep command is empty");
	}
	const child = spawn(command, commandArgs, {
		stdio: ["ignore", "pipe", "ignore"],
		signal,
	});
	let stoppedEarly = false;
	let failure: Error | undefined;
	const completed = Promise.withResolvers<undefined>();
	child.once("error", (error) => {
		failure = error;
	});
	child.once("close", (code) => {
		if (failure !== undefined) {
			completed.reject(failure);
		} else if (stoppedEarly || code === 0 || code === 1) {
			completed.resolve(undefined);
		} else {
			completed.reject(new Error(`grep exited with status ${String(code)}`));
		}
	});
	const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });

	const collected = (async () => {
		try {
			for await (const line of lines) {
				if (!onLine(line)) {
					stoppedEarly = true;
					child.kill();
					break;
				}
			}
		} finally {
			lines.close();
		}
	})();
	await Promise.all([collected, completed.promise]);
};

async function collectGrepMatches(
	args: string[],
	dir: string,
	maxResults: number,
	run: LineRunner,
	signal?: AbortSignal,
): Promise<GrepMatch[]> {
	signal?.throwIfAborted();
	const matches: GrepMatch[] = [];
	await run(
		args,
		(line) => {
			const match = parseGrepLine(line, dir);
			if (match) {
				matches.push(match);
			}
			return matches.length < maxResults;
		},
		signal,
	);
	return matches.slice(0, maxResults);
}

async function collectMatchesForGlob(
	pattern: string,
	dir: string,
	grepArgs: string[],
	glob: string,
	maxResults: number,
	run: LineRunner,
	signal?: AbortSignal,
	permittedPaths?: readonly string[],
): Promise<GrepMatch[]> {
	const matches: GrepMatch[] = [];
	let batch: string[] = [];

	const matched = new Set(globFilesSync(glob, dir));
	for (const file of permittedPaths === undefined
		? [...matched]
		: permittedPaths.filter((candidate) => matched.has(candidate))) {
		signal?.throwIfAborted();
		if (!isSafeRelativePath(file)) {
			continue;
		}
		batch.push(path.join(dir, file));
		if (batch.length < GLOB_GREP_BATCH_SIZE) {
			continue;
		}

		const remaining = maxResults - matches.length;
		matches.push(
			...(await collectGrepMatches(
				[...grepArgs, "--", pattern, ...batch],
				dir,
				remaining,
				run,
				signal,
			)),
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
		...(await collectGrepMatches(
			[...grepArgs, "--", pattern, ...batch],
			dir,
			remaining,
			run,
			signal,
		)),
	);
	return matches.slice(0, maxResults);
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
	signal?: AbortSignal,
): Promise<GrepMatch[]> {
	signal?.throwIfAborted();
	const { glob, maxResults = 500, fixedString = false, run = spawnedLines } = opts;
	if (maxResults <= 0) {
		return [];
	}

	// --null ends the file name with a NUL on GNU and BSD grep; -Z means decompression on BSD.
	// A name holding `:<n>:` still splits; -I skips binary files,
	// whose only output would be a "Binary file … matches" line with no line to cite.
	const grepArgs = ["grep", "-H", "-n", "--null", "-I", fixedString ? "-F" : "-E"];

	if ((glob !== undefined && glob !== "") || opts.paths !== undefined) {
		// A basename-only glob matches only the root dir; prepend "**/" for recursion.
		let recursiveGlob = "**/*";
		if (glob !== undefined && glob !== "") {
			recursiveGlob = glob.includes("/") ? glob : `**/${glob}`;
		}
		return collectMatchesForGlob(
			pattern,
			dir,
			grepArgs,
			recursiveGlob,
			maxResults,
			run,
			signal,
			opts.paths,
		);
	}

	return collectGrepMatches(
		["grep", "-r", "--exclude-dir=.git", ...grepArgs.slice(1), "--", pattern, dir],
		dir,
		maxResults,
		run,
		signal,
	);
}
