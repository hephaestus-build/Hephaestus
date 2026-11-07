// pi-tool-side.ts — what the review loop's tools do on the tools side.
//
// The loop starts no process. `bash` and `grep` run on the tools side over tool protocol v1, which the loop does not
// trust: their output is text for the model, and it counts for nothing until the loop has verified it. Pi's tools keep
// their contracts, so a model sees the tools that it knows.

import { existsSync } from "node:fs";
import { glob as nodeGlob } from "node:fs/promises";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

import {
	type BashOperations,
	createFindToolDefinition,
	createGrepToolDefinition,
	DEFAULT_MAX_BYTES,
	formatSize,
	truncateHead,
	truncateLine,
} from "@earendil-works/pi-coding-agent";

import type { LineRunner } from "./work/practice-scripts/kits/scm/grep.ts";

import { ToolClient } from "./tool-client.ts";
import { LIMITS, type ToolAddress } from "./tool-protocol.ts";

/** How long the loop waits for a side to come up: the kubelet starts it just before the loop. */
const OPEN_WAIT_MS = 60_000;
const OPEN_RETRY_MS = 200;

const DEFAULT_GREP_LIMIT = 100;

/** How long one repository search of a program may take. */
const SEARCH_TIMEOUT_MS = 60_000;

/** Pi truncates a `grep` line here and does not export the number; the parity test holds the two together. */
const GREP_MAX_LINE_LENGTH = 500;

/** Dials a side of the Pod, which may still be starting. */
export async function openToolSide(address: ToolAddress, nonce: string): Promise<ToolClient> {
	const until = Date.now() + OPEN_WAIT_MS;
	for (;;) {
		try {
			return await ToolClient.open(address, nonce);
		} catch (error) {
			if (Date.now() >= until) {
				throw error;
			}
			await sleep(OPEN_RETRY_MS);
		}
	}
}

/** Pi's `bash` over `exec`: the tools side gives a command its own environment, so `env` goes unused. */
export function toolBashOperations(tools: Pick<ToolClient, "exec">): BashOperations {
	return {
		exec: async (command, cwd, { onData, signal, timeout }) => {
			const exit = await tools.exec(
				{
					command,
					cwd,
					timeoutMs: timeout === undefined ? LIMITS.execTimeoutMs : timeout * 1000,
				},
				onData,
				signal,
			);
			return { exitCode: exit.code };
		},
	};
}

type GrepInput = Parameters<ReturnType<typeof createGrepToolDefinition>["execute"]>[1];

/** A repository search on the tools side, for the program host: it stops as soon as `onLine` has seen enough. */
export function toolLines(tools: Pick<ToolClient, "exec">, cwd: string): LineRunner {
	return async (argv, onLine, signal) => {
		const stop = new AbortController();
		let pending = "";
		const take = (line: string) => {
			if (!stop.signal.aborted && !onLine(line)) {
				stop.abort();
			}
		};
		const exit = await tools.exec(
			{ argv: [...argv], cwd, timeoutMs: SEARCH_TIMEOUT_MS },
			(chunk) => {
				pending += chunk.toString("utf8");
				const lines = pending.split("\n");
				pending = lines.pop() ?? "";
				for (const line of lines) {
					take(line);
				}
			},
			signal === undefined ? stop.signal : AbortSignal.any([signal, stop.signal]),
		);
		if (pending !== "") {
			take(pending);
		}
		signal?.throwIfAborted();
		if (!stop.signal.aborted && exit.code !== 0 && exit.code !== 1) {
			throw new Error(`grep exited with status ${String(exit.code)}`);
		}
	};
}

/** Pi's `find` with a Node glob: Pi's own runs `fd`, and the loop starts no process. */
export function loopFindTool(root: string) {
	return createFindToolDefinition(root, {
		operations: {
			exists: (file) => existsSync(file),
			glob: async (pattern, cwd, { ignore, limit }) => {
				const found: string[] = [];
				for await (const file of nodeGlob(pattern, { cwd, exclude: ignore })) {
					found.push(file);
					if (found.length >= limit) {
						break;
					}
				}
				return found;
			},
		},
	});
}

interface Match {
	file: string;
	line: number;
	text: string | undefined;
}

/** A `match` line of `rg --json`; any other line is not a match, and a broken one goes unread. */
function matchOf(line: string): Match | undefined {
	let event: unknown;
	try {
		event = JSON.parse(line);
	} catch {
		return undefined;
	}
	if (typeof event !== "object" || event === null || !("type" in event) || event.type !== "match") {
		return undefined;
	}
	const data = "data" in event ? event.data : undefined;
	if (typeof data !== "object" || data === null) {
		return undefined;
	}
	const file = "path" in data ? textOf(data.path) : undefined;
	const number = "line_number" in data ? data.line_number : undefined;
	if (file === undefined || typeof number !== "number") {
		return undefined;
	}
	return { file, line: number, text: "lines" in data ? textOf(data.lines) : undefined };
}

function textOf(value: unknown): string | undefined {
	return typeof value === "object" &&
		value !== null &&
		"text" in value &&
		typeof value.text === "string"
		? value.text
		: undefined;
}

/** What Pi's `grep` shows for a path, from an answer that the loop does not trust: a path outside goes unnamed. */
function shownPath(file: string, root: string): string {
	const relative = path.relative(root, path.resolve(root, file));
	return relative === "" || relative.startsWith("..") ? path.basename(file) : relative;
}

/**
 * Pi's `grep`, with `rg` on the tools side: its parameters, description and output. Pi always starts `rg` itself, with no
 * operation to replace, so this repeats its format, and a test holds the two together. `context` lines come from
 * `readFile`, which reads inside `root` only, because a path in the answer is the tools side's to choose.
 */
export function toolGrepTool(
	tools: Pick<ToolClient, "exec">,
	root: string,
	readFile: (absolutePath: string) => Promise<string>,
) {
	const pi = createGrepToolDefinition(root);
	return {
		...pi,
		execute: async (_id: string, input: GrepInput, signal?: AbortSignal) => {
			const { pattern, path: searchDir, glob, ignoreCase, literal, context, limit } = input;
			const searchPath = path.resolve(root, searchDir ?? ".");
			const effectiveLimit = Math.max(1, limit ?? DEFAULT_GREP_LIMIT);
			const contextLines = context !== undefined && context > 0 ? context : 0;
			const argv = ["rg", "--no-config", "--json", "--line-number", "--color=never", "--hidden"];
			if (ignoreCase === true) {
				argv.push("--ignore-case");
			}
			if (literal === true) {
				argv.push("--fixed-strings");
			}
			if (glob !== undefined) {
				argv.push("--glob", glob);
			}
			argv.push("--", pattern, searchPath);

			const stop = new AbortController();
			const matches: Match[] = [];
			let pending = "";
			const cutOff = { atLimit: false };
			const onLine = (line: string) => {
				const match = matches.length < effectiveLimit ? matchOf(line) : undefined;
				if (match !== undefined) {
					matches.push(match);
					if (matches.length >= effectiveLimit) {
						cutOff.atLimit = true;
						stop.abort();
					}
				}
			};
			const exit = await tools.exec(
				{ argv, cwd: root, timeoutMs: LIMITS.execTimeoutMs },
				(chunk) => {
					pending += chunk.toString("utf8");
					const lines = pending.split("\n");
					pending = lines.pop() ?? "";
					for (const line of lines) {
						onLine(line);
					}
				},
				signal === undefined ? stop.signal : AbortSignal.any([signal, stop.signal]),
			);
			onLine(pending);
			if (signal?.aborted === true) {
				throw new Error("Operation aborted");
			}
			if (!cutOff.atLimit && exit.code !== 0 && exit.code !== 1) {
				throw new Error(`ripgrep exited with code ${String(exit.code)}`);
			}
			if (matches.length === 0) {
				return {
					content: [{ type: "text" as const, text: "No matches found" }],
					details: undefined,
				};
			}
			return render(matches, {
				root: searchPath,
				contextLines,
				effectiveLimit,
				limitReached: cutOff.atLimit,
				readFile,
				inside: root,
			});
		},
	};
}

async function render(
	matches: readonly Match[],
	options: {
		root: string;
		inside: string;
		contextLines: number;
		effectiveLimit: number;
		limitReached: boolean;
		readFile: (absolutePath: string) => Promise<string>;
	},
) {
	const { root, inside, contextLines, effectiveLimit, limitReached, readFile } = options;
	const seen = { truncatedLine: false };
	const cut = (text: string) => {
		const line = truncateLine(text);
		seen.truncatedLine ||= line.wasTruncated;
		return line.text;
	};
	const cache = new Map<string, string[]>();
	const linesOf = async (file: string) => {
		const absolute = path.resolve(root, file);
		if (absolute !== inside && !absolute.startsWith(`${inside}${path.sep}`)) {
			return [];
		}
		let lines = cache.get(absolute);
		if (lines === undefined) {
			lines = await readFile(absolute).then(
				(content) => content.replaceAll("\r\n", "\n").replaceAll("\r", "\n").split("\n"),
				() => [],
			);
			cache.set(absolute, lines);
		}
		return lines;
	};
	const output: string[] = [];
	for (const match of matches) {
		const shown = shownPath(match.file, root);
		if (contextLines === 0 && match.text !== undefined) {
			const text = match.text.replaceAll("\r\n", "\n").replaceAll("\r", "").replace(/\n$/u, "");
			output.push(`${shown}:${String(match.line)}: ${cut(text)}`);
		} else {
			const lines = await linesOf(match.file);
			if (lines.length === 0) {
				output.push(`${shown}:${String(match.line)}: (unable to read file)`);
			} else {
				const start = Math.max(1, match.line - contextLines);
				const end = Math.min(lines.length, match.line + contextLines);
				for (let current = start; current <= end; current += 1) {
					const text = cut((lines[current - 1] ?? "").replaceAll("\r", ""));
					output.push(
						current === match.line
							? `${shown}:${String(current)}: ${text}`
							: `${shown}-${String(current)}- ${text}`,
					);
				}
			}
		}
	}
	const truncation = truncateHead(output.join("\n"), { maxLines: Number.MAX_SAFE_INTEGER });
	const details: Record<string, unknown> = {};
	const notices: string[] = [];
	if (limitReached) {
		notices.push(
			`${String(effectiveLimit)} matches limit reached. Use limit=${String(effectiveLimit * 2)} for more, or refine pattern`,
		);
		details.matchLimitReached = effectiveLimit;
	}
	if (truncation.truncated) {
		notices.push(`${formatSize(DEFAULT_MAX_BYTES)} limit reached`);
		details.truncation = truncation;
	}
	if (seen.truncatedLine) {
		notices.push(
			`Some lines truncated to ${String(GREP_MAX_LINE_LENGTH)} chars. Use read tool to see full lines`,
		);
		details.linesTruncated = true;
	}
	const text =
		notices.length === 0 ? truncation.content : `${truncation.content}\n\n[${notices.join(". ")}]`;
	return {
		content: [{ type: "text" as const, text }],
		details: Object.keys(details).length > 0 ? details : undefined,
	};
}
