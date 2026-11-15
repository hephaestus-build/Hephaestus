// Build the change view from the captured checkout and pinned range before any script reads it. A review whose practices
// read the change but not the repository has no checkout: its view comes from the diff that the server captured.
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

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
	if (
		typeof base !== "string" ||
		typeof head !== "string" ||
		!isCommitId(base) ||
		!isCommitId(head)
	) {
		throw new Error("change.json: expected base_sha and head_sha as full commit ids");
	}
	return { base, head };
}

export function isCommitId(value: string): boolean {
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

/**
 * Where each file's section of the annotated diff starts and ends, 1-based and inclusive, keyed by its
 * `diff --git` header without the prefix (`a/<old> b/<new>`): what lets a large diff be read one file
 * at a time.
 */
function diffSections(annotated: string): Map<string, [number, number]> {
	const lines = annotated.split("\n");
	const sections = new Map<string, [number, number]>();
	let header: string | null = null;
	let start = 0;
	for (const [index, line] of lines.entries()) {
		if (line.startsWith("diff --git ")) {
			if (header !== null) {
				sections.set(header, [start, index]);
			}
			header = line.slice("diff --git ".length);
			start = index + 1;
		}
	}
	if (header !== null) {
		sections.set(header, [start, lines.at(-1) === "" ? lines.length - 1 : lines.length]);
	}
	return sections;
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

const RENAMES = "--find-renames=50%";
const MAX_OUTPUT_BYTES = 256 * 1024 * 1024;

function git(
	repository: string,
	args: [string, ...string[]],
	maxBuffer = MAX_OUTPUT_BYTES,
): Buffer {
	// A path is printed as its bytes, not as git's quoted-octal rendering: the path a citation names is
	// the path the checkout has, and the server verifies it against the same bytes.
	const child = spawnSync(
		"git",
		["-C", repository, "--no-pager", "-c", "core.quotePath=false", ...args],
		{
			maxBuffer,
			env: { ...process.env, GIT_TERMINAL_PROMPT: "0", GIT_OPTIONAL_LOCKS: "0" },
		},
	);
	if (child.error) {
		throw child.error;
	}
	if (child.status !== 0) {
		const ending =
			child.status === null ? `signal ${child.signal ?? "unknown"}` : `exit ${child.status}`;
		throw new Error(`git ${args[0]} failed (${ending}): ${child.stderr.toString("utf8").trim()}`);
	}
	return child.stdout;
}

/** What a pinned commit holds at a path, read from Git objects and never from the working tree. */
export type PinnedBlob =
	| { kind: "regular"; bytes: Buffer }
	| { kind: "tooLarge"; size: number }
	| { kind: "absent" | "nonregular" | "unsafe" | "unreadable" };

/** A relative path a citation may name: no traversal, `.git`, backslash or control character in any segment. */
export function safeRepositoryPath(file: string): boolean {
	return (
		file !== "" &&
		!/[\\\p{Cc}]/u.test(file) &&
		file
			.split("/")
			.every(
				(part) => part !== "" && part !== "." && part !== ".." && part.toLowerCase() !== ".git",
			)
	);
}

/** Where the change view records the commit that the checkout holds. */
export const CHECKOUT_COMMIT = `${CHANGE_ROOT}/checkout-commit`;

/** The commit that the change view read before anything untrusted ran, or null when there is no checkout. */
export function pinnedCheckout(root: string): string | null {
	const file = path.resolve(root, CHECKOUT_COMMIT);
	if (!existsSync(file)) {
		return null;
	}
	const commit = readFileSync(file, "utf8").trim();
	return isCommitId(commit) ? commit : null;
}

/** The commit the checkout has checked out, or null when it cannot be read. */
export function checkedOutCommit(repository: string): string | null {
	try {
		const head = git(repository, ["rev-parse", "--verify", "HEAD^{commit}"])
			.toString("utf8")
			.trim();
		return isCommitId(head) ? head : null;
	} catch {
		return null;
	}
}

/** Where a hosting provider keeps the form a description is opened with, relative to the checkout. */
const DESCRIPTION_TEMPLATE_PATH =
	/^(?:\.gitlab\/merge_request_templates\/[^/]+\.md|(?:\.github\/|docs\/)?pull_request_template(?:\/[^/]+)?\.md)$/iu;

/**
 * A line as a template and a description are compared: whitespace collapsed, a checkbox with its
 * tick removed, so a template item the author ticked is still the template's line.
 */
function comparable(line: string): string {
	let text = line;
	let previous;
	do {
		previous = text;
		text = text.replaceAll(/<!--[\s\S]*?-->/gu, " ");
	} while (text !== previous);
	return text
		.trim()
		.replaceAll(/\s+/gu, " ")
		.replace(/^(?<marker>[-*+]\s*)\[[ xX]\]/u, "$<marker>[ ]");
}

/** Match template text and label prefixes, but keep authored answers such as "N/A". */
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
 * Remove HTML comments and lines from the template with the most matches. This is a heuristic:
 * the captured description remains authoritative, and retained lines keep their original numbers.
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
	const range = [change.base, change.head];
	writeView(
		root,
		annotateDiff(git(repository, ["diff", "--no-color", "--no-ext-diff", RENAMES, ...range])),
		git(repository, ["diff", "--no-color", "--no-ext-diff", "--stat=200", RENAMES, ...range]),
		parseNameStatus(
			git(repository, ["diff", "--no-color", "--name-status", "-z", RENAMES, ...range]),
		),
		description === null
			? null
			: renderAuthoredDescription(
					description,
					authoredDescription(description, descriptionTemplates(repository, change.head)),
				),
	);
}

/**
 * The change view from the diff that the server captured (`diff` in `change.json`), for a review that has no checkout:
 * the server diffed the same pinned range with renames found at 50 %, and admission checks a quote of the change against
 * the same diff. No authored view of the description is written: its templates are files of the repository, so a form's
 * lines cannot be told apart here, and a practice then reads `description.md` as it is.
 */
export function writeCapturedChangeView(root: string, diff: string) {
	const patch = withPlainPaths(diff);
	const files = patchFiles(patch);
	writeView(root, annotateDiff(Buffer.from(patch, "utf8")), patchStat(patch, files), files, null);
}

/** The diff that `change.json` holds, or null when it holds none. */
export function readCapturedDiff(contextRoot: string): string | null {
	const parsed: unknown = JSON.parse(
		readFileSync(path.resolve(contextRoot, "change.json"), "utf8"),
	);
	const diff: unknown =
		typeof parsed === "object" && parsed !== null ? Reflect.get(parsed, "diff") : null;
	return typeof diff === "string" ? diff : null;
}

function writeView(
	root: string,
	annotated: Buffer,
	stat: Buffer | string,
	files: { status: string; path: string; oldPath?: string }[],
	authored: string | null,
) {
	const out = path.resolve(root, CHANGE_ROOT);
	mkdirSync(out, { recursive: true });
	writeFileSync(path.resolve(out, "diff.patch"), annotated);
	writeFileSync(path.resolve(out, "diff_stat.txt"), stat);
	const sections = diffSections(annotated.toString("utf8"));
	const indexed = files.map((file) => {
		const lines = sections.get(`a/${file.oldPath ?? file.path} b/${file.path}`);
		return lines === undefined ? file : { ...file, diffPatchLines: lines };
	});
	writeFileSync(
		path.resolve(out, "files.json"),
		`${JSON.stringify({ files: indexed }, null, 2)}\n`,
	);
	if (authored !== null) {
		writeFileSync(path.resolve(out, "description.authored.md"), authored);
	}
}

/** The header lines of a file's section of a unified diff, outside its hunks, that name a path. */
const PATH_HEADER =
	/^(?<prefix>--- |\+\+\+ |rename from |rename to |copy from |copy to )(?<path>.*)$/u;

/**
 * The diff with every quoted path written as itself, as the checkout's view prints it: JGit quotes a path outside
 * printable ASCII in octal (`"a/W\303\266rdle.md"`). Hunk lines stay as they are.
 */
export function withPlainPaths(diff: string): string {
	const out: string[] = [];
	let oldLeft = 0;
	let newLeft = 0;
	for (const line of diff.split("\n")) {
		if (oldLeft > 0 || newLeft > 0) {
			if (line.startsWith("+")) {
				newLeft -= 1;
			} else if (line.startsWith("-")) {
				oldLeft -= 1;
			} else if (line.startsWith(" ")) {
				oldLeft -= 1;
				newLeft -= 1;
			}
			out.push(line);
			continue;
		}
		const hunk = /^@@ -\d+(?:,(?<oldCount>\d+))? \+\d+(?:,(?<newCount>\d+))? @@/u.exec(line);
		if (hunk) {
			oldLeft = hunk.groups?.oldCount === undefined ? 1 : Number(hunk.groups.oldCount);
			newLeft = hunk.groups?.newCount === undefined ? 1 : Number(hunk.groups.newCount);
			out.push(line);
			continue;
		}
		if (line.startsWith("diff --git ")) {
			const [from, to] = gitPathPair(line.slice("diff --git ".length));
			out.push(`diff --git ${from} ${to}`);
			continue;
		}
		const header = PATH_HEADER.exec(line);
		const prefix = header?.groups?.prefix;
		out.push(prefix === undefined ? line : `${prefix}${unquoted(header?.groups?.path ?? "")}`);
	}
	return out.join("\n");
}

/** The two paths of a `diff --git` header, unquoted: `a/<old> b/<new>`. */
function gitPathPair(header: string): [string, string] {
	if (header.startsWith('"')) {
		const end = quotedEnd(header);
		return [unquoted(header.slice(0, end)), unquoted(header.slice(end + 1))];
	}
	if (header.endsWith('"')) {
		const start = header.lastIndexOf(' "');
		return [header.slice(0, start), unquoted(header.slice(start + 1))];
	}
	// Unquoted, the two paths are equal unless the section says otherwise: split at the middle.
	const half = (header.length - 1) / 2;
	return Number.isInteger(half) && header.slice(2, half) === header.slice(half + 3)
		? [header.slice(0, half), header.slice(half + 1)]
		: [header.slice(0, header.indexOf(" b/")), header.slice(header.indexOf(" b/") + 1)];
}

/** The index just past the closing quote of the quoted path that `text` starts with. */
function quotedEnd(text: string): number {
	for (let index = 1; index < text.length; index += 1) {
		if (text[index] === "\\") {
			index += 1;
		} else if (text[index] === '"') {
			return index + 1;
		}
	}
	throw new Error(`git diff: unterminated quoted path: ${JSON.stringify(text.slice(0, 80))}`);
}

const ESCAPES: Readonly<Record<string, number>> = {
	a: 7,
	b: 8,
	t: 9,
	n: 10,
	v: 11,
	f: 12,
	r: 13,
	'"': 34,
	"\\": 92,
};

/**
 * A path as Git writes it, quoted or not, as the UTF-8 text it names. A path with a control character stays quoted, as
 * Git prints it with `core.quotePath=false`, so a header stays one line.
 */
function unquoted(text: string): string {
	const plain = decoded(text);
	for (let index = 0; index < plain.length; index += 1) {
		const code = plain.codePointAt(index) ?? 0;
		if (code < 0x20 || code === 0x7f) {
			return text;
		}
	}
	return plain;
}

function decoded(text: string): string {
	if (!text.startsWith('"') || !text.endsWith('"') || text.length < 2) {
		return text;
	}
	const bytes: number[] = [];
	const body = text.slice(1, -1);
	for (let index = 0; index < body.length; index += 1) {
		const char = body[index] ?? "";
		if (char !== "\\") {
			bytes.push(...Buffer.from(char, "utf8"));
			continue;
		}
		const octal = /^[0-7]{3}/u.exec(body.slice(index + 1));
		if (octal) {
			bytes.push(Number.parseInt(octal[0], 8));
			index += 3;
			continue;
		}
		const escaped = ESCAPES[body[index + 1] ?? ""];
		if (escaped === undefined) {
			throw new Error(`git diff: unknown escape in ${JSON.stringify(text)}`);
		}
		bytes.push(escaped);
		index += 1;
	}
	return Buffer.from(bytes).toString("utf8");
}

/** Each file of a diff with plain paths, as `git diff --name-status` names it, in the diff's order. */
export function patchFiles(patch: string): { status: string; path: string; oldPath?: string }[] {
	const files: { status: string; path: string; oldPath?: string }[] = [];
	let current: { status: string; path: string; oldPath?: string } | null = null;
	let inHunk = false;
	for (const line of patch.split("\n")) {
		if (line.startsWith("diff --git ")) {
			const [from, to] = gitPathPair(line.slice("diff --git ".length));
			current = { status: "M", path: to.slice(2) };
			if (from.slice(2) !== to.slice(2)) {
				current.oldPath = from.slice(2);
			}
			files.push(current);
			inHunk = false;
		} else if (current !== null && !inHunk) {
			if (line.startsWith("@@ ")) {
				inHunk = true;
			} else if (line.startsWith("new file mode")) {
				current.status = "A";
			} else if (line.startsWith("deleted file mode")) {
				current.status = "D";
			} else if (line.startsWith("rename from ")) {
				current.status = "R";
				current.oldPath = line.slice("rename from ".length);
			} else if (line.startsWith("rename to ")) {
				current.path = line.slice("rename to ".length);
			} else if (line.startsWith("copy from ")) {
				current.status = "C";
				current.oldPath = line.slice("copy from ".length);
			} else if (line.startsWith("copy to ")) {
				current.path = line.slice("copy to ".length);
			}
		}
	}
	for (const file of files) {
		if (file.status !== "R" && file.status !== "C") {
			delete file.oldPath;
		}
	}
	return files;
}

/** `count word` with the word plural unless the count is one, as `git diff --stat` writes its summary. */
function plural(count: number, word: string): string {
	return `${count} ${word}${count === 1 ? "" : "s"}`;
}

/** The lines of each file's section of a diff, from its `diff --git` header on. */
function sectionsOf(patch: string): string[][] {
	const sections: string[][] = [];
	for (const line of patch.split("\n")) {
		if (line.startsWith("diff --git ")) {
			sections.push([]);
		}
		sections.at(-1)?.push(line);
	}
	return sections;
}

/** `git diff --stat`'s count of each file and its summary line, from the diff's hunks. */
function patchStat(patch: string, files: { path: string; oldPath?: string }[]): string {
	const counts: { added: number; removed: number }[] = [];
	let count: { added: number; removed: number } | null = null;
	let oldLeft = 0;
	let newLeft = 0;
	for (const line of patch.split("\n")) {
		if (oldLeft > 0 || newLeft > 0) {
			if (line.startsWith("+") && count !== null) {
				count.added += 1;
				newLeft -= 1;
			} else if (line.startsWith("-") && count !== null) {
				count.removed += 1;
				oldLeft -= 1;
			} else if (line.startsWith(" ")) {
				oldLeft -= 1;
				newLeft -= 1;
			}
			continue;
		}
		if (line.startsWith("diff --git ")) {
			count = { added: 0, removed: 0 };
			counts.push(count);
			continue;
		}
		const hunk = /^@@ -\d+(?:,(?<oldCount>\d+))? \+\d+(?:,(?<newCount>\d+))? @@/u.exec(line);
		if (hunk) {
			oldLeft = hunk.groups?.oldCount === undefined ? 1 : Number(hunk.groups.oldCount);
			newLeft = hunk.groups?.newCount === undefined ? 1 : Number(hunk.groups.newCount);
		}
	}
	if (files.length === 0) {
		return "";
	}
	const binary = sectionsOf(patch).map((section) =>
		section.some((line) => line.startsWith("Binary files ")),
	);
	const rows = files.map((file, index) => {
		const { added, removed } = counts[index] ?? { added: 0, removed: 0 };
		const name = file.oldPath === undefined ? file.path : `${file.oldPath} => ${file.path}`;
		if (binary[index] === true) {
			return ` ${name} | Bin`;
		}
		const bar = `${"+".repeat(Math.min(added, 50))}${"-".repeat(Math.min(removed, 50))}`;
		return ` ${name} | ${added + removed}${bar === "" ? "" : ` ${bar}`}`;
	});
	const added = counts.reduce((sum, entry) => sum + entry.added, 0);
	const removed = counts.reduce((sum, entry) => sum + entry.removed, 0);
	const summary = [
		` ${plural(files.length, "file")} changed`,
		...(added > 0 ? [`${plural(added, "insertion")}(+)`] : []),
		...(removed > 0 ? [`${plural(removed, "deletion")}(-)`] : []),
	].join(", ");
	return `${[...rows, summary].join("\n")}\n`;
}

/** The description the server staged beside the change, or null when the review has none. */
export function readDescription(contextRoot: string): string | null {
	const file = path.resolve(contextRoot, "description.md");
	return existsSync(file) ? readFileSync(file, "utf8") : null;
}
