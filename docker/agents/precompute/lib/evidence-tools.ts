/** Read-only repository tools for a precompute agent. */
import { readdir, readFile, realpath, stat } from "node:fs/promises";
import path from "node:path";

import { tool } from "ai";
import { z } from "zod";

import { grep } from "./grep.ts";

const MAX_LINES = 200;
/** A larger file is not read: a model can name any file, and the script's heap is small. */
const MAX_FILE_BYTES = 2 * 1024 * 1024;
const MAX_ENTRIES = 200;
const MAX_MATCHES = 50;

/** A path inside the repository, symlinks resolved: a tool never reads outside it. */
async function inside(repo: string, relative: string): Promise<string> {
	const root = await realpath(repo);
	const full = await realpath(path.resolve(root, relative));
	if (full !== root && !full.startsWith(`${root}${path.sep}`)) {
		throw new Error(`${relative} is outside the repository`);
	}
	return full;
}

export function evidenceTools(repo: string) {
	return {
		readFile: tool({
			description: `Read lines of a repository file, numbered. At most ${MAX_LINES} lines per call.`,
			inputSchema: z.object({
				path: z.string().describe("Path relative to the repository root"),
				startLine: z.int().min(1).optional(),
				endLine: z.int().min(1).optional(),
			}),
			execute: async ({ path: relative, startLine = 1, endLine }) => {
				const file = await inside(repo, relative);
				const stats = await stat(file);
				if (!stats.isFile()) {
					return `${relative} is not a file`;
				}
				if (stats.size > MAX_FILE_BYTES) {
					return `${relative} is larger than ${MAX_FILE_BYTES} bytes; search it with grep`;
				}
				const content = await readFile(file, "utf8");
				const lines = content.split("\n");
				const last = Math.min(
					endLine ?? startLine + MAX_LINES - 1,
					startLine + MAX_LINES - 1,
					lines.length,
				);
				return (
					lines
						.slice(startLine - 1, last)
						.map((text, i) => `${startLine + i}: ${text}`)
						.join("\n") || "(no lines in that range)"
				);
			},
		}),
		listFiles: tool({
			description: `List the entries of a repository folder. At most ${MAX_ENTRIES}.`,
			inputSchema: z.object({ dir: z.string().describe("Folder relative to the repository root") }),
			execute: async ({ dir }) => {
				const entries = await readdir(await inside(repo, dir), { withFileTypes: true });
				return entries
					.slice(0, MAX_ENTRIES)
					.map((entry) => (entry.isDirectory() ? `${entry.name}/` : entry.name))
					.join("\n");
			},
		}),
		grep: tool({
			description: `Search repository files with an extended regular expression. At most ${MAX_MATCHES} matches, as path:line: text.`,
			inputSchema: z.object({
				pattern: z.string(),
				glob: z.string().optional().describe('A file glob such as "*.ts"'),
			}),
			execute: async ({ pattern, glob }) => {
				const matches = await grep(pattern, repo, { glob, maxResults: MAX_MATCHES });
				return (
					matches.map((m) => `${m.file}:${m.line}: ${m.content.slice(0, 300)}`).join("\n") ||
					"(no matches)"
				);
			},
		}),
	};
}
