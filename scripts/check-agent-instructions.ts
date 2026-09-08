/**
 * An instruction file that no agent loads fails in complete silence.
 *
 * Claude Code discovers a nested `CLAUDE.md` and never a nested `AGENTS.md`
 * (https://code.claude.com/docs/en/memory), so a package guide reaches it only through a `CLAUDE.md`
 * beside it that imports it. Every check here is that defect from a different side — a pointer into
 * content, with nothing verifying the pointer arrives:
 *
 *   symlinked   — an agent file committed as a symlink, which a Windows checkout gets as text.
 *   unreachable — an `AGENTS.md` the `CLAUDE.md` beside it does not import.
 *   dangling    — an `@` reference resolving to nothing.
 *   uncovered   — an `AGENTS.md` no opencode `instructions` entry matches, or an entry matching none.
 *   uncommanded — a typed-only skill with no opencode command to type.
 *   unmirrored  — a Codex copy of a skill that has drifted from the Claude Code original.
 *   duplicated  — two agent files with one body.
 *   stale       — a contributor document names a repository path or npm package that does not resolve.
 *
 * `unread` runs before all of them and reports this gate's own blind spot: an agent file the
 * classifier never opened, which every check below would otherwise read as a file that says nothing.
 *
 * The file set is `git ls-files --cached --others --exclude-standard`, read from the working tree
 * rather than the index: `vp run check` runs before `git add`, so a gate answering about the last
 * commit would disagree with the tree in front of you.
 */
import { execFile } from "node:child_process";
import { lstat, readFile, readlink } from "node:fs/promises";
import { resolve } from "node:path";
import { basename, dirname, join, matchesGlob, normalize } from "node:path/posix";
import { promisify } from "node:util";

import { fromMarkdown } from "mdast-util-from-markdown";
import { mdxFromMarkdown } from "mdast-util-mdx";
import { mdxjs } from "micromark-extension-mdxjs";

import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asRecord, asStringArray, parseJson } from "./lib/json.ts";

/** Resolved from this file, so the gate answers the same whatever the working directory is. */
const REPO_ROOT = resolve(import.meta.dirname, "..");

const OPENCODE_CONFIG = "opencode.json";
const SKILLS_ROOT = ".claude/skills";
const COMMANDS_ROOT = ".opencode/commands";

/**
 * Directories holding markdown addressed to an agent rather than to a reader. Each is read by a
 * different tool: Claude Code reads `.claude/` only, Codex scans `.agents/skills` only
 * (https://developers.openai.com/codex/skills), and opencode reads all three.
 */
const AGENT_ROOTS = [".claude/", ".opencode/", ".agents/"];

const isContributorDoc = (path: string): boolean =>
	path.startsWith("docs/contributor/")
		? /\.mdx?$/.test(path)
		: !path.includes("/") && path.endsWith(".md");

/**
 * Codex and Claude Code share no skills directory, so a skill both must reach exists twice. That is
 * the one duplication this repo keeps, and `mirrored` is what stops the two halves drifting.
 */
const CODEX_SKILLS_ROOT = ".agents/skills";

/**
 * The skills Codex is meant to have: the four that drive a contribution. Codex has no repo-level
 * slash commands — `~/.codex/prompts/` is personal and uncommitted — so a shared workflow reaches it
 * as a skill or not at all (https://developers.openai.com/codex/custom-prompts).
 *
 * The knowledge packs are deliberately absent. `react-best-practices` alone is 50 files, and a Codex
 * session that needs them is working in `webapp/`, whose `AGENTS.md` names them by path.
 *
 * Keeping the two halves identical is `unmirrored`'s job; this list is what stops a pair quietly
 * becoming one, which is how `/gh-stack` was lost from Codex once already. Naming a skill
 * `.claude/skills/` does not have fails too, so a rename cannot leave a dead entry behind.
 */
export const CODEX_SKILLS = ["fix-ci", "gh-stack", "land-pr", "resolve-review"];

const mirrorOf = (path: string): string | undefined =>
	path.startsWith(`${CODEX_SKILLS_ROOT}/`)
		? `${SKILLS_ROOT}/${path.slice(CODEX_SKILLS_ROOT.length + 1)}`
		: undefined;

const underAgentRoot = (path: string): boolean => AGENT_ROOTS.some((root) => path.startsWith(root));

/**
 * Anything an agent loads from, by name or by location. Not narrowed to markdown: a symlink into
 * `.claude/skills/` links a directory and carries no extension.
 */
const isAgentPath = (path: string): boolean =>
	basename(path) === "AGENTS.md" || basename(path) === "CLAUDE.md" || underAgentRoot(path);

const isAgentMarkdown = (path: string): boolean => isAgentPath(path) && path.endsWith(".md");

/**
 * A path in the checkout and what this analysis knows about it. `opaque` is a file no check needs to
 * read; the CLI's own scope decides which files become `text`, so a check that starts needing a new
 * one widens that scope rather than reading `opaque` as empty.
 */
export type TrackedFile =
	| { readonly path: string; readonly kind: "symlink"; readonly target: string }
	| { readonly path: string; readonly kind: "opaque" }
	| { readonly path: string; readonly kind: "text"; readonly content: string };

export interface Snapshot {
	readonly files: readonly TrackedFile[];
}

interface Repo {
	readonly present: ReadonlyMap<string, TrackedFile>;
	readonly paths: readonly string[];
	readonly byName: ReadonlyMap<string, readonly string[]>;
	/** The `AGENTS.md` files that are this repo's own guidance, in the trees they describe. */
	readonly guides: readonly string[];
	readonly commands: readonly string[];
}

interface ClaimException {
	readonly document: string;
	readonly value: string;
	readonly reason: string;
}

const INTENTIONALLY_MISSING_PATHS = [
	{
		document: "docs/contributor/agent/workspace-abi.mdx",
		value: ".sessions",
		reason: "a per-run directory created inside the agent workspace",
	},
	{
		document: "MIGRATION.md",
		value: "docker/.env",
		reason: "a deployment-local secrets file that must stay untracked",
	},
	{
		document: "MIGRATION.md",
		value: "docker/agent-image-pin.env",
		reason: "a removed path retained in migration history",
	},
	{
		document: "MIGRATION.md",
		value: "docker/agent-image-pin.local.env",
		reason: "a removed path retained in migration history",
	},
	{
		document: "AGENTS.md",
		value: "server/.env",
		reason: "a developer-local secrets file that must stay untracked",
	},
	{
		document: "docs/contributor/local-development.mdx",
		value: "server/.env",
		reason: "a developer-local secrets file that must stay untracked",
	},
	{
		document: "docs/contributor/local-development.mdx",
		value: "server/postgres-data",
		reason: "a runtime data directory created by PostgreSQL and deliberately untracked",
	},
	{
		document: "docs/contributor/local-development.mdx",
		value: "webapp/.env",
		reason: "a developer-local environment file that must stay untracked",
	},
	{
		document: "docs/contributor/agent/workspace-abi.mdx",
		value: "inputs/manifest.json",
		reason: "a path inside the staged agent workspace, not the repository checkout",
	},
	{
		document: "docs/contributor/agent/workspace-abi.mdx",
		value: "out/result.json",
		reason: "a path inside the staged agent workspace, not the repository checkout",
	},
	{
		document: "docs/contributor/agent/workspace-abi.mdx",
		value: "out/watchdog-killed.json",
		reason: "a path inside the staged agent workspace, not the repository checkout",
	},
	{
		document: "docs/contributor/artifact-source-contract.mdx",
		value: "inputs/manifest.json",
		reason: "a path inside the staged agent workspace, not the repository checkout",
	},
	{
		document: "docs/contributor/practice-review-glossary.mdx",
		value: "inputs/history/delta.json",
		reason: "a path inside the staged agent workspace, not the repository checkout",
	},
	{
		document: "docs/contributor/practice-review-glossary.mdx",
		value: "out/feedback.json",
		reason: "a path inside the staged agent workspace, not the repository checkout",
	},
] satisfies readonly ClaimException[];

const PACKAGE_NAME = /^(?:@[a-z0-9][a-z0-9._-]*\/[a-z0-9][a-z0-9._-]*|[a-z0-9][a-z0-9._-]*)$/;
const PACKAGE_SHAPED = /-(?:cli|config|core|js|node|package|plugin|react|sdk|test|ts)$/;
/**
 * A settings path this product owns, which the release notes name whenever one is renamed —
 * `hephaestus.mentor.docker-cli` reads as a package to the shape test above, and blocked a release
 * for it. An npm name reaches two dots about as often as a Spring property reaches none, so the
 * dotted depth is what separates them; a declared dependency is still recognised as itself.
 */
const SETTINGS_PATH = /^[a-z0-9-]+(?:\.[a-z0-9-]+){2,}$/;
const FILE_SHAPED = /\.(?:java|js|jsonc?|mdx?|mjs|sh|ts|tsx|xml|ya?ml)$/;
const exists = (repo: Repo, path: string): boolean =>
	repo.present.has(path) || repo.paths.some((present) => present.startsWith(`${path}/`));

const excepts = (exceptions: readonly ClaimException[], document: string, value: string): boolean =>
	exceptions.some((exception) => exception.document === document && exception.value === value);

const dependencyFields = [
	"dependencies",
	"devDependencies",
	"optionalDependencies",
	"peerDependencies",
] as const;

function declaredPackages(repo: Repo): ReadonlySet<string> {
	const packages = new Set<string>();
	for (const file of repo.present.values()) {
		if (file.kind !== "text" || basename(file.path) !== "package.json") continue;
		const json = asRecord(parseJson(file.content), file.path);
		if (typeof json["name"] === "string") packages.add(json["name"]);
		for (const field of dependencyFields) {
			const dependencies = json[field];
			if (dependencies === undefined) continue;
			for (const name of Object.keys(asRecord(dependencies, `${file.path} ${field}`))) {
				packages.add(name);
			}
		}
	}
	return packages;
}

const looksLikePackage = (value: string, packages: ReadonlySet<string>): boolean =>
	PACKAGE_NAME.test(value) &&
	(value.startsWith("@") ||
		packages.has(value) ||
		(PACKAGE_SHAPED.test(value) && !SETTINGS_PATH.test(value)));

function looksLikePath(value: string, roots: ReadonlySet<string>): boolean {
	if (value.startsWith("@") || value.startsWith("~/") || value.includes(":")) return false;
	const first = value.split("/", 1)[0] ?? "";
	return (
		roots.has(first) ||
		(first.startsWith(".") && value.includes("/")) ||
		(value.includes("/") && basename(value).startsWith(".")) ||
		(FILE_SHAPED.test(value) && (value.includes("/") || /\.mdx?$/.test(value)))
	);
}

function pathResolves(repo: Repo, document: string, value: string): boolean {
	const cleaned = normalize(value.replace(/\/$/, ""));
	if (exists(repo, cleaned)) return true;
	if (/^\.\.?\//.test(value) && exists(repo, normalize(join(dirname(document), cleaned))))
		return true;
	if (!value.startsWith(".")) {
		const suffixes = new Set(
			repo.paths.flatMap((path) => {
				const start = path.indexOf(`/${cleaned}`);
				return start === -1 || (path[start + cleaned.length + 1] ?? "/") !== "/"
					? []
					: [path.slice(0, start + cleaned.length + 1)];
			}),
		);
		if (suffixes.size === 1) return true;
	}
	return excepts(INTENTIONALLY_MISSING_PATHS, document, cleaned);
}

function staleContributorClaims(repo: Repo): readonly string[] {
	const roots = new Set(repo.paths.map((path) => path.split("/", 1)[0] ?? path));
	const packages = declaredPackages(repo);
	const failures: string[] = [];
	for (const file of repo.present.values()) {
		if (file.kind !== "text" || !isContributorDoc(file.path)) continue;
		let spans: readonly string[];
		try {
			spans = codeSpans(file.content, file.path.endsWith(".mdx") ? "mdx" : "markdown");
		} catch (error) {
			throw new Error(`${file.path}: ${error instanceof Error ? error.message : String(error)}`, {
				cause: error,
			});
		}
		for (const value of new Set(spans)) {
			const candidate = value.replace(/[.,:;]$/, "").replace(/#.*$/, "");
			if (candidate.includes("…") || candidate.includes("...") || /[*<>{}$\s]/.test(candidate))
				continue;
			if (looksLikePackage(candidate, packages)) {
				if (!packages.has(candidate)) {
					failures.push(
						`${file.path} names npm package \`${candidate}\`, but no package.json declares it.\n` +
							"  Declare the dependency in the workspace that uses it, or remove the stale package name.",
					);
				}
				continue;
			}
			if (looksLikePath(candidate, roots)) {
				if (!pathResolves(repo, file.path, candidate)) {
					failures.push(
						`${file.path} names \`${candidate}\`, which looks like a repository path but resolves to nothing in this checkout.\n` +
							"  Fix the path or remove the stale claim.",
					);
				}
				continue;
			}
		}
	}
	return failures;
}

function survey(snapshot: Snapshot): Repo {
	const byName = new Map<string, string[]>();
	for (const { path } of snapshot.files) {
		byName.set(basename(path), [...(byName.get(basename(path)) ?? []), path]);
	}
	const present = new Map(snapshot.files.map((file) => [file.path, file]));
	const paths = [...present.keys()];
	// A vendored pack under an agent root — `react-best-practices/AGENTS.md` is Vercel's — is a
	// skill's own payload, and Claude Code reads none of those directories anyway, so a `CLAUDE.md`
	// beside one would reach nothing.
	const guides = (byName.get("AGENTS.md") ?? []).filter((path) => !underAgentRoot(path));
	return {
		present,
		paths,
		byName,
		guides,
		commands: snapshot.files
			.map((file) => file.path)
			.filter((path) => path.startsWith(`${COMMANDS_ROOT}/`) && path.endsWith(".md")),
	};
}

/**
 * The content of an agent file, or why there is none to read. Every check narrows on this rather than
 * defaulting to `""`: an unread body read as empty is what turns a symlinked command file into "two
 * separate copies of one command".
 */
type Readable =
	| { readonly kind: "text"; readonly content: string }
	| { readonly kind: "symlink" }
	| { readonly kind: "opaque" }
	| { readonly kind: "absent" };

const readable = (repo: Repo, path: string): Readable =>
	repo.present.get(path) ?? { kind: "absent" };

const COMMENT_START = "<!--";
const COMMENT_END = "-->";

function visibleMarkdown(markdown: string): string {
	const kept: string[] = [];
	let fence: string | undefined;
	let commented = false;
	for (const raw of markdown.replaceAll(/\r\n?/g, "\n").split("\n")) {
		let line = raw;
		const fenceMatch = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
		const marker = fenceMatch?.[1];
		if (fence !== undefined) {
			if (
				marker !== undefined &&
				marker[0] === fence[0] &&
				marker.length >= fence.length &&
				(fenceMatch?.[2] ?? "").trim() === ""
			) {
				fence = undefined;
			}
			continue;
		}
		if (commented) {
			const close = line.indexOf(COMMENT_END);
			if (close === -1) continue;
			line = line.slice(close + COMMENT_END.length);
			commented = false;
		}
		const visibleFence = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
		const visibleMarker = visibleFence?.[1];
		if (
			visibleMarker !== undefined &&
			!(visibleMarker[0] === "`" && (visibleFence?.[2] ?? "").includes("`"))
		) {
			fence = visibleMarker;
			continue;
		}
		// Rescanned from the start each pass: closing one comment can bring a `<!` and a `--` together,
		// which a single sweep would leave behind as an opening delimiter nothing goes on to remove.
		for (let open = line.indexOf(COMMENT_START); open !== -1; open = line.indexOf(COMMENT_START)) {
			const close = line.indexOf(COMMENT_END, open + COMMENT_START.length);
			if (close === -1) {
				line = line.slice(0, open);
				commented = true;
				break;
			}
			line = `${line.slice(0, open)} ${line.slice(close + COMMENT_END.length)}`;
		}
		kept.push(line);
	}
	return kept.join("\n");
}

interface MarkdownNode {
	readonly type: string;
	readonly value?: unknown;
	readonly children?: readonly MarkdownNode[];
}

export function codeSpans(markdown: string, syntax: "markdown" | "mdx"): readonly string[] {
	const spans: string[] = [];
	const visit = (node: MarkdownNode): void => {
		if (node.type === "inlineCode" && typeof node.value === "string") {
			spans.push(node.value.replaceAll(/\r\n?|\n/g, " "));
		}
		for (const child of node.children ?? []) visit(child);
	};
	visit(
		fromMarkdown(
			markdown,
			syntax === "mdx"
				? { extensions: [mdxjs()], mdastExtensions: [mdxFromMarkdown()] }
				: undefined,
		),
	);
	return spans;
}

export function withoutCode(markdown: string): string {
	// Line-bounded pairing prevents malformed wrapping from hiding a later agent import.
	return visibleMarkdown(markdown).replaceAll(/(`+)[^`\n]*?\1/g, " ");
}

/**
 * An extension an instruction file can usefully name. Some filter is required, because a package
 * scope, a Java annotation and a handle are all `@`-prefixed and none is a path. Extension-less
 * targets stay ambiguous — nothing in the shape of `@base-ui/react` distinguishes it from
 * `@docs/contributor/erd` — so they are not read as references.
 */
const LOADABLE = /^[\w.~/-]+\.(?:md|mdx|txt|ts|tsx|json|ya?ml)$/;

/** `@` is excluded from the target, so `@scope/pkg@1.2.3` yields two rejects rather than one accept. */
const REFERENCE = /@([A-Za-z0-9._~/-]+)/g;

export function references(markdown: string): readonly string[] {
	const found = new Set<string>();
	for (const [, target] of withoutCode(markdown).matchAll(REFERENCE)) {
		const cleaned = (target ?? "").replace(/\.+$/, "");
		if (LOADABLE.test(cleaned)) found.add(cleaned);
	}
	return [...found];
}

/**
 * A markdown file as its YAML frontmatter and everything after it, tolerating a BOM and CRLF.
 * `frontmatter` is `undefined` rather than `""` when there is no block, so a caller cannot confuse
 * "the field is absent" with "there is no block to read".
 */
export function parse(markdown: string): { frontmatter?: string; body: string } {
	const text = markdown.replace(/^﻿/, "").replaceAll("\r\n", "\n");
	const end = text.startsWith("---\n") ? text.indexOf("\n---", 4) : -1;
	if (end === -1) return { body: text };
	return { frontmatter: text.slice(4, end), body: text.slice(text.indexOf("\n", end + 1) + 1) };
}

const YAML_TRUE = new Set(["true", "yes", "on"]);
const YAML_FALSE = new Set(["false", "no", "off"]);

/**
 * A scalar YAML flag as every YAML loader reads it, `#` comment stripped. Returns the raw text when
 * the value is neither boolean, so the caller reports it: absorbing an unrecognised spelling into
 * `false` would skip the check it guards and call that a pass.
 */
function yamlBoolean(block: string | undefined, key: string): boolean | string {
	const raw = new RegExp(`^${key}:[ \\t]*(\\S+)`, "m").exec(block ?? "")?.[1];
	if (raw === undefined) return false;
	const value = raw.replace(/#.*$/, "").trim();
	if (YAML_TRUE.has(value.toLowerCase())) return true;
	if (YAML_FALSE.has(value.toLowerCase())) return false;
	return value;
}

/** A file whose body is nothing but references — what a command file and a nested `CLAUDE.md` are. */
const isPointer = (markdown: string): boolean => {
	const { body } = parse(markdown);
	if (references(body).length === 0) return false;
	return withoutCode(body)
		.split("\n")
		.every((line) => line.replaceAll(/@[A-Za-z0-9._~/-]+/g, "").trim() === "");
};

/**
 * A file some check will query and the classifier never read. Nothing downstream can tell that from a
 * file that says nothing, so it is caught once here rather than absorbed by every check that queries it.
 */
const unread = (repo: Repo): readonly string[] =>
	[...repo.present.values()]
		.filter((file) => file.kind === "opaque" && isAgentMarkdown(file.path))
		.map(
			(file) =>
				`${file.path} was not read, so every check that queries it saw an empty file.\n` +
				`  This gate's read scope and isAgentMarkdown disagree — widen the scope in the CLI block.`,
		);

/**
 * A committed symlink resolves for whoever wrote it and for nobody on Windows, where git with
 * `core.symlinks` unset writes a regular file whose content is the target path.
 */
function symlinked(repo: Repo): readonly string[] {
	const inCheckout = (path: string): boolean =>
		repo.present.has(path) || [...repo.present.keys()].some((p) => p.startsWith(`${path}/`));
	const links = [...repo.present.values()].filter(
		(file): file is Extract<TrackedFile, { kind: "symlink" }> =>
			file.kind === "symlink" && isAgentPath(file.path),
	);
	return links.map((file) => {
		const target = join(dirname(file.path), file.target);
		const remedy =
			basename(file.path) === "CLAUDE.md"
				? 'Claude loads the target path as the entire instruction set. Replace it with a regular file containing "@AGENTS.md".'
				: inCheckout(target)
					? `It duplicates ${target}, which both agents already read. Delete the link.`
					: "Replace it with a regular file holding what it points at.";
		return `${file.path} is a committed symlink, which resolves here and not on a Windows checkout.\n  ${remedy}`;
	});
}

/** An `AGENTS.md` the `CLAUDE.md` beside it does not import is read by every agent but Claude Code. */
function unreachable(repo: Repo): readonly string[] {
	const failures: string[] = [];
	for (const guide of repo.guides) {
		const claude = join(dirname(guide), "CLAUDE.md");
		const beside = readable(repo, claude);
		if (beside.kind === "absent") {
			failures.push(
				`${guide} is loaded by every agent except Claude Code, which reads CLAUDE.md and never a nested AGENTS.md.\n` +
					`  Add ${claude} containing the single line "@AGENTS.md". The import resolves against the file holding it.`,
			);
			continue;
		}
		if (beside.kind !== "text") continue; // Symlinked or unread; each has its own check.
		const imported = references(beside.content).map((target) => join(dirname(claude), target));
		if (!imported.includes(guide)) {
			failures.push(
				`${claude} exists but does not import ${guide}, so the guidance beside it reaches nothing.\n` +
					`  Add the line "@${basename(guide)}".`,
			);
		}
	}
	return failures;
}

/** An `@` reference resolving nowhere: Claude drops the import, opencode inlines nothing, silently. */
function dangling(repo: Repo): readonly string[] {
	// Claude resolves an import against the file holding it; opencode runs a command from the root.
	// `AGENTS.md` is in scope because both tools load it whole; a `SKILL.md` is not, because neither
	// expands an import inside one — its `@` lines are prose the model reads and acts on itself.
	const sources = [
		...(repo.byName.get("CLAUDE.md") ?? []).map((claude) => [claude, dirname(claude)] as const),
		...repo.guides.map((guide) => [guide, dirname(guide)] as const),
		...repo.commands.map((command) => [command, "."] as const),
	].flatMap(([path, base]) => {
		const file = readable(repo, path);
		return file.kind === "text" ? [[path, base, file.content] as const] : [];
	});
	return sources.flatMap(([path, base, content]) =>
		references(content)
			.filter((target) => !target.startsWith("~/")) // A personal file, outside every checkout.
			.map((target) => [target, join(base, target)] as const)
			.filter(([, resolved]) => !repo.present.has(resolved))
			.map(
				([target, resolved]) =>
					`${path} references @${target}, which resolves to ${resolved} — not a file in this checkout.\n` +
					`  The reference is dropped at load time, so the content it stands in for is simply absent.`,
			),
	);
}

/** opencode loads only what `instructions` names, and an entry naming nothing reports nothing. */
function uncovered(repo: Repo): readonly string[] {
	const config = readable(repo, OPENCODE_CONFIG);
	if (config.kind !== "text") {
		return [
			`${OPENCODE_CONFIG} is missing or unreadable, so opencode loads no AGENTS.md at all.\n` +
				`  Restore it with an "instructions" array naming every one.`,
		];
	}
	let patterns: readonly string[];
	try {
		const parsed = asRecord(parseJson(config.content), OPENCODE_CONFIG);
		patterns = asStringArray(parsed["instructions"] ?? [], `${OPENCODE_CONFIG} instructions`)
			// A remote rule set is not ours to resolve.
			.filter((pattern) => !pattern.startsWith("http"));
	} catch (error) {
		// Reported rather than thrown: every gate in `vp run check` names its own defect, and a
		// stack trace out of a config read would say the same thing less clearly.
		return [
			`${OPENCODE_CONFIG} could not be read as opencode's config: ${error instanceof Error ? error.message : String(error)}\n` +
				`  opencode starts with no instructions when this file is malformed.`,
		];
	}
	return [
		...patterns
			.filter((pattern) => ![...repo.present.keys()].some((path) => matchesGlob(path, pattern)))
			.map(
				(pattern) =>
					`${OPENCODE_CONFIG} lists "${pattern}" under instructions, and no file matches it.\n` +
					`  The entry loads nothing. Fix the path or drop it.`,
			),
		...repo.guides
			.filter((guide) => !patterns.some((pattern) => matchesGlob(guide, pattern)))
			.map(
				(guide) =>
					`${guide} is matched by no ${OPENCODE_CONFIG} instructions entry, so opencode never loads it.\n` +
					`  Give it its own entry.`,
			),
	];
}

/**
 * `disable-model-invocation` leaves a typed `/name` as the only way into a skill in Claude Code, and
 * opencode resolves that from `.opencode/commands/` alone. The name is the skill's directory: in a
 * project skill the frontmatter `name` is a display label only
 * (https://code.claude.com/docs/en/skills).
 */
function uncommanded(repo: Repo): readonly string[] {
	const failures: string[] = [];
	for (const path of repo.byName.get("SKILL.md") ?? []) {
		if (dirname(dirname(path)) !== SKILLS_ROOT) continue; // Not a skill root; it has no command name.
		const skill = readable(repo, path);
		if (skill.kind !== "text") continue;
		const flag = yamlBoolean(parse(skill.content).frontmatter, "disable-model-invocation");
		if (typeof flag === "string") {
			failures.push(
				`${path} sets disable-model-invocation to "${flag}", which is not a YAML boolean.\n` +
					`  Every loader reads it differently, so write true or false.`,
			);
			continue;
		}
		if (!flag) continue;
		const name = basename(dirname(path));
		const command = `${COMMANDS_ROOT}/${name}.md`;
		if (!repo.present.has(command)) {
			failures.push(
				`${path} sets disable-model-invocation, so nothing but a typed /${name} reaches it — and opencode resolves a slash command from ${COMMANDS_ROOT}/ only.\n` +
					`  Add ${command} referencing @${path}. Do not copy the body: opencode inlines a referenced file into the prompt.`,
			);
			continue;
		}
		const pointer = readable(repo, command);
		if (pointer.kind === "text" && !references(pointer.content).includes(path)) {
			failures.push(
				`${command} does not reference @${path}, so the two are separate copies of one command.\n` +
					`  Replace its body with the reference.`,
			);
		}
	}
	return failures;
}

/** A Codex copy of a skill, against the Claude Code original it has to stay identical to. */
function unmirrored(repo: Repo, expected: readonly string[]): readonly string[] {
	const failures: string[] = [];
	for (const skill of expected) {
		for (const root of [SKILLS_ROOT, CODEX_SKILLS_ROOT]) {
			const path = `${root}/${skill}/SKILL.md`;
			if (repo.present.has(path)) continue;
			failures.push(
				`${path} is missing, and ${skill} is listed as a skill Codex has.\n` +
					`  Codex reads ${CODEX_SKILLS_ROOT}/ and Claude Code reads ${SKILLS_ROOT}/, so the skill lives in both.\n` +
					`  Restore it, or drop "${skill}" from CODEX_SKILLS if it is going away.`,
			);
		}
	}
	for (const file of repo.present.values()) {
		const original = mirrorOf(file.path);
		if (original === undefined || file.kind !== "text") continue;
		const source = readable(repo, original);
		if (source.kind === "absent") {
			// A listed skill's missing half is reported by the loop above, in the terms that name why
			// the pair exists at all.
			if (expected.includes(basename(dirname(file.path)))) continue;
			failures.push(
				`${file.path} has no counterpart at ${original}, so Codex reads a skill Claude Code does not have.\n` +
					`  Restore ${original}, or delete the Codex copy if the skill is going away.`,
			);
		} else if (source.kind === "text" && source.content !== file.content) {
			failures.push(
				`${file.path} has drifted from ${original}. Codex and Claude Code read different instructions under one name.\n` +
					`  Copy ${original} over it. Neither tool reads the other's directory, which is why this pair exists.`,
			);
		}
	}
	return failures;
}

/**
 * Two files with one body. Compared exactly, so a copy is caught when it is made and never after —
 * the only moment deleting it is free.
 *
 * Exempt on shape rather than on presence: a file is a pointer when nothing survives removing its
 * frontmatter and its reference lines. Exempting anything that merely *mentions* a reference would
 * excuse every real copy, since a copied skill carries the original's references with it.
 */
function duplicated(repo: Repo): readonly string[] {
	const bodies = new Map<string, string[]>();
	for (const file of repo.present.values()) {
		if (file.kind !== "text" || !isAgentMarkdown(file.path)) continue;
		if (mirrorOf(file.path) !== undefined) continue; // A Codex mirror, which `unmirrored` owns.
		const body = file.content.trim();
		if (body === "" || isPointer(file.content)) continue;
		bodies.set(body, [...(bodies.get(body) ?? []), file.path]);
	}
	return [...bodies.values()]
		.filter((copies) => copies.length > 1)
		.map(
			(copies) =>
				`${copies.toSorted().join(" and ")} hold the same content, and nothing reports the drift once they have any.\n` +
				`  Keep one and point the others at it: a CLAUDE.md import and an opencode command both inline a referenced file.`,
		);
}

/**
 * `codexSkills` is the repo's intent rather than the algorithm's, so it is supplied rather than
 * assumed: the CLI passes `CODEX_SKILLS`, and a caller reasoning about a snapshot alone passes none.
 */
export const analyse = (
	snapshot: Snapshot,
	codexSkills: readonly string[] = [],
): readonly string[] => {
	const repo = survey(snapshot);
	return [
		...unread(repo),
		...symlinked(repo),
		...unreachable(repo),
		...dangling(repo),
		...uncovered(repo),
		...uncommanded(repo),
		...unmirrored(repo, codexSkills),
		...duplicated(repo),
		...staleContributorClaims(repo),
	];
};

export async function scan(root: string = REPO_ROOT): Promise<Snapshot> {
	const { stdout } = await promisify(execFile)(
		"git",
		["ls-files", "-z", "--cached", "--others", "--exclude-standard"],
		{ cwd: root, env: environmentWithoutGitRepository(), maxBuffer: 64 * 1024 * 1024 },
	);
	// Sorted, so the failures print in the same sequence locally and in CI.
	const listed = [...new Set(stdout.split("\0").filter((path) => path !== ""))].toSorted();
	const files: TrackedFile[] = [];
	for (const path of listed) {
		// A path git still tracks but the working tree no longer has is a deletion in progress.
		const stats = await lstat(resolve(root, path)).catch(() => undefined);
		if (stats === undefined) continue;
		if (stats.isSymbolicLink()) {
			files.push({ path, kind: "symlink", target: await readlink(resolve(root, path)) });
		} else if (
			isAgentMarkdown(path) ||
			isContributorDoc(path) ||
			basename(path) === "package.json" ||
			path === OPENCODE_CONFIG
		) {
			files.push({ path, kind: "text", content: await readFile(resolve(root, path), "utf8") });
		} else files.push({ path, kind: "opaque" });
	}
	return { files };
}

if (process.argv[1] === import.meta.filename) {
	const snapshot = await scan();
	const repo = survey(snapshot);
	const skills = (repo.byName.get("SKILL.md") ?? []).filter(
		(path) => dirname(dirname(path)) === SKILLS_ROOT,
	);
	const empty = [
		repo.guides.length === 0 ? "no AGENTS.md" : undefined,
		skills.length === 0 ? `no skill under ${SKILLS_ROOT}/` : undefined,
	].filter((reason) => reason !== undefined);
	if (empty.length > 0) {
		console.error(
			`check-agent-instructions: ${empty.join(" and ")} — this check would pass without checking.`,
		);
		process.exit(1);
	}

	const failures = analyse(snapshot, CODEX_SKILLS);
	if (failures.length > 0) {
		for (const failure of failures) console.error(`${failure}\n`);
		process.exit(1);
	}
	const mirrored = [...repo.present.keys()].filter(
		(path) => basename(path) === "SKILL.md" && mirrorOf(path) !== undefined,
	).length;
	console.log(
		`check-agent-instructions: ${repo.guides.length} AGENTS.md reach Claude Code and opencode; ` +
			`${skills.length} skills, ${mirrored} of them mirrored for Codex, ` +
			`${repo.commands.length} opencode commands; contributor-doc references checked.`,
	);
}
