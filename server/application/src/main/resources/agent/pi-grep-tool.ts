import { type Dirent, readdirSync, readFileSync, type Stats, statSync } from "node:fs";
import path from "node:path";

import { type AgentToolResult, defineTool } from "@earendil-works/pi-coding-agent";

/**
 * The search tool a review session gets instead of the SDK's `grep`, which spawns ripgrep and, when
 * it cannot, downloads one; the sandbox allows neither a child process nor the network. This walks
 * the workspace in-process with the same parameters and the same output shape as the SDK tool, so
 * the orchestrator's instructions and the model's habits carry over unchanged.
 */
export interface GrepParams {
	pattern: string;
	path?: string;
	glob?: string;
	ignoreCase?: boolean;
	literal?: boolean;
	context?: number;
	limit?: number;
}

export interface GrepDetails {
	matches: number;
	truncated: boolean;
}

const DEFAULT_LIMIT = 100;
const MAX_CONTEXT = 20;
const MAX_FILE_BYTES = 2 * 1024 * 1024;
const MAX_LINE_LENGTH = 240;
const MAX_OUTPUT_BYTES = 50 * 1024;
// Bounds the block list held before the byte cut; the `limit` parameter is the model's, this is not.
const MAX_LIMIT = 1000;
/** Never evidence, at any depth. */
const SKIPPED_ANYWHERE = new Set(["node_modules", ".git"]);
/**
 * The sandbox's own state, by its path from the workspace root; a reviewed repository may use these
 * names. `work` itself stays searchable: the precompute summary the orchestrator points at lives there.
 */
const SKIPPED_PATHS = new Set([".pi", ".sessions", "out", "work/composition"]);

function globToRegExp(glob: string): RegExp {
	let source = "";
	for (let i = 0; i < glob.length; i += 1) {
		const c = glob.charAt(i);
		if (c === "*") {
			if (glob.charAt(i + 1) === "*") {
				source += ".*";
				i += 1;
				if (glob.charAt(i + 1) === "/") {
					i += 1;
				}
			} else {
				source += "[^/]*";
			}
		} else if (c === "?") {
			source += "[^/]";
		} else {
			source += c.replaceAll(/[.+^${}()|[\]\\]/gu, String.raw`\$&`);
		}
	}
	// A glob without a slash matches a basename anywhere, as ripgrep's does.
	return new RegExp(glob.includes("/") ? `^${source}$` : `(^|/)${source}$`, "u");
}

function truncateLine(text: string): { text: string; wasTruncated: boolean } {
	return text.length > MAX_LINE_LENGTH
		? { text: `${text.slice(0, MAX_LINE_LENGTH)}…`, wasTruncated: true }
		: { text, wasTruncated: false };
}

function listFiles(cwd: string, root: string, out: string[], signal?: AbortSignal): void {
	if (signal?.aborted === true) {
		return;
	}
	let entries: Dirent[];
	try {
		entries = readdirSync(root, { withFileTypes: true });
	} catch {
		return;
	}
	entries.sort((a, b) => a.name.localeCompare(b.name));
	for (const entry of entries) {
		if (entry.isDirectory()) {
			if (SKIPPED_ANYWHERE.has(entry.name)) {
				continue;
			}
			const child = path.join(root, entry.name);
			if (SKIPPED_PATHS.has(path.relative(cwd, child).split(path.sep).join("/"))) {
				continue;
			}
			listFiles(cwd, child, out, signal);
		} else if (entry.isFile()) {
			out.push(path.join(root, entry.name));
		}
	}
}

function compilePattern(params: GrepParams): { matcher: RegExp } | { error: string } {
	const flags = params.ignoreCase === true ? "i" : "";
	const source =
		params.literal === true
			? params.pattern.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`)
			: params.pattern;
	try {
		return { matcher: new RegExp(source, flags) };
	} catch (error) {
		return { error: `Invalid pattern: ${error instanceof Error ? error.message : String(error)}` };
	}
}

/** The file's text, or `null` when it is too large, binary or unreadable and so not evidence. */
function readSearchableText(file: string): string | null {
	try {
		if (statSync(file).size > MAX_FILE_BYTES) {
			return null;
		}
		const raw = readFileSync(file);
		if (raw.includes(0)) {
			return null;
		}
		return raw.toString("utf8");
	} catch {
		return null;
	}
}

/** One match with its context lines in ripgrep's layout, and whether any line had to be cut. */
function formatMatch(
	shown: string,
	lines: readonly string[],
	lineNumber: number,
	context: number,
): { lines: string[]; truncated: boolean } {
	let truncated = false;
	const start = context > 0 ? Math.max(1, lineNumber - context) : lineNumber;
	const end = context > 0 ? Math.min(lines.length, lineNumber + context) : lineNumber;
	const formatted: string[] = [];
	for (let current = start; current <= end; current += 1) {
		const { text, wasTruncated } = truncateLine(lines[current - 1]?.replaceAll("\r", "") ?? "");
		truncated ||= wasTruncated;
		formatted.push(
			current === lineNumber ? `${shown}:${current}: ${text}` : `${shown}-${current}- ${text}`,
		);
	}
	if (context > 0) {
		formatted.push("--");
	}
	return { lines: formatted, truncated };
}

function noMatches(text: string): { text: string; details: GrepDetails } {
	return { text, details: { matches: 0, truncated: false } };
}

/** Where the search is rooted, or why nothing under `params.path` may be read. */
function resolveSearchRoot(
	cwd: string,
	params: GrepParams,
): { searchRoot: string } | { error: string } {
	const searchRoot = path.resolve(cwd, params.path ?? ".");
	const fromWorkspace = path.relative(cwd, searchRoot);
	if (fromWorkspace === ".." || fromWorkspace.startsWith(`..${path.sep}`)) {
		return { error: `Path ${params.path} is outside the workspace.` };
	}
	// The skips apply to a search rooted inside them as much as to a walk reaching them.
	const rootSegments = fromWorkspace === "" ? [] : fromWorkspace.split(path.sep);
	const rootIsSkipped =
		rootSegments.some((segment) => SKIPPED_ANYWHERE.has(segment)) ||
		rootSegments.some((_, index) => SKIPPED_PATHS.has(rootSegments.slice(0, index + 1).join("/")));
	if (rootIsSkipped) {
		return {
			error: `Path ${params.path} is the sandbox's own state, not the reviewed work; it is not searchable.`,
		};
	}
	return { searchRoot };
}

function listSearchFiles(
	cwd: string,
	searchRoot: string,
	params: GrepParams,
	signal?: AbortSignal,
): { files: string[] } | { error: string } {
	let rootStat: Stats;
	try {
		rootStat = statSync(searchRoot);
	} catch {
		return { error: `Path ${params.path ?? "."} does not exist.` };
	}
	const files: string[] = [];
	if (rootStat.isDirectory()) {
		listFiles(path.resolve(cwd), searchRoot, files, signal);
	} else {
		files.push(searchRoot);
	}
	return { files };
}

function searchOptions(params: GrepParams): {
	globMatcher: RegExp | null;
	context: number;
	limit: number;
} {
	const globMatcher =
		params.glob !== undefined && params.glob !== "" ? globToRegExp(params.glob) : null;
	const context =
		params.context !== undefined && params.context > 0
			? Math.min(MAX_CONTEXT, Math.floor(params.context))
			: 0;
	const limit = Math.min(MAX_LIMIT, Math.max(1, params.limit ?? DEFAULT_LIMIT));
	return { globMatcher, context, limit };
}

/** The file's lines, or `null` when it is not evidence (`readSearchableText`). */
function readSearchableLines(file: string): string[] | null {
	const content = readSearchableText(file);
	if (content === null) {
		return null;
	}
	const lines = content.split("\n");
	// A file's final newline ends its last line; it does not start an empty one.
	if (lines.at(-1) === "") {
		lines.pop();
	}
	return lines;
}

interface FileMatches {
	matches: number;
	/** Whether a line was left unexamined once `remaining` matches had been found. */
	truncated: boolean;
	linesTruncated: boolean;
}

function matchLines(
	shown: string,
	lines: readonly string[],
	matcher: RegExp,
	context: number,
	remaining: number,
	blocks: string[],
): FileMatches {
	let matches = 0;
	let linesTruncated = false;
	for (let index = 0; index < lines.length; index += 1) {
		if (matches >= remaining) {
			return { matches, truncated: true, linesTruncated };
		}
		const line = lines[index]?.replaceAll("\r", "") ?? "";
		matcher.lastIndex = 0;
		if (!matcher.test(line)) {
			continue;
		}
		matches += 1;
		const match = formatMatch(shown, lines, index + 1, context);
		blocks.push(...match.lines);
		linesTruncated ||= match.truncated;
	}
	return { matches, truncated: false, linesTruncated };
}

interface SearchOutcome {
	blocks: string[];
	matches: number;
	limit: number;
	aborted: boolean;
	truncated: boolean;
	linesTruncated: boolean;
}

function renderSearch(outcome: SearchOutcome): { text: string; details: GrepDetails } {
	const { blocks, matches, limit, aborted, linesTruncated } = outcome;
	let { truncated } = outcome;
	const notes: string[] = [];
	if (aborted) {
		notes.push("[Search stopped: the session's time is up]");
	}
	if (truncated) {
		notes.push(
			`[Output truncated at ${limit} matches; narrow the pattern, path or glob to see more]`,
		);
	}
	if (linesTruncated) {
		notes.push(`[Some lines were truncated at ${MAX_LINE_LENGTH} characters]`);
	}
	let body = blocks.join("\n");
	if (body.length > MAX_OUTPUT_BYTES) {
		body = body.slice(0, MAX_OUTPUT_BYTES);
		notes.push(
			`[Output truncated at ${MAX_OUTPUT_BYTES} characters; narrow the pattern, path or glob to see more]`,
		);
		truncated = true;
	}
	const text = [blocks.length === 0 ? "No matches found" : body, ...notes].join("\n");
	return { text, details: { matches, truncated } };
}

export function searchFiles(
	cwd: string,
	params: GrepParams,
	signal?: AbortSignal,
): { text: string; details: GrepDetails } {
	const root = resolveSearchRoot(cwd, params);
	if ("error" in root) {
		return noMatches(root.error);
	}
	const compiled = compilePattern(params);
	if ("error" in compiled) {
		return noMatches(compiled.error);
	}
	const { searchRoot } = root;
	const { matcher } = compiled;
	const { globMatcher, context, limit } = searchOptions(params);
	const listed = listSearchFiles(cwd, searchRoot, params, signal);
	if ("error" in listed) {
		return noMatches(listed.error);
	}

	const blocks: string[] = [];
	let matches = 0;
	let truncated = false;
	let linesTruncated = false;
	let aborted = signal?.aborted ?? false;
	for (const file of listed.files) {
		if (signal?.aborted === true) {
			aborted = true;
			break;
		}
		if (matches >= limit) {
			truncated = true;
			break;
		}
		const relativePath = path.relative(searchRoot, file);
		// A single-file search has no relative path; its name stands in, as ripgrep prints it.
		const shown = relativePath === "" ? path.basename(file) : relativePath;
		if (globMatcher !== null && !globMatcher.test(shown.split(path.sep).join("/"))) {
			continue;
		}
		const lines = readSearchableLines(file);
		if (lines === null) {
			continue;
		}
		const found = matchLines(shown, lines, matcher, context, limit - matches, blocks);
		matches += found.matches;
		truncated ||= found.truncated;
		linesTruncated ||= found.linesTruncated;
	}
	return renderSearch({ blocks, matches, limit, aborted, truncated, linesTruncated });
}

/** The model's arguments arrive untyped; anything of the wrong shape is ignored rather than trusted. */
export function readGrepParams(raw: unknown): GrepParams {
	const record: Record<string, unknown> = typeof raw === "object" && raw !== null ? { ...raw } : {};
	const string = (key: string) => {
		const value = record[key];
		return typeof value === "string" ? value : undefined;
	};
	const bool = (key: string) => {
		const value = record[key];
		return typeof value === "boolean" ? value : undefined;
	};
	const number = (key: string) => {
		const value = record[key];
		return typeof value === "number" && Number.isFinite(value) ? value : undefined;
	};
	return {
		pattern: string("pattern") ?? "",
		path: string("path"),
		glob: string("glob"),
		ignoreCase: bool("ignoreCase"),
		literal: bool("literal"),
		context: number("context"),
		limit: number("limit"),
	};
}

export function buildGrepTool(cwd: string) {
	return defineTool({
		name: "grep",
		label: "Grep",
		promptSnippet: "Search file contents for a pattern within the reviewed work",
		description:
			"Search file contents for a pattern. Returns matching lines with file paths and line numbers. " +
			`Output is truncated to ${DEFAULT_LIMIT} matches by default; use limit, path or glob to narrow it.`,
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["pattern"],
			properties: {
				pattern: { type: "string", description: "Search pattern (regex or literal string)" },
				path: {
					type: "string",
					description: "Directory or file to search (default: current directory)",
				},
				glob: {
					type: "string",
					description: "Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'",
				},
				ignoreCase: { type: "boolean", description: "Case-insensitive search (default: false)" },
				literal: {
					type: "boolean",
					description: "Treat pattern as literal string instead of regex (default: false)",
				},
				context: {
					type: "number",
					description: "Number of lines to show before and after each match (default: 0)",
				},
				limit: {
					type: "number",
					description: `Maximum number of matches to return (default: ${DEFAULT_LIMIT})`,
				},
			},
		},
		execute: async (_toolCallId, params, signal): Promise<AgentToolResult<GrepDetails>> => {
			const { text, details } = searchFiles(cwd, readGrepParams(params), signal);
			return { content: [{ type: "text", text }], details };
		},
	});
}
