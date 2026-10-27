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
} from "./work/practice-scripts/lib/core/contract.ts";
import {
	type EvidenceReader,
	type Receipts,
	noReceipts,
} from "./work/practice-scripts/lib/core/evidence-reader.ts";
import { isJsonObject as isRecord } from "./work/practice-scripts/lib/core/json.ts";
import { ReadRanges } from "./work/practice-scripts/lib/core/read-ranges.ts";

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
	readonly #searchedPatterns = new Set<string>();

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
			this.#searched(args, target);
		}
	}

	/**
	 * Records the pattern of a grep that ran to the end over the whole checkout. A file filter or a narrower path
	 * searched part of the checkout, and a file listing searched no contents.
	 */
	#searched(args: unknown, target: string | undefined): void {
		if (
			isRecord(args) &&
			typeof args.pattern === "string" &&
			args.pattern !== "" &&
			args.pattern.length <= CANDIDATE_LIMITS.warrantFieldChars &&
			this.#searchedPatterns.size < SEARCHED_PATTERNS &&
			!(typeof args.glob === "string" && args.glob !== "") &&
			this.#containsTree(target ?? "")
		) {
			this.#searchedPatterns.add(args.pattern);
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
	 * the end, with its pattern, and then each file read to its last line, with the digest of the pinned bytes. A
	 * search that was cut, failed, filtered by file, narrower than the checkout or run through the shell proves no
	 * such search, and so does any search of a checkout captured only in part. The searches come first, so the receipt
	 * bound never drops them. The `.git` folder holds no work.
	 */
	treeReceipts(): ReadReceipt[] {
		const tree = this.#tree;
		if (tree === undefined) {
			return [];
		}
		const root = this.#key(tree.root);
		const searches = tree.complete ? [...this.#searchedPatterns] : [];
		const receipts: ReadReceipt[] = searches.map((pattern) => ({
			sourceKind: TREE_SOURCE,
			sourcePath: tree.sourcePath,
			path: ".",
			version: tree.version,
			status: "SEARCHED",
			pattern,
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
 * and admission withholds such a result without a search of the whole checkout, so the session is asked first, once.
 * A clause that states what the review did not establish is a limit of the review, not a claim. Clauses end at a full
 * stop, a semicolon, a colon, a dash, a parenthesis or a comma before a conjunction, so such a limit cannot hide the
 * claim beside it.
 */
export const ABSENCE_MARKERS = [
	String.raw`\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|absent)\b[^.]{0,80}\b(?:in|across|throughout|under) (?:the )?(?:whole |entire |reviewed |pinned |checked-out |rest of the )?(?:repository|repo|codebase|code base|checkout|project|application|source tree|src)\b(?!\.\w|[ -](?:code|manifest|inventory|policy|board|tracker)\b)`,
	String.raw`\b(?:no|none|nothing|not any|nowhere|never|lacks?|without|missing|absent)\b[^.]{0,80}\b(?:anywhere|elsewhere) (?:in|across|throughout|under) (?:the )?(?:whole |entire |reviewed |pinned |checked-out |rest of the )?(?:code|app|source|sources)\b(?![ -](?:file|files|change|diff)\b)`,
	String.raw`\bno other files?\b(?! (?:in|of) (?:the|this) (?:change|diff|commit|pull request)\b)[^.;]{0,30}\b(?:references?|imports?|calls?|uses?|instantiates?|consumes?|wires?|depends? on|handles?|catches?|validates?)\b|\b(?:referenced|imported|called|used|instantiated|consumed|handled) (?:by|from|in) no other files?\b|\breferences from other files\b`,
	String.raw`\b(?:the|this) (?:whole |entire |reviewed |pinned )?(?:repository|repo|codebase|code base|checkout)\b(?! (?:method|call|class|interface|layer|bean|query|lookup|result|field|object|instance|step|action|policy|settings?)s?\b)[^.]{0,40}\b(?:contains no|has no|holds no|defines no|declares no|registers no|lacks?|never|does not|doesn't)\b|\b(?:the|this) (?:project|application)\b(?![ -](?:code|manifest|inventory|policy|board|tracker)\b)[^.]{0,40}\b(?:defines no|declares no|registers no|never)\b`,
	String.raw`\b(?:nothing else|nothing in the (?:project|application|code ?base|repository|repo|checkout)|no (?:other )?(?:code|callers?)(?! path| (?:in|of)\b)|none of (?:the|its) callers)\b[^.]{0,60}\b(?:catch(?:es)?|handles?)\b(?!\()`,
];
export const ABSENCE_DISCLAIMER = String.raw`\b(?:did|does|do|could|can|would)(?: not|n't) (?:\w+ ){0,1}?(?:establish|inspect|examine|search|judge)\w*\b|\bnot (?:established|inspected|examined|searched|judged)\b|\bwithout (?:establishing|inspecting|examining|searching|judging)\b|\bnot that\b|\b(?:was|were) not (?:examined|inspected|judged)\b|\bno (?:[\w-]+ ){0,8}?(?:was|were) (?:examined|inspected|judged)\b`;
export const ABSENCE_CLAUSE_END = String.raw`(?<=[.;:])[ \t\r\n\u00A0]+|,[ \t\r\n\u00A0]+(?=(?:but|and|so|yet|while|though|although)[ \t\r\n\u00A0])|[ \t\r\n\u00A0]+[—–-][ \t\r\n\u00A0]+|[ \t\r\n\u00A0]*[()][ \t\r\n\u00A0]*`;
const ABSENCE_PATTERNS = ABSENCE_MARKERS.map((marker) => new RegExp(marker, "iu"));
const DISCLAIMER = new RegExp(ABSENCE_DISCLAIMER, "iu");
const CLAUSE_END = new RegExp(ABSENCE_CLAUSE_END, "u");

/** Whether a text says the repository lacks something, clause by clause. */
export function claimsAbsence(text: string): boolean {
	return text
		.split(CLAUSE_END)
		.some(
			(clause) =>
				!DISCLAIMER.test(clause) && ABSENCE_PATTERNS.some((pattern) => pattern.test(clause)),
		);
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
		search?: { consulted: readonly string[]; lookedFor: string; boundary: string };
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

/** Why a submission that rests on the checkout cannot be recorded yet, and whether only its words say so. */
export interface UnreadCheckoutClaim {
	text: string;
	byWords: boolean;
}

/**
 * A submission whose search for something missing, or whose ruling-out of a prerequisite, names the repository tree
 * needs a search of the whole checkout in this session: a complete read of one file is a complete source of that file,
 * not of the tree (ADR 0064 § Absence). With `askAboutWords`, a submission of a review that reads a checkout, whose
 * words say the repository lacks something, needs the same. Without a checkout captured whole, no search can show it.
 */
export function unreadCheckoutClaim(
	submission: AbsenceCheckInput,
	coverage: Coverage,
	{ checkout, askAboutWords }: { checkout?: CheckoutState; askAboutWords: boolean },
): UnreadCheckoutClaim | undefined {
	const searched = (coverage.source === "host" ? coverage.receipts : []).some(
		(receipt) =>
			receipt.sourceKind === TREE_SOURCE && receipt.status === "SEARCHED" && receipt.path === ".",
	);
	// A could-not-determine result says nothing about the work.
	if (searched || submission.outcome === "COULD_NOT_DETERMINE") {
		return undefined;
	}
	const { search, inapplicability, openQuestions = [] } = submission.evidence;
	const consulted = [...(search?.consulted ?? []), ...(inapplicability?.consulted ?? [])].includes(
		TREE_SOURCE,
	);
	const texts = [
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
		inapplicability?.prerequisite ?? "",
		inapplicability?.ruledOutBy ?? "",
		...openQuestions.flatMap((question) => [question.openQuestion, question.wouldSettleIt]),
	];
	const byWords =
		!consulted && checkout !== undefined && askAboutWords && texts.some(claimsAbsence);
	if (!consulted && !byWords) {
		return undefined;
	}
	const claim = consulted
		? `this result names ${TREE_SOURCE} as searched`
		: "this result says the repository lacks something beyond the cited lines";
	const remove = "Remove that claim and keep only what the cited lines show, then submit again.";
	if (checkout?.root === undefined) {
		return { byWords, text: `${claim}, but this work has no checkout to search. ${remove}` };
	}
	if (!checkout.complete) {
		return {
			byWords,
			text: `${claim}, but this checkout was captured only in part, so no search of it can show what the repository lacks. ${remove}`,
		};
	}
	const exit = consulted
		? `If your search covered only the selected or cited lines, name the source that holds them, such as scm.pull-request.diff, in place of ${TREE_SOURCE}.`
		: "If you cannot, remove the claim about the rest of the repository and keep only what the cited lines show.";
	return {
		byWords,
		text: `${claim}, but this session ran no search of the whole checkout. Call grep with a pattern for what is missing and path "${checkout.root}", without a glob, and narrow the pattern until the search is not cut, then submit again. ${exit}`,
	};
}
