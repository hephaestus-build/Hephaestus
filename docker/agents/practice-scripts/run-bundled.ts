// This harness validates pinned evidence and script input without making model calls.
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { isDeepStrictEqual } from "node:util";

import type { DiffFile } from "./kits/scm/types.ts";
import {
	parseCandidate,
	parseReviewOptions,
	practiceInputsSchema,
	type Candidate,
	type ReviewOptions,
} from "./lib/core/contract.ts";
import { EvidenceReader, noReceipts, type EvidenceSources } from "./lib/core/evidence-reader.ts";
import { isJsonObject } from "./lib/core/json.ts";
import { resolveLeads } from "./lib/core/leads.ts";
import {
	isPracticeScriptDefinition,
	type ModelNeeds,
	type PracticeScriptDefinition,
} from "./lib/core/practice-script.ts";
import { programContext } from "./lib/core/program-context.ts";
import { stagePracticeScripts } from "./stage.ts";

const SCRIPTS_DIR = path.resolve(
	import.meta.dirname,
	"../../../server/application/src/main/resources/practices/scripts",
);

const ADMITTED_AT = "2026-01-01T00:00:00Z";

export const COMMIT = "a".repeat(40);

export function pullRequestRecord(fields: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		basis: { admission_fields: [], other_fields: "CAPTURE" },
		pr_number: 1,
		pr_url: "https://example.org/team/project/pull/1",
		repository_full_name: "team/project",
		source_branch: "feature",
		target_branch: "main",
		commit_sha: COMMIT,
		about_role: "AUTHOR",
		is_draft: false,
		is_merged: false,
		additions: 0,
		deletions: 0,
		changed_files: 0,
		labels: [],
		assignees: [],
		...fields,
	};
}

export function issueRecord(fields: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		issue_number: 1,
		title: null,
		body: "",
		state: "OPEN",
		state_reason: null,
		html_url: null,
		repository_full_name: "team/project",
		author: null,
		author_id: null,
		issue_type: null,
		is_locked: false,
		sub_issues_total: null,
		sub_issues_completed: null,
		milestone: null,
		closed_at: null,
		labels: [],
		assignees: [],
		...fields,
	};
}

export function requested(kind = "scm.pull_request", state: Record<string, string> = {}) {
	return {
		contractVersion: 3,
		kind,
		item: kind === "scm.issue" ? issueRecord() : pullRequestRecord(),
		about: { role: "AUTHOR", developerId: "1" },
		practice: {
			id: "1",
			versionId: "1",
			reviewDigest: "v6:test",
			slug: "fixture",
			criteria: "Review the captured work.",
		},
		scope: { kind: "ITEM", item: { id: "1", kind }, version: COMMIT },
		review: {
			id: "11111111-1111-4111-8111-111111111111",
			origin: "REQUEST",
			startedAt: ADMITTED_AT,
			...(Object.keys(state).length === 0 ? {} : { event: { name: `${kind}.updated`, state } }),
		},
		previous: null,
	};
}
export function event(name: string, origin: "EVENT" | "REQUEST" = "EVENT") {
	const kind = name.split(".").slice(0, 2).join(".");
	const inputs = requested(kind);
	return { ...inputs, review: { ...inputs.review, origin, event: { name, state: {} } } };
}
export function scan(filePath: string) {
	const inputs = requested("scm.file");
	return {
		...inputs,
		item: { repositoryId: "1", providerId: "1", path: filePath, blobId: COMMIT, commitId: COMMIT },
		about: null,
		scope: {
			kind: "SCAN",
			item: { id: "2", kind: "scm.file", parentId: "1", path: filePath },
			version: `scm.file:v1:${COMMIT}:${"b".repeat(64)}`,
		},
	};
}

export const REVIEWED: Candidate = {
	outcome: "COULD_NOT_DETERMINE",
	summary: "The fake review leaves the practice open",
	explanation: "The test harness answers every review with this result.",
	citations: [
		{
			sourceKind: "scm.pull-request.core",
			sourcePath: "context/metadata.json",
			path: "context/metadata.json",
			startLine: 1,
			endLine: 1,
			quote: "{",
		},
	],
	evidence: {
		openQuestions: [
			{ openQuestion: "What would the review decide?", wouldSettleIt: "A review session." },
		],
	},
	coverage: {
		source: "host",
		reads: 0,
		truncatedReads: 0,
		failedReads: 0,
		receipts: [],
		complete: true,
	},
};

/** Files by their path in a folder: a string is the text of the file, and any other value is JSON. */
export type Files = Record<string, unknown>;

export interface RunInputs {
	/** A stored practice program; absent selects the bundled resource. */
	script?: string;
	/**
	 * The record of the work, which the program reads as `ctx.item`. For a pull request or an issue,
	 * these fields go over a record that holds every required field.
	 */
	item?: Record<string, unknown>;
	/** The practice inputs, as `practiceInputsSchema` reads them. The default is a requested pull request review. */
	inputs?: unknown;
	previous?: unknown;
	/** The checkout: a folder, or the files of a new one. The default is an empty checkout. */
	repo?: string | Files;
	/** The captured change. The default is a captured empty change; null is a change that was not captured. */
	change?: ReadonlyMap<string, DiffFile> | null;
	/** The context folder: a folder, or the files of a new one. The default is no folder. */
	context?: string | Files;
	contextReference?: string;
	reviewed?: Candidate;
}

export interface BundledRun {
	reviews: ReviewOptions[];
	outcome: { candidate: Candidate } | { error: unknown };
}

export interface BundledReview {
	review: ReviewOptions;
	candidate: Candidate;
}

async function writeFiles(dir: string, files: Files): Promise<void> {
	for (const [name, value] of Object.entries(files)) {
		const file = path.join(dir, name);
		await mkdir(path.dirname(file), { recursive: true });
		await writeFile(file, typeof value === "string" ? value : JSON.stringify(value));
	}
}

async function folder(root: string, name: string, given: string | Files | undefined) {
	if (typeof given === "string") {
		return given;
	}
	const dir = path.join(root, name);
	await mkdir(dir, { recursive: true });
	await writeFiles(dir, given ?? {});
	return dir;
}

function recordOf(kind: unknown, item: Record<string, unknown> | undefined): unknown {
	if (kind === "scm.issue") {
		return issueRecord(item);
	}
	return kind === "scm.pull_request" ? pullRequestRecord(item) : item;
}

async function load(
	slug: string,
	script?: string,
): Promise<{
	definition: PracticeScriptDefinition<ModelNeeds>;
	labels: Record<string, string>;
	root: string;
}> {
	const { root, practices } = await stagePracticeScripts(
		{ [slug]: script ?? (await readFile(path.join(SCRIPTS_DIR, `${slug}.ts`), "utf8")) },
		{},
	);
	const mod: unknown = await import(path.join(practices, `${slug}.ts`));
	const exported: unknown = isJsonObject(mod) ? mod.default : undefined;
	if (!isPracticeScriptDefinition(exported)) {
		throw new Error(`${slug} does not export a definePracticeScript definition`);
	}
	const labels: unknown = isJsonObject(mod) ? mod.LABELS : undefined;
	return {
		definition: exported,
		labels: isJsonObject(labels)
			? Object.fromEntries(
					Object.entries(labels).flatMap(([key, label]) =>
						typeof label === "string" ? [[key, label]] : [],
					),
				)
			: {},
		root,
	};
}

/**
 * The key that a bundled program's `LABELS` export gives each lead label, so that a test names a
 * lead by its key rather than by its sentence. A label the program does not export fails the test.
 */
export async function leadKeys(slug: string): Promise<(label: string) => string> {
	const { labels, root } = await load(slug);
	await rm(root, { recursive: true, force: true });
	const byLabel = new Map(Object.entries(labels).map(([key, label]) => [label, key]));
	return (label) => {
		const key = byLabel.get(label);
		if (key === undefined) {
			throw new Error(`${slug} exports no LABELS entry for "${label}"`);
		}
		return key;
	};
}

function fixtureSource(change: DiffFile): string {
	const captured = new Map(change.addedLines);
	for (const hunk of change.hunks) {
		let line = hunk.newStart;
		for (const text of hunk.lines) {
			if (text.startsWith(" ") || text.startsWith("+")) {
				captured.set(line, text.slice(1));
				line += 1;
			}
		}
	}
	const last = Math.max(0, ...captured.keys());
	return Array.from({ length: last }, (_, i) => captured.get(i + 1) ?? "").join("\n");
}

/**
 * Run the bundled program `slug` on validated inputs with the fake review, and return each review it
 * asked for and what it returned or threw.
 */
export async function runBundledProgram(slug: string, inputs: RunInputs = {}): Promise<BundledRun> {
	const { definition, root } = await load(slug, inputs.script);
	try {
		const staged = inputs.inputs ?? requested();
		if (!isJsonObject(staged)) {
			throw new TypeError("the inputs must be an object");
		}
		const parsed = practiceInputsSchema.parse({
			...staged,
			previous: inputs.previous ?? staged.previous ?? null,
			item: inputs.item === undefined ? staged.item : recordOf(staged.kind, inputs.item),
		});
		const testFiles: Files = {};
		if (inputs.repo === undefined && inputs.change !== null) {
			for (const [filePath, change] of inputs.change ?? []) {
				testFiles[filePath] = fixtureSource(change);
			}
		}
		const repo = await folder(root, "repo", inputs.repo ?? testFiles);
		const contextDir =
			inputs.context === undefined ? null : await folder(root, "context", inputs.context);
		const change = inputs.change === undefined ? new Map<string, DiffFile>() : inputs.change;
		const sources: EvidenceSources = {
			repo,
			headRevision: COMMIT,
			treeSourceFile: "repos/reviewed/.git/HEAD",
			contextDir,
			contextReference: inputs.contextReference ?? "",
			change: change === null ? null : { base: "b".repeat(40), head: COMMIT, files: change },
			sourceKindOf: () => "scm.pull-request.core",
		};
		const reader = new EvidenceReader(sources, noReceipts());
		const reviews: ReviewOptions[] = [];
		const reviewed = inputs.reviewed ?? REVIEWED;
		const ctx = programContext(
			{
				slug,
				inputs: parsed,
				files:
					parsed.kind === "scm.pull_request" || parsed.kind === "scm.file"
						? {
								head: { commit: COMMIT },
								change:
									change === null
										? null
										: { base: "b".repeat(40), head: COMMIT, files: new Map(change) },
							}
						: null,
				now: ADMITTED_AT,
				deadline: Date.now() + 60_000,
			},
			{
				read: async (request) => reader.answer(request),
				review: async (options) => {
					const checked = parseReviewOptions(options);
					if ("error" in checked) {
						throw new Error(checked.error);
					}
					await resolveLeads(checked.options.leads ?? [], sources);
					reviews.push(checked.options);
					return reviewed;
				},
				models: {},
				tokensLeft: () => 0,
				log: () => undefined,
				abortSignal: new AbortController().signal,
			},
		);
		try {
			const returned = parseCandidate(await definition.run(ctx));
			if (!returned.ok) {
				throw new Error(
					`${slug} returned no candidate: ${returned.reasonCode}: ${returned.message}`,
				);
			}
			return { reviews, outcome: { candidate: returned.candidate } };
		} catch (error) {
			return { reviews, outcome: { error } };
		}
	} finally {
		await rm(root, { recursive: true, force: true });
	}
}

/**
 * Run the bundled program `slug` and return the input of the one review it asks for. It fails unless
 * the program asks exactly one review and returns that review's result unchanged.
 */
export async function runBundled(slug: string, inputs: RunInputs = {}): Promise<ReviewOptions> {
	const { review } = await runBundledReview(slug, inputs);
	return review;
}

export async function runBundledReview(
	slug: string,
	inputs: RunInputs = {},
): Promise<BundledReview> {
	const { reviews, outcome } = await runBundledProgram(slug, inputs);
	if ("error" in outcome) {
		throw outcome.error;
	}
	const [review, ...more] = reviews;
	if (review === undefined || more.length > 0) {
		throw new Error(`${slug} asked ${reviews.length} reviews; a bundled program asks exactly one`);
	}
	if (!isDeepStrictEqual(outcome.candidate, inputs.reviewed ?? REVIEWED)) {
		throw new Error(`${slug} did not return its review's result unchanged`);
	}
	return { review, candidate: outcome.candidate };
}
