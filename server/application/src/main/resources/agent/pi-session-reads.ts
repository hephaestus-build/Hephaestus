import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";

import { bashRead, bashReadRanges } from "./pi-bash-reads.ts";

import type { CitableText } from "./pi-folder-index.ts";
import type { BriefSource } from "./pi-review-brief.ts";
import { TREE_SOURCE } from "./work/practice-scripts/kits/scm/sources.ts";
import {
	CANDIDATE_LIMITS,
	type Coverage,
	type Omission,
	type ReadReceipt,
	searchedPattern,
} from "./work/practice-scripts/lib/core/contract.ts";
import {
	type EvidenceReader,
	type Receipts,
	noReceipts,
} from "./work/practice-scripts/lib/core/evidence-reader.ts";
import { isJsonObject as isRecord } from "./work/practice-scripts/lib/core/json.ts";
import { ReadRanges } from "./work/practice-scripts/lib/core/read-ranges.ts";

import { checkoutMatches, type SearchClaim, type SearchProof } from "./pi-search-worker.ts";

const READ_TOOLS = new Set(["read", "grep", "find", "ls", "bash"]);

/**
 * The pinned checkout a session reads on demand: where it is, its witness file, the commit it holds, and whether the
 * capture holds all of it. A capture that left files out, such as a submodule, holds only part of it.
 */
export interface SessionTree {
	root: string;
	sourcePath: string;
	version: string;
	complete: boolean;
}
// Bounds each refusal: sources named, and ranges named per source.
export const REPAIR_LIMIT = 5;
// Bounds the search receipts, so they leave room under the receipt bound for the files a session read.
const SEARCHED_PATTERNS = 20;

/** A relative path that stays inside the root it is read from. */
export function insideRoot(target: string): boolean {
	const normal = path.normalize(target);
	return normal !== "." && !path.isAbsolute(normal) && normal.split(path.sep)[0] !== "..";
}
const textOf = (result: unknown): string =>
	isRecord(result) && Array.isArray(result.content)
		? result.content
				.flatMap((part: unknown) =>
					isRecord(part) && part.type === "text" && typeof part.text === "string"
						? [part.text]
						: [],
				)
				.join("\n")
		: "";

/** Host observations retain all reads; coverage checks only required and cited source content. */
export class SessionReads {
	reads = 0;
	readonly ranges = new ReadRanges();
	readonly cutSearches: string[] = [];
	readonly failed: string[] = [];
	readonly unknown: string[] = [];
	readonly #required = new Set<string>();
	readonly #supplied = new Set<string>();
	readonly pending = new Map<string, unknown>();
	readonly #observedContent = new Set<string>();
	readonly #opened = new Set<string>();
	readonly #coveredBy = new Map<string, Set<string>>();
	/** First line the read tool could not return because it alone exceeds the output byte limit. */
	readonly #oversized = new Map<string, number>();
	readonly #root: string;
	readonly #lineCount: (target: string) => number;
	readonly #tree: SessionTree | undefined;
	/** What each grep without a file filter that searched the whole checkout to the end looked for. */
	readonly #searches = new Map<string, SearchClaim>();
	/**
	 * The searches that the loop proved, as a receipt states them, with the digest of the checkout each searched and
	 * its matches: only these are receipts.
	 */
	readonly #provedPatterns = new Map<string, { contentDigest: string; matches: number }>();

	constructor(
		root: string,
		{
			required = [],
			supplied = [],
		}: {
			required?: readonly string[];
			supplied?: readonly BriefSource[];
		} = {},
		lineCount = (target: string) =>
			readFileSync(path.resolve(root, target), "utf8").split("\n").length,
		texts: readonly CitableText[] = [],
		tree?: SessionTree,
	) {
		this.#root = root;
		this.#lineCount = lineCount;
		this.#tree = tree;
		const byContent = new Map<string, Set<string>>();
		const byPath = new Map<string, Set<string>>();
		for (const text of texts) {
			for (const [index, key] of [
				[byContent, `${text.source}:${text.sha256}`],
				[byPath, `${text.source}:${text.path}`],
			] as const) {
				const paths = index.get(key) ?? new Set<string>();
				paths.add(this.#key(text.stagedPath));
				index.set(key, paths);
			}
		}
		for (const text of texts) {
			const key = this.#key(text.stagedPath);
			const copies = this.#coveredBy.get(key) ?? new Set<string>();
			for (const copy of byContent.get(`${text.source}:${text.sha256}`) ?? []) {
				copies.add(copy);
			}
			// Extracted strings add citation coordinates, not new source content.
			const separator = text.path.indexOf("#/");
			if (separator !== -1 && text.stagedPath.startsWith("context/citable-records/")) {
				const parent = text.path.slice(0, separator);
				copies.add(this.#key(parent));
				for (const record of byPath.get(`${text.source}:${parent}`) ?? []) {
					copies.add(record);
				}
			}
			this.#coveredBy.set(key, copies);
		}
		for (const target of required) {
			const key = this.#key(target);
			this.#required.add(key);
			this.ranges.expect(key);
		}
		for (const source of supplied) {
			if (source.supplied && source.lines !== undefined) {
				const key = this.#key(source.path);
				this.#supplied.add(key);
				this.ranges.add(key, 1, source.lines, source.lines);
				this.#observedContent.add(key);
			}
		}
	}

	#key(target: string): string {
		return path.relative(this.#root, path.resolve(this.#root, target));
	}

	note(event: AgentSessionEvent): void {
		if (event.type === "tool_execution_start" && READ_TOOLS.has(event.toolName)) {
			this.pending.set(event.toolCallId, event.args);
			return;
		}
		if (event.type !== "tool_execution_end" || !this.pending.has(event.toolCallId)) {
			return;
		}
		const args = this.pending.get(event.toolCallId);
		this.pending.delete(event.toolCallId);
		this.reads += 1;
		const target =
			isRecord(args) && typeof args.path === "string" ? this.#key(args.path) : undefined;
		const named = target ?? event.toolName;
		const text = textOf(event.result);
		if (event.toolName === "bash") {
			this.#bash(args, event.result, event.isError);
			return;
		}
		if (event.isError) {
			this.failed.push(named);
			return;
		}
		const result: unknown = event.result;
		const details = isRecord(result) && isRecord(result.details) ? result.details : {};
		const truncation = isRecord(details.truncation) ? details.truncation : {};
		const cut =
			truncation.truncated === true ||
			details.matchLimitReached !== undefined ||
			details.resultLimitReached !== undefined ||
			details.entryLimitReached !== undefined;
		if (event.toolName === "read" && target !== undefined) {
			const start = isRecord(args) && typeof args.offset === "number" ? args.offset : 1;
			this.ranges.expect(target);
			this.#opened.add(target);
			if (truncation.firstLineExceedsLimit === true && !this.#oversized.has(target)) {
				this.#oversized.set(target, start);
			}
			// Count the pinned source on the host. Source text can imitate the tool's continuation notices.
			try {
				const last = this.#lineCount(target);
				const limit = isRecord(args) && typeof args.limit === "number" ? args.limit : last;
				let shown = Math.min(limit, last - start + 1);
				if (cut) {
					shown = typeof truncation.outputLines === "number" ? truncation.outputLines : 0;
				}
				if (truncation.firstLineExceedsLimit !== true && !/Read image file/u.test(text)) {
					this.ranges.add(target, start, start + shown - 1, last);
					if (shown > 0) {
						this.#observedContent.add(target);
					}
				}
			} catch {
				this.unknown.push(target);
			}
			return;
		}
		if (cut) {
			this.cutSearches.push(named);
			return;
		}
		if (event.toolName === "grep") {
			this.#searched(args, target, text);
		}
	}

	/**
	 * Records the pattern of a grep that ran to the end over the whole checkout. A file filter or a narrower path
	 * searched part of the checkout, and a file listing searched no contents.
	 */
	#searched(args: unknown, target: string | undefined, output: string): void {
		if (
			isRecord(args) &&
			typeof args.pattern === "string" &&
			args.pattern !== "" &&
			searchedPattern({
				pattern: args.pattern,
				literal: args.literal === true,
				ignoreCase: args.ignoreCase === true,
			}).length <= CANDIDATE_LIMITS.warrantFieldChars &&
			this.#searches.size < SEARCHED_PATTERNS &&
			!(typeof args.glob === "string" && args.glob !== "") &&
			this.#containsTree(target ?? "")
		) {
			const search = {
				pattern: args.pattern,
				literal: args.literal === true,
				ignoreCase: args.ignoreCase === true,
				found: this.#reported(output, target ?? ""),
			};
			// The same word with other flags is another search: a model that was asked to ignore case repeats its word.
			this.#searches.set(searchedPattern(search), search);
		}
	}

	/** The matches that a grep over `searched` reported, as `path:line` from the checkout's root. */
	#reported(output: string, searched: string): string[] {
		const root = this.#tree === undefined ? "" : this.#key(this.#tree.root);
		return output.split("\n").flatMap((line) => {
			const match = /^(?<file>.+?):(?<number>\d+): /u.exec(line);
			const { file, number } = match?.groups ?? {};
			return file === undefined || number === undefined
				? []
				: [`${path.relative(root, path.join(searched, file))}:${number}`];
		});
	}

	/**
	 * Proves the searches that this session ran, before a result relies on them. The tools side ran them, so a search
	 * counts only when the loop's own search of the same files found nothing that it left out.
	 */
	async proveSearches(
		prove: (root: string, claims: readonly SearchClaim[]) => Promise<SearchProof>,
	): Promise<void> {
		const tree = this.#tree;
		const open = [...this.#searches.values()].filter(
			(claim) => !this.#provedPatterns.has(searchedPattern(claim)),
		);
		if (tree === undefined || !tree.complete || open.length === 0) {
			return;
		}
		const { proved, checkoutDigest } = await prove(path.resolve(this.#root, tree.root), open);
		for (const [index, claim] of open.entries()) {
			if (proved[index] === true && checkoutDigest !== null) {
				this.#provedPatterns.set(searchedPattern(claim), {
					contentDigest: checkoutDigest,
					matches: checkoutMatches(claim.found).length,
				});
			}
			this.#searches.delete(searchedPattern(claim));
		}
	}

	/** Whether a search over `key` covers the whole checkout. */
	#containsTree(key: string): boolean {
		if (this.#tree === undefined) {
			return false;
		}
		const above = path.relative(key, this.#key(this.#tree.root));
		return (
			key === "" || above === "" || (!path.isAbsolute(above) && above.split(path.sep)[0] !== "..")
		);
	}

	/**
	 * The checkout's own proof of what this session read: each search of the whole checkout's contents that ran to
	 * the end, with its pattern and the digest of the checkout that it searched, and then each file read to its last
	 * line, with the digest of the pinned bytes. A search that was cut, failed, filtered by file, narrower than the
	 * checkout or run through the shell proves no such search, and so does one that the loop could not prove and any
	 * search of a checkout captured only in part. The searches come first, so the receipt bound never drops them. The
	 * `.git` folder holds no work.
	 */
	treeReceipts(): ReadReceipt[] {
		const tree = this.#tree;
		if (tree === undefined) {
			return [];
		}
		const root = this.#key(tree.root);
		const searches = tree.complete ? [...this.#provedPatterns] : [];
		const receipts: ReadReceipt[] = searches.map(([pattern, { contentDigest, matches }]) => ({
			sourceKind: TREE_SOURCE,
			sourcePath: tree.sourcePath,
			path: ".",
			version: tree.version,
			status: "SEARCHED",
			contentDigest,
			pattern,
			matches,
		}));
		for (const key of this.#opened) {
			const inside = path.relative(root, key).split(path.sep);
			if (
				inside[0] === "" ||
				inside[0] === ".." ||
				inside[0] === ".git" ||
				!this.ranges.complete(key)
			) {
				continue;
			}
			try {
				const bytes = readFileSync(path.resolve(this.#root, key));
				receipts.push({
					sourceKind: TREE_SOURCE,
					sourcePath: tree.sourcePath,
					path: inside.join("/"),
					version: tree.version,
					status: "COMPLETE",
					contentDigest: createHash("sha256").update(bytes).digest("hex"),
					startLine: 1,
					endLine: this.#lineCount(key),
				});
			} catch {
				// A file that cannot be read again proves no read.
			}
		}
		return receipts.slice(0, CANDIDATE_LIMITS.receipts);
	}

	#bash(args: unknown, result: unknown, isError: boolean): void {
		const command = isRecord(args) && typeof args.command === "string" ? args.command : "";
		const read = bashRead(command);
		const details = isRecord(result) && isRecord(result.details) ? result.details : {};
		const truncation = isRecord(details.truncation) ? details.truncation : {};
		const structured =
			isRecord(result) && isRecord(result.structuredContent) ? result.structuredContent : {};
		const cut = truncation.truncated === true || structured.truncated === true;
		const noMatches = read?.kind === "search" && structured.exit_code === 1;
		const target = read === undefined ? undefined : this.#key(read.path);
		if (target !== undefined) {
			this.ranges.expect(target);
			if (!isError || noMatches) {
				this.#opened.add(target);
			}
		}
		if (isError && !noMatches) {
			this.failed.push(target ?? "bash");
		}
		if (read !== undefined && target !== undefined && !cut && (!isError || noMatches)) {
			try {
				const output = noMatches ? "" : textOf(result);
				const { last, ranges } = bashReadRanges(this.#root, read, output);
				for (const range of ranges) {
					this.ranges.add(target, range.start, range.end, last);
					if (range.end >= range.start) {
						this.#observedContent.add(target);
					}
				}
				return;
			} catch {
				// A receipt cannot rely on output that differs from the pinned source.
			}
		}
		this.unknown.push(target ?? `bash: ${command}`);
	}

	/** Content supplied in this session's opening or returned by its native reader. */
	hasRead(target: string): boolean {
		return this.#observedContent.has(this.#key(target));
	}

	#complete(key: string, visited = new Set<string>()): boolean {
		if (this.#supplied.has(key) || this.ranges.complete(key)) {
			return true;
		}
		if (visited.has(key)) {
			return false;
		}
		visited.add(key);
		return [...(this.#coveredBy.get(key) ?? [])].some((copy) => this.#complete(copy, visited));
	}

	/** Complete source content, including equivalent captured copies supplied in the brief. */
	hasCompleteRead(target: string): boolean {
		return this.#complete(this.#key(target));
	}

	/** Unread file lines of a source, or undefined when the host cannot count them. */
	remaining(target: string): { start: number; end: number }[] | undefined {
		const key = this.#key(target);
		if (this.#complete(key)) {
			return [];
		}
		const gaps = this.ranges.gaps(key);
		if (gaps !== undefined) {
			return gaps;
		}
		try {
			return [{ start: 1, end: this.#lineCount(key) }];
		} catch {
			return undefined;
		}
	}

	/** Executable reads for the unread lines of sources inside the session root. */
	repair(targets: readonly string[]): string {
		const keys = [...new Set(targets.map((target) => this.#key(target)))].filter(
			(key) => key !== "" && insideRoot(key) && this.remaining(key)?.length !== 0,
		);
		const steps = keys.slice(0, REPAIR_LIMIT).map((key) => {
			const oversized = this.#oversized.get(key);
			if (oversized !== undefined) {
				return `file line ${oversized} of ${key} alone exceeds the read tool's output limit, so no read can cover ${key} and no MET result that needs it can be admitted`;
			}
			const gaps = this.remaining(key);
			if (gaps === undefined) {
				return `read(${JSON.stringify({ path: key })})`;
			}
			const more = gaps.length - REPAIR_LIMIT;
			return (
				gaps
					.slice(0, REPAIR_LIMIT)
					.map(
						({ start, end }) =>
							`read(${JSON.stringify({ path: key, offset: start, limit: end - start + 1 })})`,
					)
					.join("; ") + (more > 0 ? `; then ${more} more unread ranges of ${key}` : "")
			);
		});
		if (steps.length === 0) {
			return "";
		}
		const more = keys.length - REPAIR_LIMIT;
		return (
			`Read the unread file lines: ${steps.join("; ")}${more > 0 ? `; then ${more} more sources` : ""}. ` +
			`Offsets and limits count physical lines of the file, not the [L<n>] markers inside diff content. ` +
			`When a read ends with "Use offset=N to continue", read again from N to the end of the same range.${this.bashNote(
				keys,
			)}`
		);
	}

	/** Explains uncounted shell reads, only when a command named one of these sources. */
	bashNote(targets: readonly string[]): string {
		const names = targets.map((target) => path.basename(target));
		return this.unknown.some(
			(entry) => entry.startsWith("bash: ") && names.some((name) => entry.includes(name)),
		)
			? " Bash output from pipes, globs or compound commands is not counted as reading; use read, or one literal `sed -n 'a,bp' <file>` per range."
			: "";
	}

	get #incomplete(): string[] {
		return this.ranges.incomplete.filter((key) => !this.#complete(key));
	}

	get receipts(): Receipts {
		return {
			reads: this.reads,
			truncatedReads:
				this.#incomplete.filter((file) => this.#opened.has(file)).length + this.cutSearches.length,
			failedReads: this.failed.length,
		};
	}

	coverage(
		reader?: EvidenceReader,
		domainReads: Receipts = noReceipts(),
		citedPaths: readonly string[] = [],
	): Extract<Coverage, { source: "host" }> {
		const required = new Set([...this.#required, ...citedPaths.map((file) => this.#key(file))]);
		const incomplete = [...required].filter((file) => !this.#complete(file));
		const failed = incomplete.filter((file) => this.failed.includes(file));
		const truncated = incomplete.filter((file) => !failed.includes(file) && this.#opened.has(file));
		const unavailable = incomplete.filter(
			(file) => !failed.includes(file) && !this.#opened.has(file),
		);
		const omitted: Omission[] = [];
		const omit = (what: string, items: string[], reason: Omission["reason"]) => {
			if (items.length > 0) {
				omitted.push({
					what,
					count: items.length,
					reason,
					items: items
						.slice(0, CANDIDATE_LIMITS.omissionItems)
						.map((item) => item.slice(0, CANDIDATE_LIMITS.warrantFieldChars)),
				});
			}
		};
		omit("required or cited sources with unread ranges", truncated, "truncated");
		omit("relevant sources not supplied or read", unavailable, "unavailable");
		omit("reads that failed", failed, "unavailable");
		if (required.size === 0 && this.#observedContent.size === 0 && domainReads.reads === 0) {
			omitted.push({
				what: "no relevant source was supplied or read",
				count: 1,
				reason: "unavailable",
			});
		}
		if (domainReads.truncatedReads > 0) {
			omitted.push({
				what: "governed file reads cut short",
				count: domainReads.truncatedReads,
				reason: "truncated",
			});
		}
		if (domainReads.failedReads > 0) {
			omitted.push({
				what: "governed file reads that failed",
				count: domainReads.failedReads,
				reason: "unavailable",
			});
		}
		const receipts = {
			source: "host" as const,
			reads: this.reads + domainReads.reads,
			truncatedReads: truncated.length + domainReads.truncatedReads,
			failedReads: failed.length + domainReads.failedReads,
			receipts: reader?.readReceipts ?? this.treeReceipts(),
		};
		const [first, ...rest] = omitted;
		return first === undefined
			? { ...receipts, complete: true }
			: { ...receipts, complete: false, omitted: [first, ...rest] };
	}
}

/**
 * Words that say the repository, beyond the cited lines, lacks something. `AbsenceClaims.java` holds the same markers
 * and admission withholds such a result without a search of the whole checkout, so the session is asked first.
 * A clause that states what the review did not establish is a limit of the review, not a claim. Clauses end at a full
 * stop, a semicolon, a colon, a dash, a parenthesis or a comma before a conjunction, so such a limit cannot hide the
 * claim beside it.
 */
export const ABSENCE_MARKERS = [
	String.raw`(?:\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|absent)\b[^.]{0,80}\b(?:in|across|throughout|under)|\b(?:missing|absent) from) (?:the |this |our )?(?:whole |entire |reviewed |pinned |checked-out |rest of the )?(?:repository|repo|codebase|code base|checkout|project|application|source tree|src)\b(?!\.\w|'s\b|[ -](?:code|manifest|inventory|policy|board|tracker|implementation|service|config|method|call|class|interface|layer|bean|query|lookup|result|field|object|instance|step|action|settings?)s?\b)`,
	String.raw`\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|absent)\b[^.]{0,80}\b(?:anywhere|elsewhere) (?:in|across|throughout|under) (?:the )?(?:whole |entire |reviewed |pinned |checked-out |rest of the )?(?:code|app|source|sources)\b(?![ -](?:file|files|change|diff)\b)`,
	String.raw`\bno other files?\b(?! (?:in|of) (?:the|this) (?:change|diff|commit|pull request)\b)[^.;]{0,30}\b(?:references?|imports?|calls?|uses?|instantiates?|consumes?|wires?|depends? on|handles?|catches?|validates?)\b|\b(?:referenced|imported|called|used|instantiated|consumed|handled) (?:by|from|in) no other files?\b|\breferences from other files\b`,
	String.raw`(?<!from )\b(?:the|this) (?:whole |entire |reviewed |pinned )?(?:repository|repo|codebase|code base|checkout)\b(?! (?:method|call|class|interface|layer|bean|query|lookup|result|field|object|instance|step|action|policy|settings?)s?\b)[^.]{0,40}\b(?:contains no|has no|holds no|defines no|declares no|registers no|lacks?|never|does not|doesn't)\b|\b(?:the|this) (?:project|application)\b(?![ -](?:code|manifest|inventory|policy|board|tracker)\b)[^.]{0,40}\b(?:defines no|declares no|registers no|never)\b`,
	String.raw`\b(?:nothing else|nothing in the (?:project|application|code ?base|repository|repo|checkout)|no (?:other )?(?:code|callers?)(?! path| (?:in|of)\b)|none of (?:the|its) callers)\b[^.]{0,60}\b(?:catch(?:es)?|handles?)\b(?!\()`,
];
export const ABSENCE_DISCLAIMER = String.raw`\b(?:did|does|do|could|can|would)(?: not|n't) (?:\w+ ){0,1}?(?:establish|inspect|examine|search|judge)\w*\b|\bnot (?:established|inspected|examined|searched|judged)\b|\bwithout (?:establishing|inspecting|examining|searching|judging)\b|\bnot that\b|\b(?:was|were) not (?:examined|inspected|judged)\b|\bno (?:[\w-]+ ){0,8}?(?:was|were) (?:examined|inspected|judged)\b`;
export const ABSENCE_CLAUSE_END = String.raw`(?<=[.;:])[ \t\r\n\u00A0]+|,[ \t\r\n\u00A0]+(?=(?:but|and|so|yet|while|though|although)[ \t\r\n\u00A0])|[ \t\r\n\u00A0]+[—–-][ \t\r\n\u00A0]+|[ \t\r\n\u00A0]*[()][ \t\r\n\u00A0]*`;
/** What `AbsenceClaims.java` reads as a plain search and as words that name no subject. */
export const ABSENCE_PLAIN_SEARCH = String.raw`(?:\(\?i\))?(?:\\b)?[\p{L}\p{N}_-]+(?:\\b)?(?:\|(?:\\b)?[\p{L}\p{N}_-]+(?:\\b)?)*`;
export const ABSENCE_GENERIC_WORDS = [
	"a",
	"across",
	"an",
	"and",
	"any",
	"anywhere",
	"app",
	"application",
	"are",
	"as",
	"at",
	"base",
	"be",
	"by",
	"call",
	"caller",
	"catch",
	"check",
	"checked",
	"checkout",
	"class",
	"code",
	"codebase",
	"commit",
	"consume",
	"contain",
	"declare",
	"define",
	"depend",
	"diff",
	"do",
	"does",
	"doesn",
	"else",
	"elsewhere",
	"entire",
	"exist",
	"file",
	"find",
	"for",
	"found",
	"from",
	"function",
	"handle",
	"has",
	"hasn",
	"have",
	"helper",
	"hold",
	"import",
	"in",
	"instantiate",
	"into",
	"is",
	"isn",
	"it",
	"its",
	"lack",
	"logic",
	"mechanism",
	"method",
	"missing",
	"never",
	"no",
	"none",
	"not",
	"nothing",
	"nowhere",
	"of",
	"on",
	"one",
	"only",
	"or",
	"other",
	"out",
	"path",
	"pinned",
	"project",
	"pull",
	"reference",
	"register",
	"repo",
	"repository",
	"request",
	"rest",
	"reviewed",
	"search",
	"source",
	"sources",
	"src",
	"such",
	"that",
	"the",
	"there",
	"thing",
	"this",
	"throughout",
	"to",
	"tree",
	"under",
	"use",
	"used",
	"using",
	"validate",
	"value",
	"was",
	"were",
	"whole",
	"wire",
	"with",
	"without",
];
const ABSENCE_PATTERNS = ABSENCE_MARKERS.map((marker) => new RegExp(marker, "iu"));
const DISCLAIMER = new RegExp(ABSENCE_DISCLAIMER, "iu");
const CLAUSE_END = new RegExp(ABSENCE_CLAUSE_END, "u");

const PLAIN_SEARCH = new RegExp(`^(?:${ABSENCE_PLAIN_SEARCH})$`, "u");
const WORD_BREAK = /(?<=\p{Ll})(?=\p{Lu})|[^\p{L}\p{N}]+/u;
const TOKEN_BREAK = /[^\p{L}\p{N}_-]+/u;

/** The same crude stem as `AbsenceClaims.java`: a miss costs one more search. */
function stem(word: string): string {
	let stemmed = word.toLowerCase();
	if (stemmed.length > 4 && stemmed.endsWith("ies")) {
		stemmed = `${stemmed.slice(0, -3)}y`;
	} else if (stemmed.length > 5 && stemmed.endsWith("ing")) {
		stemmed = stemmed.slice(0, -3);
	} else if (stemmed.length > 4 && (stemmed.endsWith("ed") || stemmed.endsWith("es"))) {
		stemmed = stemmed.slice(0, -2);
	} else if (stemmed.length > 3 && stemmed.endsWith("s")) {
		stemmed = stemmed.slice(0, -1);
	}
	return stemmed.length > 3 && stemmed.endsWith("e") ? stemmed.slice(0, -1) : stemmed;
}

const GENERIC = new Set(ABSENCE_GENERIC_WORDS.map(stem));
const wordsOf = (text: string) =>
	text
		.split(WORD_BREAK)
		.filter(Boolean)
		.map((word) => word.toLowerCase());
const content = (text: string) =>
	[...new Set(wordsOf(text).map(stem))].filter((word) => word.length >= 3 && !GENERIC.has(word));

interface SearchTerm {
	word: string;
	ignoresCase: boolean;
	bounded: boolean;
}

function termsOf(pattern: string): SearchTerm[] {
	if (!PLAIN_SEARCH.test(pattern)) {
		return [];
	}
	const ignoresCase = pattern.startsWith("(?i)");
	return pattern
		.replace("(?i)", "")
		.split("|")
		.map((word) => ({
			word: word.replaceAll(String.raw`\b`, ""),
			ignoresCase,
			bounded: word.endsWith(String.raw`\b`),
		}))
		.filter(({ word }) => content(word).length > 0 && !/^[_-]|[_-]$/u.test(word));
}

/**
 * Whether a search for `term` finds these words. It runs over them in order, past a separator only where one word
 * ends, and ends inside the last unless a word boundary ends it. It starts and ends at a word that names a subject,
 * so that a prefix of a generic word names nothing.
 */
function finds(term: SearchTerm, words: readonly string[]): boolean {
	const search = term.word.toLowerCase();
	return words.some((start, first) => {
		if (GENERIC.has(stem(start))) {
			return false;
		}
		let at = 0;
		for (const word of words.slice(first)) {
			if (at >= search.length) {
				return false;
			}
			const rest = search.slice(at);
			const sameWord = rest.length <= word.length && stem(rest) === stem(word);
			const ends = sameWord || (term.bounded ? rest === word : word.startsWith(rest));
			if (rest.length >= 3 && ends && !GENERIC.has(stem(word))) {
				return true;
			}
			if (!rest.startsWith(word)) {
				return false;
			}
			at += word.length;
			if (at < search.length && "_-".includes(search.charAt(at))) {
				at += 1;
			}
		}
		return false;
	});
}

function holdsTerm(terms: readonly SearchTerm[], text: string): boolean {
	const words = wordsOf(text);
	const tokens = text.split(TOKEN_BREAK);
	const folded = new Set(tokens.map((token) => token.toLowerCase()));
	return terms.some((term) =>
		term.ignoresCase
			? folded.has(term.word.toLowerCase()) || finds(term, words)
			: tokens.includes(term.word),
	);
}

const claimed = (clause: string) =>
	!DISCLAIMER.test(clause) && ABSENCE_PATTERNS.some((pattern) => pattern.test(clause));

/** Whether a text says the repository lacks something, clause by clause. */
export function claimsAbsence(text: string): boolean {
	return text.split(CLAUSE_END).some(claimed);
}

/** The texts of a result that the absence check reads. */
function textsOf(submission: AbsenceCheckInput): string[] {
	const { search, inapplicability, openQuestions = [] } = submission.evidence;
	return [
		submission.summary,
		submission.explanation,
		...(submission.problems ?? []).flatMap((problem) => [
			problem.summary,
			problem.explanation,
			problem.nextStep ?? "",
			problem.basis.explanation,
		]),
		submission.example?.note ?? "",
		search?.lookedFor ?? "",
		search?.boundary ?? "",
		search?.hits ?? "",
		inapplicability?.prerequisite ?? "",
		inapplicability?.ruledOutBy ?? "",
		...openQuestions.flatMap((question) => [question.openQuestion, question.wouldSettleIt]),
	];
}

/**
 * The first claim that the patterns of a result's searches do not name: each clause that says something is absent, or
 * with no such clause and the checkout named as consulted, what the result looked for. `AbsenceClaims.java` decides
 * the same at admission.
 */
export function unsearchedClaim(
	subject: string,
	texts: readonly string[],
	patterns: readonly string[],
	consulted: boolean,
): string | undefined {
	const terms = patterns.flatMap(termsOf);
	return claimsOf(subject, texts, consulted).find((claim) => !holdsTerm(terms, claim));
}

/** The first claim that a search with matches names: the result must then say what they are. */
export function claimWithHits(
	subject: string,
	texts: readonly string[],
	patternsWithMatches: readonly string[],
	consulted: boolean,
): string | undefined {
	const terms = patternsWithMatches.flatMap(termsOf);
	return claimsOf(subject, texts, consulted).find((claim) => holdsTerm(terms, claim));
}

/** The text of each claim: a clause that names its own subject, or else what the result looked for. */
function claimsOf(subject: string, texts: readonly string[], consulted: boolean): string[] {
	const clauses = texts
		.flatMap((text) => text.split(CLAUSE_END))
		.filter(claimed)
		.map((clause) => (content(clause).length === 0 ? subject : clause));
	return clauses.length === 0 && consulted ? [subject] : clauses;
}

/** The texts and warrants of a submission that the absence check reads, as the candidate carries them. */
export interface AbsenceCheckInput {
	outcome: string;
	summary: string;
	explanation: string;
	problems?: readonly {
		summary: string;
		explanation: string;
		nextStep?: string;
		basis: { explanation: string };
	}[];
	example?: { note: string };
	evidence: {
		search?: { consulted: readonly string[]; lookedFor: string; boundary: string; hits?: string };
		inapplicability?: { consulted: readonly string[]; prerequisite: string; ruledOutBy: string };
		openQuestions?: readonly { openQuestion: string; wouldSettleIt: string }[];
	};
}

/**
 * The work's checkout as the absence check sees it: its root when the capture holds it, and whether it holds all of
 * it. Undefined when the review reads no checkout at all.
 */
export interface CheckoutState {
	root?: string;
	complete: boolean;
}

/** Why a submission that rests on the checkout cannot be recorded yet. */
export interface UnreadCheckoutClaim {
	text: string;
	/** The session did search the checkout, but for something else, or its search found matches. */
	kind?: "untied" | "hits";
}

/**
 * A submission whose search for something missing, or whose ruling-out of a prerequisite, names the repository tree
 * needs a search of the whole checkout in this session: a complete read of one file is a complete source of that file,
 * not of the tree (ADR 0064 § Absence). A submission of a review that reads a checkout, whose words say the repository
 * lacks something, needs the same. Without a checkout captured whole, no search can show it. The session is asked once
 * per practice (`ask`), once more when its search names something else (`askUntied`), and once more when that search found matches (`askHits`); a resubmission goes to admission, which withholds such a result, so a session that cannot
 * repair it does not spend the practice's refusals.
 */
export function unreadCheckoutClaim(
	submission: AbsenceCheckInput,
	coverage: Coverage,
	{
		checkout,
		ask,
		askUntied = ask,
		askHits = ask,
	}: { checkout?: CheckoutState; ask: boolean; askUntied?: boolean; askHits?: boolean },
): UnreadCheckoutClaim | undefined {
	const receipts = coverage.receipts.filter(
		(receipt) =>
			receipt.sourceKind === TREE_SOURCE && receipt.status === "SEARCHED" && receipt.path === ".",
	);
	const searches = receipts.map((receipt) => receipt.pattern ?? "");
	const withMatches = receipts
		.filter((receipt) => receipt.matches !== 0)
		.map((receipt) => receipt.pattern ?? "");
	// A could-not-determine result says nothing about the work.
	if (submission.outcome === "COULD_NOT_DETERMINE") {
		return undefined;
	}
	const { search, inapplicability } = submission.evidence;
	const consulted = [...(search?.consulted ?? []), ...(inapplicability?.consulted ?? [])].includes(
		TREE_SOURCE,
	);
	const byWords = !consulted && checkout !== undefined && textsOf(submission).some(claimsAbsence);
	const subject = search?.lookedFor ?? inapplicability?.prerequisite ?? submission.summary;
	if (!consulted && !byWords) {
		return undefined;
	}
	if (searches.length > 0) {
		const unsearched = unsearchedClaim(subject, textsOf(submission), searches, consulted);
		if (unsearched !== undefined) {
			return askUntied
				? {
						kind: "untied",
						text: `no search of the whole checkout names what this claim says is absent: "${unsearched}". Either reword the claim to name what you searched for, or call the grep tool with ignoreCase and a plain word that the claim holds, alternatives joined by |. A pattern with any other syntax names nothing. If you can do neither, remove the claim and keep only what the cited lines show.`,
					}
				: undefined;
		}
		const unexplained = claimWithHits(subject, textsOf(submission), withMatches, consulted);
		if (unexplained === undefined || search?.hits !== undefined || inapplicability !== undefined) {
			return undefined;
		}
		return askHits
			? {
					kind: "hits",
					text: `a search that names this claim found matches: "${unexplained}". A match can show that the thing exists. Read the matches, then add evidence.search with consulted, lookedFor, boundary and hits, where hits says what the matches are and why they do not show the thing. Or remove the claim and keep only what the cited lines show.`,
				}
			: undefined;
	}
	if (!ask) {
		return undefined;
	}
	const claim = consulted
		? `this result names ${TREE_SOURCE} as searched`
		: "this result says the repository lacks something beyond the cited lines";
	const remove = "Remove that claim and keep only what the cited lines show, then submit again.";
	if (checkout?.root === undefined) {
		return { text: `${claim}, but this work has no checkout to search. ${remove}` };
	}
	if (!checkout.complete) {
		return {
			text: `${claim}, but this checkout was captured only in part, so no search of it can show what the repository lacks. ${remove}`,
		};
	}
	const first = textsOf(submission)
		.flatMap((text) => text.split(CLAUSE_END))
		.find(claimed);
	const exit = consulted
		? `If your search covered only the selected or cited lines, name the source that holds them, such as scm.pull-request.diff, in place of ${TREE_SOURCE}.`
		: "If you cannot, remove the claim about the rest of the repository and keep only what the cited lines show.";
	return {
		// A grep in bash runs, but it leaves no receipt that admission can read, so the model must hear which tool counts.
		text: `${claim}${first === undefined ? "" : `: "${first}"`}, but this session ran no search of the whole checkout. Call the grep tool, not grep in bash, with ignoreCase, a plain word that the claim holds, alternatives joined by |, and path "${checkout.root}", without a glob. A pattern with any other syntax names nothing, and a search in bash proves nothing to admission. If the search is cut, raise limit or use a longer word of the claim. Or reword the claim to name what you searched for. ${exit}`,
	};
}
