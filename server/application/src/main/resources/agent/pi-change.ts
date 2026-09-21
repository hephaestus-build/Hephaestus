// The change view: everything a review reads about "what changed", derived here from the checkout
// with git. The server hands over the checkout and the pinned range (inputs/context/change.json)
// and nothing else about the change; the patch, its statistics, the changed files and the commits
// are this container's own reading of the same objects, so the layout below is owned here.
//
//   work/change/diff.patch      unified diff, renames detected, every hunk line prefixed `[L<n>] `
//                               with its NEW-side line number (OLD-side for removed lines)
//   work/change/diff_stat.txt   `git diff --stat`
//   work/change/files.json      { files: [{ status, path, oldPath? }] }
//   work/change/commits.json    { commits: [{ sha, message, author, authoredAt, committedAt, parents }] }
//   work/change/description.authored.md
//                               the description's own lines — those not from the merge request
//                               template the checkout carries — by their line numbers in description.md
//
// Run before the precompute scripts and the model. A checkout without a captured change (an issue
// review, or a change the server could not pin) writes nothing, and the manifest already says so.
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

import { SUPPORTED_SCHEMA_VERSION, resolveTaskPaths } from "./pi-task-paths.ts";

export const CHANGE_ROOT = "work/change";

/** The change the server pinned, from `<contextRoot>/change.json`; null when none was captured. */
export function readChange(contextRoot: string): { base: string; head: string } | null {
	const file = path.resolve(contextRoot, "change.json");
	if (!existsSync(file)) {
		return null;
	}
	const parsed: unknown = JSON.parse(readFileSync(file, "utf8"));
	const base: unknown =
		typeof parsed === "object" && parsed !== null ? Reflect.get(parsed, "base_sha") : null;
	const head: unknown =
		typeof parsed === "object" && parsed !== null ? Reflect.get(parsed, "head_sha") : null;
	if (typeof base !== "string" || typeof head !== "string" || !isSha(base) || !isSha(head)) {
		throw new Error("change.json: expected base_sha and head_sha as full commit ids");
	}
	return { base, head };
}

function isSha(value: string): boolean {
	return /^[0-9a-f]{40}$|^[0-9a-f]{64}$/u.test(value);
}

/**
 * Prefixes every hunk line with its source line number, the coordinate a citation of the change
 * names. Bytes are preserved: the input is handled as latin1 so a line that is not valid UTF-8 comes
 * out exactly as git printed it.
 */
export function annotateDiff(patch: Buffer): Buffer {
	const lines = patch.toString("latin1").split("\n");
	const out: string[] = [];
	let oldLine = 0;
	let newLine = 0;
	let oldLeft = 0;
	let newLeft = 0;
	for (const line of lines) {
		if (oldLeft === 0 && newLeft === 0) {
			const hunk =
				/^@@ -(?<oldStart>\d+)(?:,(?<oldCount>\d+))? \+(?<newStart>\d+)(?:,(?<newCount>\d+))? @@/u.exec(
					line,
				);
			if (hunk) {
				oldLine = Number(hunk[1]);
				oldLeft = hunk[2] === undefined ? 1 : Number(hunk[2]);
				newLine = Number(hunk[3]);
				newLeft = hunk[4] === undefined ? 1 : Number(hunk[4]);
			}
			out.push(line);
			continue;
		}
		if (line.startsWith("\\")) {
			out.push(line);
		} else if (line.startsWith("+")) {
			out.push(`[L${newLine}] ${line}`);
			newLine += 1;
			newLeft -= 1;
		} else if (line.startsWith("-")) {
			out.push(`[L${oldLine}] ${line}`);
			oldLine += 1;
			oldLeft -= 1;
		} else if (line.startsWith(" ")) {
			out.push(`[L${newLine}] ${line}`);
			newLine += 1;
			oldLine += 1;
			oldLeft -= 1;
			newLeft -= 1;
		} else {
			throw new Error(
				`git diff: unexpected line inside a hunk: ${JSON.stringify(line.slice(0, 80))}`,
			);
		}
	}
	return Buffer.from(out.join("\n"), "latin1");
}

/** `git diff --name-status -z` as records. Renames and copies carry the old path first. */
export function parseNameStatus(
	output: Buffer,
): { status: string; path: string; oldPath?: string }[] {
	const fields = output.toString("utf8").split("\0");
	if (fields.at(-1) === "") {
		fields.pop();
	}
	const files: { status: string; path: string; oldPath?: string }[] = [];
	let index = 0;
	const next = (): string | undefined => {
		const field = fields[index];
		index += 1;
		return field;
	};
	while (index < fields.length) {
		const status = next() ?? "";
		const letter = status.charAt(0);
		if (letter === "R" || letter === "C") {
			const oldPath = next();
			const newPath = next();
			if (oldPath === undefined || newPath === undefined) {
				throw new Error("git diff --name-status: truncated record");
			}
			files.push({ status: letter, path: newPath, oldPath });
		} else {
			const file = next();
			if (file === undefined) {
				throw new Error("git diff --name-status: truncated record");
			}
			files.push({ status: letter, path: file });
		}
	}
	return files;
}

/**
 * `git log` records: NUL between fields, a record separator after each commit, and the newline git
 * terminates every commit's output with, which is not part of the next record.
 */
export function parseCommits(output: Buffer) {
	const records = output
		.toString("utf8")
		.split("\u001E")
		.map((record) => record.replace(/^\n/u, ""))
		.filter((record) => record !== "");
	return records.map((record) => {
		const [sha, parents, author, authoredAt, committer, committedAt, message] = record.split("\0");
		if (sha === undefined || message === undefined) {
			throw new Error("git log: truncated record");
		}
		return {
			sha,
			parents: parents === undefined || parents === "" ? [] : parents.split(" "),
			author: author ?? "",
			authoredAt: authoredAt ?? "",
			committer: committer ?? "",
			committedAt: committedAt ?? "",
			message: message.replace(/\n$/u, ""),
		};
	});
}

const RENAMES = "--find-renames=50%";
const MAX_OUTPUT_BYTES = 256 * 1024 * 1024;

function git(repository: string, args: string[]): Buffer {
	// A path is printed as its bytes, not as git's quoted-octal rendering: the path a citation names is
	// the path the checkout has, and the server verifies it against the same bytes.
	const child = spawnSync(
		"git",
		["-C", repository, "--no-pager", "-c", "core.quotePath=false", ...args],
		{
			maxBuffer: MAX_OUTPUT_BYTES,
			env: { ...process.env, GIT_TERMINAL_PROMPT: "0", GIT_OPTIONAL_LOCKS: "0" },
		},
	);
	if (child.error) {
		throw child.error;
	}
	if (child.status !== 0) {
		throw new Error(
			`git ${args[0]} failed (${child.status}): ${child.stderr.toString("utf8").trim()}`,
		);
	}
	return child.stdout;
}

/** Where a hosting provider keeps the form a description is opened with, relative to the checkout. */
const DESCRIPTION_TEMPLATE_PATH =
	/^(?:\.gitlab\/merge_request_templates\/[^/]+\.md|(?:\.github\/|docs\/)?pull_request_template(?:\/[^/]+)?\.md)$/iu;

/**
 * A line as a template and a description are compared: whitespace collapsed, a checkbox with its
 * tick removed, so a template item the author ticked is still the template's line.
 */
function comparable(line: string): string {
	return line
		.replaceAll(/<!--[\s\S]*?-->/gu, "")
		.trim()
		.replaceAll(/\s+/gu, " ")
		.replace(/^(?<marker>[-*+]\s*)\[[ xX]\]/u, "$<marker>[ ]");
}

/**
 * Whether a description line is the form's. The form's line verbatim; the opening of one, since a
 * form's label (`- [ ] **Requirement:**`) stays when the placeholder after it moves to its own line
 * or gets an answer; or a bare dash, the placeholder itself. An answer as short as "N/A" is the
 * author's even when the form's guidance mentions it.
 */
function fromTemplate(line: string, templateLines: ReadonlySet<string>): boolean {
	if (templateLines.has(line)) {
		return true;
	}
	if (/^[-–—]$/u.test(line)) {
		return [...templateLines].some((candidate) => candidate.includes(line));
	}
	return line.length >= 8 && [...templateLines].some((candidate) => candidate.startsWith(line));
}

/** The line numbers (1-based) with nothing outside an HTML comment: a form's guidance, which nobody writes. */
function commentedLines(lines: readonly string[]): Set<number> {
	const inside = new Set<number>();
	let open = false;
	for (const [index, line] of lines.entries()) {
		let outside = "";
		let rest = line;
		while (rest.length > 0) {
			if (open) {
				const end = rest.indexOf("-->");
				if (end === -1) {
					break;
				}
				open = false;
				rest = rest.slice(end + 3);
			} else {
				const start = rest.indexOf("<!--");
				if (start === -1) {
					outside += rest;
					break;
				}
				outside += rest.slice(0, start);
				open = true;
				rest = rest.slice(start + 4);
			}
		}
		if (outside.trim() === "" && outside.length < line.length) {
			inside.add(index + 1);
		}
	}
	return inside;
}

/** The templates the checkout carries at the reviewed head, by path. */
function descriptionTemplates(repository: string, head: string): Map<string, string> {
	const templates = new Map<string, string>();
	const listing = git(repository, ["ls-tree", "-r", "--name-only", "-z", head]).toString("utf8");
	for (const file of listing.split("\0")) {
		if (DESCRIPTION_TEMPLATE_PATH.test(file)) {
			templates.set(file, git(repository, ["show", `${head}:${file}`]).toString("utf8"));
		}
	}
	return templates;
}

/**
 * The description's own lines, apart from the form it was opened with. A pull request template ships
 * headings, checklists, placeholders and HTML comments, and a description that keeps them reads as
 * if the author wrote them: a "Closes #12" example became a linked issue, a checklist became the
 * issue's criteria, and a requirement heading became a reason. The template that shares the most
 * lines with the description is the one the form used; a line it carries is the template's, a line
 * inside an HTML comment is the template's, and the rest is the author's.
 */
export function authoredDescription(
	description: string,
	templates: ReadonlyMap<string, string>,
): { template: string | null; authored: number[]; ticked: number[]; total: number } {
	const lines = description.split(/\r?\n/u);
	const commented = commentedLines(lines);
	let template: string | null = null;
	let templateLines = new Set<string>();
	let best = 0;
	for (const [file, text] of templates) {
		const candidate = new Set(
			text
				.split(/\r?\n/u)
				.map(comparable)
				.filter((line) => line !== ""),
		);
		const shared = lines.filter((line) => fromTemplate(comparable(line), candidate)).length;
		if (shared > best) {
			best = shared;
			template = file;
			templateLines = candidate;
		}
	}
	const authored: number[] = [];
	const ticked: number[] = [];
	for (const [index, line] of lines.entries()) {
		const number = index + 1;
		if (line.trim() === "" || commented.has(number)) {
			continue;
		}
		if (fromTemplate(comparable(line), templateLines)) {
			if (/^\s*[-*+]\s*\[[xX]\]/u.test(line)) {
				ticked.push(number);
			}
			continue;
		}
		authored.push(number);
	}
	return { template, authored, ticked, total: lines.length };
}

/** `description.authored.md`: what the author wrote, cited by the line numbers of description.md. */
export function renderAuthoredDescription(
	description: string,
	view: ReturnType<typeof authoredDescription>,
): string {
	const lines = description.split(/\r?\n/u);
	const at = (number: number) => `[L${number}] ${lines[number - 1] ?? ""}`;
	const head = [
		"# The description's own words",
		"",
		view.template === null
			? "No merge request template is in the checkout, so every line is the author's; lines inside HTML comments are left out."
			: `Template \`${view.template}\`: ${view.total - view.authored.length} of the description's ${view.total} lines are the form's own (headings, checklist labels, placeholders, comments). The lines below are the author's. Cite them from \`inputs/context/description.md\` by these numbers.`,
		"",
	];
	const body =
		view.authored.length === 0
			? ["(The author added no line of their own.)"]
			: view.authored.map(at);
	const ticked =
		view.ticked.length === 0
			? []
			: ["", "Checklist items of the template the author ticked:", ...view.ticked.map(at)];
	return `${[...head, ...body, ...ticked].join("\n")}\n`;
}

export function writeChangeView(
	root: string,
	repository: string,
	change: { base: string; head: string },
	description: string | null = null,
) {
	const out = path.resolve(root, CHANGE_ROOT);
	mkdirSync(out, { recursive: true });
	const range = [change.base, change.head];
	const patch = git(repository, ["diff", "--no-color", "--no-ext-diff", RENAMES, ...range]);
	writeFileSync(path.resolve(out, "diff.patch"), annotateDiff(patch));
	writeFileSync(
		path.resolve(out, "diff_stat.txt"),
		git(repository, ["diff", "--no-color", "--no-ext-diff", "--stat=200", RENAMES, ...range]),
	);
	const files = parseNameStatus(
		git(repository, ["diff", "--no-color", "--name-status", "-z", RENAMES, ...range]),
	);
	writeFileSync(path.resolve(out, "files.json"), `${JSON.stringify({ files }, null, 2)}\n`);
	const commits = parseCommits(
		git(repository, [
			"log",
			"--topo-order",
			"--reverse",
			"--format=%H%x00%P%x00%an%x00%aI%x00%cn%x00%cI%x00%B%x1e",
			`${change.base}..${change.head}`,
		]),
	);
	writeFileSync(path.resolve(out, "commits.json"), `${JSON.stringify({ commits }, null, 2)}\n`);
	if (description !== null) {
		const view = authoredDescription(description, descriptionTemplates(repository, change.head));
		writeFileSync(
			path.resolve(out, "description.authored.md"),
			renderAuthoredDescription(description, view),
		);
	}
}

/** The description the server staged beside the change, or null when the review has none. */
export function readDescription(contextRoot: string): string | null {
	const file = path.resolve(contextRoot, "description.md");
	return existsSync(file) ? readFileSync(file, "utf8") : null;
}

if (import.meta.filename === process.argv[1]) {
	const root = path.resolve(process.argv[2] ?? "/workspace");
	const envelope: unknown = JSON.parse(readFileSync(path.resolve(root, "task.json"), "utf8"));
	if (typeof envelope !== "object" || envelope === null) {
		throw new Error("task.json: expected an envelope");
	}
	if (Reflect.get(envelope, "schemaVersion") !== SUPPORTED_SCHEMA_VERSION) {
		throw new Error("task.json: unsupported schemaVersion");
	}
	const paths = resolveTaskPaths(root, Reflect.get(envelope, "paths"));
	const change = readChange(paths.contextRoot);
	if (change !== null) {
		writeChangeView(root, paths.repositoryRoot, change, readDescription(paths.contextRoot));
	}
}
