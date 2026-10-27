/**
 * The host's side of a program's reads: the pinned head tree, the captured change and the captured
 * records. Each read leaves a receipt, so the host knows what a program actually read, cut short, or
 * could not read. A missing file is proved absence; a read that fails is unavailable.
 */
import { createHash } from "node:crypto";
import { lstat, open, readFile, realpath, stat } from "node:fs/promises";
import path from "node:path";
import { DIFF_SOURCE, TREE_SOURCE } from "../../kits/scm/sources.ts";

import { globFilesSync } from "../../kits/scm/files.ts";
import { grep } from "../../kits/scm/grep.ts";
import type { DiffFile } from "../../kits/scm/types.ts";
import {
	CANDIDATE_LIMITS,
	isSafeRelativePath,
	type Citation,
	type ReadReceipt,
} from "./contract.ts";
import type { ReadRequest, ReadValues } from "./ipc.ts";
import { UnratedError } from "./practice-script.ts";
import { ReadRanges } from "./read-ranges.ts";

export const MAX_READ_BYTES = 2 * 1024 * 1024;
const MAX_RECORD_BYTES = 16 * 1024 * 1024;
const MAX_LIST_PATHS = 5000;
const DEFAULT_GREP_RESULTS = 200;
const MAX_CITED_LINES = 200;
/** Bytes that tell a binary file: a NUL among the first of them. */
const BINARY_PROBE_BYTES = 8000;

/** What a practice's reads reached: every read, those cut short, and those that failed. */
export interface Receipts {
	reads: number;
	truncatedReads: number;
	failedReads: number;
}

export const noReceipts = (): Receipts => ({ reads: 0, truncatedReads: 0, failedReads: 0 });

/** The pinned captures a host reads for its programs. */
export interface EvidenceSources {
	repo: string | null;
	headRevision: string | null;
	/** The workspace path of the captured tree's source witness. */
	treeSourceFile: string;
	/** The absolute path of the captured context folder; null when nothing was captured. */
	contextDir: string | null;
	/** The workspace path of the context folder, as citations name it. */
	contextReference: string;
	/** The captured change, parsed; null without one. */
	change: { base: string; head: string; files: ReadonlyMap<string, DiffFile> } | null;
	/** The source kind of a captured sourceFile, by its workspace path; undefined for one not captured. */
	sourceKindOf: (sourcePath: string) => string | undefined;
	permittedPaths?: ReadonlySet<string>;
}

const isMissing = (error: unknown) =>
	error instanceof Error &&
	"code" in error &&
	(error.code === "ENOENT" || error.code === "ENOTDIR" || error.code === "EISDIR");

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

const inGitFolder = (relative: string) => relative.split(/[\\/]/u).includes(".git");

/** The absolute path of `relative` inside `root`, symlinks resolved; a path that leaves the root is refused. */
async function inside(root: string, relative: string): Promise<string> {
	const base = await realpath(root);
	const full = await realpath(path.resolve(base, relative));
	if (full !== base && !full.startsWith(`${base}${path.sep}`)) {
		throw new UnratedError("refused", `${relative} is outside the captured source`);
	}
	if (inGitFolder(path.relative(base, full))) {
		throw new UnratedError("refused", `${relative} is in the checkout's Git folder, not the work`);
	}
	return full;
}

export function assertCitationRange(start: number, end: number): void {
	if (start < 1) {
		throw new UnratedError("error", "a citation starts at line 1 or later");
	}
	if (end < start) {
		throw new UnratedError("error", `the range ${start}-${end} ends before it starts`);
	}
	if (end - start >= MAX_CITED_LINES) {
		throw new UnratedError("too-large", `a citation spans at most ${MAX_CITED_LINES} lines`);
	}
}

/** The lines `start` to `end` of a text, 1-based, or why they cannot be quoted. */
export function quotedLines(text: string, start: number, end: number): string {
	assertCitationRange(start, end);
	const lines = text.split("\n");
	if (end > lines.length) {
		throw new UnratedError("error", `the source has ${lines.length} lines, not ${end}`);
	}
	const quote = lines.slice(start - 1, end).join("\n");
	if (quote.length > CANDIDATE_LIMITS.quoteChars) {
		throw new UnratedError(
			"too-large",
			`a quote holds at most ${CANDIDATE_LIMITS.quoteChars} characters`,
		);
	}
	return quote;
}

/** The lines a change adds or removes, `start` to `end`, or why the change does not hold them. */
export function changeLines(
	file: DiffFile | undefined,
	side: "OLD" | "NEW",
	start: number,
	end: number,
): string {
	assertCitationRange(start, end);
	if (file === undefined) {
		throw new UnratedError("error", "the change does not touch that file");
	}
	const lines = side === "OLD" ? file.removedLines : file.addedLines;
	const picked: string[] = [];
	for (let line = start; line <= end; line += 1) {
		const text = lines.get(line);
		if (text === undefined) {
			throw new UnratedError(
				"error",
				`the change does not ${side === "OLD" ? "remove" : "add"} line ${line} of ${file.path}`,
			);
		}
		picked.push(text);
	}
	const quote = picked.join("\n");
	if (quote === "" || quote.length > CANDIDATE_LIMITS.quoteChars) {
		throw new UnratedError("too-large", "those lines cannot be quoted");
	}
	return quote;
}

export class EvidenceReader {
	readonly #sources: EvidenceSources;
	readonly #receipts: Receipts;
	readonly #readReceipts = new Map<string, ReadReceipt>();
	readonly #ranges = new ReadRanges();

	constructor(sources: EvidenceSources, receipts: Receipts) {
		this.#sources = sources;
		this.#receipts = receipts;
	}

	get readReceipts(): readonly ReadReceipt[] {
		return [...this.#readReceipts.values()];
	}
	allowsCitation(citation: Citation): boolean {
		return (
			this.#sources.permittedPaths === undefined || this.#sources.permittedPaths.has(citation.path)
		);
	}

	/** Answer one read. A read that cannot be answered throws `UnratedError` with its reason. */
	async answer<R extends ReadRequest>(
		request: R,
		signal?: AbortSignal,
	): Promise<ReadValues[R["op"]]>;
	async answer(request: ReadRequest, signal?: AbortSignal): Promise<ReadValues[keyof ReadValues]> {
		signal?.throwIfAborted();
		if (request.op === "cite") {
			try {
				return await this.#cite(request, signal);
			} catch (error) {
				if (error instanceof UnratedError || signal?.aborted === true) {
					throw error;
				}
				throw new UnratedError("unavailable", "the cited source could not be read");
			}
		}
		this.#receipts.reads += 1;
		try {
			const value = await this.#read(request, signal);
			signal?.throwIfAborted();
			if ("truncated" in value && value.truncated && request.op !== "read") {
				this.#receipts.truncatedReads += 1;
			}
			return value;
		} catch (error) {
			if (signal?.aborted === true) {
				throw signal.reason;
			}
			this.#receipts.failedReads += 1;
			if (request.op === "read") {
				this.#receipt({
					sourceKind: TREE_SOURCE,
					sourcePath: this.#sources.treeSourceFile,
					path: request.path,
					version: this.#sources.headRevision ?? "unavailable",
					status: "FAILED",
				});
			}
			throw error instanceof UnratedError
				? error
				: new UnratedError("unavailable", messageOf(error));
		}
	}

	#receipt(receipt: ReadReceipt, last?: number): void {
		const key = JSON.stringify(receipt);
		if (this.#readReceipts.has(key)) {
			return;
		}
		if (this.#readReceipts.size >= CANDIDATE_LIMITS.receipts) {
			throw new UnratedError("too-large", "read receipts exceed the proof bound");
		}
		this.#readReceipts.set(key, receipt);
		if (
			receipt.sourceKind !== TREE_SOURCE ||
			(receipt.status !== "TRUNCATED" && receipt.status !== "COMPLETE")
		) {
			return;
		}
		const identity = JSON.stringify([
			receipt.sourceKind,
			receipt.sourcePath,
			receipt.path,
			receipt.version,
		]);
		if (last === undefined) {
			this.#receipts.truncatedReads += 1;
			return;
		}
		const wasIncomplete = this.#ranges.incomplete.includes(identity);
		this.#ranges.add(identity, receipt.startLine ?? 0, receipt.endLine ?? 0, last);
		const complete = this.#ranges.complete(identity);
		if (!wasIncomplete && !complete) {
			this.#receipts.truncatedReads += 1;
		} else if (wasIncomplete && complete) {
			this.#receipts.truncatedReads -= 1;
		}
		if (complete && receipt.contentDigest !== undefined) {
			const settled: ReadReceipt = { ...receipt, status: "COMPLETE", startLine: 1, endLine: last };
			const settledKey = JSON.stringify(settled);
			if (
				this.#readReceipts.has(settledKey) ||
				this.#readReceipts.size < CANDIDATE_LIMITS.receipts
			) {
				this.#readReceipts.set(settledKey, settled);
			}
		}
	}

	/**
	 * A search of the whole checkout's contents for `pattern` that ran to the end, without a file filter. A scan
	 * reads only its assigned file and context, so its searches prove nothing about the rest of the tree and get no
	 * receipt. A pattern longer than a receipt holds gets none either.
	 */
	#searchReceipt(
		record: boolean,
		truncated: boolean,
		{ pattern, glob }: { pattern: string; glob?: string },
	): void {
		if (
			!record ||
			truncated ||
			glob !== undefined ||
			this.#sources.permittedPaths !== undefined ||
			pattern.length > CANDIDATE_LIMITS.warrantFieldChars
		) {
			return;
		}
		this.#receipt({
			sourceKind: TREE_SOURCE,
			sourcePath: this.#sources.treeSourceFile,
			path: ".",
			version: this.#sources.headRevision ?? "unavailable",
			status: "SEARCHED",
			pattern,
		});
	}

	#repo(): string {
		if (this.#sources.repo === null) {
			throw new UnratedError("unavailable", "this work has no captured file tree");
		}
		return this.#sources.repo;
	}

	async #read(
		request: Exclude<ReadRequest, { op: "cite" }>,
		signal?: AbortSignal,
		recordReceipt = true,
	): Promise<ReadValues[keyof ReadValues]> {
		switch (request.op) {
			case "read": {
				const repo = this.#repo();
				if (
					this.#sources.permittedPaths !== undefined &&
					!this.#sources.permittedPaths.has(request.path)
				) {
					throw new UnratedError(
						"refused",
						`${request.path} is outside this practice's captured context`,
					);
				}
				if (!isSafeRelativePath(request.path)) {
					throw new UnratedError("refused", "a file read needs a repository path");
				}
				const entry = await lstat(path.resolve(repo, request.path)).catch((error: unknown) => {
					if (isMissing(error)) {
						return null;
					}
					throw error;
				});
				if (entry?.isSymbolicLink() === true) {
					throw new UnratedError("refused", "captured symlinks cannot be followed");
				}
				const identity = {
					sourceKind: TREE_SOURCE,
					sourcePath: this.#sources.treeSourceFile,
					path: request.path,
					version: this.#sources.headRevision ?? "unavailable",
				};
				let file: string;
				try {
					file = await inside(repo, request.path);
				} catch (error) {
					if (isMissing(error)) {
						this.#receipt({ ...identity, status: "ABSENT" });
						return { status: "ABSENT" };
					}
					throw error;
				}
				const stats = await stat(file);
				if (!stats.isFile()) {
					this.#receipt({ ...identity, status: "ABSENT" });
					return { status: "ABSENT" };
				}
				const handle = await open(file, "r");
				try {
					const size = Math.min(stats.size, MAX_READ_BYTES);
					const bytes = Buffer.alloc(size);
					await handle.read(bytes, 0, size, 0);
					if (bytes.subarray(0, BINARY_PROBE_BYTES).includes(0)) {
						this.#receipt({ ...identity, status: "BINARY" });
						return { status: "BINARY" };
					}
					const text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes, {
						stream: stats.size > MAX_READ_BYTES,
					});
					const lines = text.split("\n");
					const start = request.offsetLine ?? 1;
					const max = request.maxLines ?? lines.length;
					if (!Number.isSafeInteger(start) || start < 1 || !Number.isSafeInteger(max) || max < 1) {
						throw new UnratedError("error", "file read bounds must be positive integers");
					}
					const selected = lines.slice(start - 1, start - 1 + max).join("\n");
					const end = Math.min(lines.length, start - 1 + max);
					const truncated = stats.size > MAX_READ_BYTES || start - 1 + max < lines.length;
					const contentDigest = createHash("sha256").update(bytes).digest("hex");
					if (start <= end) {
						this.#receipt(
							{
								...identity,
								status:
									stats.size <= MAX_READ_BYTES && start === 1 && end === lines.length
										? "COMPLETE"
										: "TRUNCATED",
								...(stats.size <= MAX_READ_BYTES ? { contentDigest } : {}),
								startLine: start,
								endLine: end,
							},
							stats.size <= MAX_READ_BYTES ? lines.length : undefined,
						);
					} else {
						this.#receipt({ ...identity, status: "TRUNCATED" });
					}
					return { status: "FOUND", text: selected, truncated };
				} finally {
					await handle.close();
				}
			}
			case "list": {
				const repo = this.#repo();
				const all = globFilesSync(request.glob ?? "**", repo).filter(
					(file) =>
						this.#sources.permittedPaths === undefined || this.#sources.permittedPaths.has(file),
				);
				return {
					paths: all.slice(0, MAX_LIST_PATHS),
					truncated: all.length > MAX_LIST_PATHS,
				};
			}
			case "grep": {
				const repo = this.#repo();
				const maxResults = request.maxResults ?? DEFAULT_GREP_RESULTS;
				// One match more than asked tells a search that was cut from one that found exactly the bound.
				const matches = await grep(
					request.pattern,
					repo,
					{
						glob: request.glob,
						maxResults: maxResults + 1,
						fixedString: request.fixedString,
						paths:
							this.#sources.permittedPaths === undefined
								? undefined
								: [...this.#sources.permittedPaths],
					},
					signal,
				);
				const truncated = matches.length > maxResults;
				this.#searchReceipt(recordReceipt, truncated, request);
				return { matches: matches.slice(0, maxResults), truncated };
			}
			case "record": {
				if (this.#sources.permittedPaths !== undefined) {
					throw new UnratedError(
						"refused",
						"scan evidence reads use the assigned file and frozen context paths",
					);
				}
				const { contextDir } = this.#sources;
				if (contextDir === null) {
					return { status: "ABSENT" };
				}
				let file: string;
				try {
					file = await inside(contextDir, request.name);
				} catch (error) {
					if (isMissing(error)) {
						return { status: "ABSENT" };
					}
					throw error;
				}
				const stats = await stat(file);
				if (!stats.isFile()) {
					return { status: "ABSENT" };
				}
				if (stats.size > MAX_RECORD_BYTES) {
					throw new UnratedError(
						"too-large",
						`${request.name} is larger than ${MAX_RECORD_BYTES} bytes`,
					);
				}
				const text = await readFile(file, { encoding: "utf8", signal });
				const sourcePath = path.posix.join(this.#sources.contextReference, request.name);
				const sourceKind = this.#sources.sourceKindOf(sourcePath);
				if (sourceKind === undefined) {
					throw new UnratedError("refused", `${request.name} is not a captured source`);
				}
				const contentDigest = createHash("sha256").update(text).digest("hex");
				if (recordReceipt) {
					this.#receipt({
						sourceKind,
						sourcePath,
						path: sourcePath,
						version: `sha256:${contentDigest}`,
						status: "COMPLETE",
						contentDigest,
						startLine: 1,
						endLine: text.split("\n").length,
					});
				}
				return { status: "FOUND", text };
			}
		}
	}

	async #cite(
		request: Extract<ReadRequest, { op: "cite" }>,
		signal?: AbortSignal,
	): Promise<Citation> {
		const end = request.endLine ?? request.startLine;
		switch (request.source) {
			case "tree": {
				const repo = this.#repo();
				if (
					this.#sources.permittedPaths !== undefined &&
					!this.#sources.permittedPaths.has(request.path)
				) {
					throw new UnratedError("refused", "citation is outside the captured context");
				}
				const entry = await lstat(path.resolve(repo, request.path));
				if (entry.isSymbolicLink()) {
					throw new UnratedError("refused", "captured symlinks cannot be quoted");
				}
				const { headRevision } = this.#sources;
				const file = await inside(repo, request.path).catch((error: unknown) => {
					throw isMissing(error)
						? new UnratedError("error", `${request.path} is not in the tree`)
						: error;
				});
				const stats = await stat(file);
				if (!stats.isFile() || stats.size > MAX_READ_BYTES) {
					throw new UnratedError("too-large", `${request.path} cannot be quoted`);
				}
				return {
					sourceKind: TREE_SOURCE,
					sourcePath: this.#sources.treeSourceFile,
					path: request.path,
					...(headRevision === null ? {} : { commit: headRevision }),
					startLine: request.startLine,
					endLine: end,
					quote: quotedLines(
						await readFile(file, { encoding: "utf8", signal }),
						request.startLine,
						end,
					),
				};
			}
			case "change": {
				const { change } = this.#sources;
				if (change === null) {
					throw new UnratedError("unavailable", "this work has no captured change");
				}
				return {
					sourceKind: DIFF_SOURCE,
					sourcePath: path.posix.join(this.#sources.contextReference, "change.json"),
					path: request.path,
					side: request.side,
					startLine: request.startLine,
					endLine: end,
					quote: changeLines(change.files.get(request.path), request.side, request.startLine, end),
				};
			}
			case "record": {
				const sourcePath = path.posix.join(this.#sources.contextReference, request.name);
				const sourceKind = this.#sources.sourceKindOf(sourcePath);
				if (sourceKind === undefined) {
					throw new UnratedError("error", `${request.name} is not a captured source`);
				}
				const record = await this.#read({ op: "record", name: request.name }, signal, false);
				if (!("text" in record) || typeof record.text !== "string") {
					throw new UnratedError("error", `${request.name} was not captured`);
				}
				return {
					sourceKind,
					sourcePath,
					path: sourcePath,
					startLine: request.startLine,
					endLine: end,
					quote: quotedLines(record.text, request.startLine, end),
				};
			}
		}
	}
}
