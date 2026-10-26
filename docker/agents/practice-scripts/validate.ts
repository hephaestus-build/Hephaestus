#!/usr/bin/env node
import { mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { parseArgs } from "node:util";

import { parseDiff } from "./kits/scm/diff-parser.ts";
import { DIFF_SOURCE } from "./kits/scm/sources.ts";
import type { DiffFile } from "./kits/scm/types.ts";
import {
	parseCandidate,
	PRACTICE_SLUG,
	type Candidate,
	type ReviewOptions,
} from "./lib/core/contract.ts";
import { EvidenceReader, noReceipts, type EvidenceSources } from "./lib/core/evidence-reader.ts";
import type { JobFiles } from "./lib/core/ipc.ts";
import { isJsonObject } from "./lib/core/json.ts";
import { renderReviewInput, resolveLeads } from "./lib/core/leads.ts";
import { ModelBudget } from "./lib/core/models-host.ts";
import { reasonOf, UnratedError, type UnratedReason } from "./lib/core/practice-script.ts";
import {
	notReached,
	requiredSources,
	runPracticeProgram,
	type PracticeProgram,
	type ProgramOutcome,
} from "./lib/core/program-host.ts";
import { parseInputs } from "./lib/core/script-inputs.ts";
import { stagePracticeScripts } from "./stage.ts";

const USAGE =
	"Usage: node validate.ts --script <file.ts> --repo <clone> [--diff <patch>] --inputs <json> [--review <candidate.json>] [--out <dir>]";

const DEADLINE_MS = 600_000;
/** Where citations name the context folder and the checkout, as the sandbox stages them. */
const CONTEXT_REFERENCE = "context";
const TREE_SOURCE_PATH = "repos/reviewed/.git/HEAD";

function stop(problem: string): never {
	console.error(problem);
	process.exit(2);
}

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));
const oneLine = (text: string) => text.replaceAll(/\s+/gu, " ").trim();

async function read(option: string, file: string): Promise<string> {
	try {
		return await readFile(file, "utf8");
	} catch (error) {
		return stop(`--${option}: ${messageOf(error)}`);
	}
}

async function json(option: string, file: string): Promise<unknown> {
	const text = await read(option, file);
	try {
		return JSON.parse(text);
	} catch (error) {
		return stop(`--${option}: ${file} is not JSON: ${messageOf(error)}`);
	}
}

async function requireFolder(option: string, dir: string): Promise<void> {
	const found = await stat(dir).catch(() => undefined);
	if (found?.isDirectory() !== true) {
		stop(`--${option}: ${dir} is not a folder`);
	}
}

function parsedArgs() {
	try {
		return parseArgs({
			args: process.argv.slice(2),
			options: {
				script: { type: "string" },
				repo: { type: "string" },
				diff: { type: "string" },
				inputs: { type: "string" },
				review: { type: "string" },
				out: { type: "string" },
			},
		}).values;
	} catch (error) {
		// Node's message can add a sentence about arguments without an option; the usage line replaces it.
		return stop(`${messageOf(error).replace(/\. .*$/su, "")}. ${USAGE}`);
	}
}

const { script, repo, out, diff, inputs: inputsFile, review: reviewFile } = parsedArgs();
if (script === undefined || repo === undefined || inputsFile === undefined) {
	stop(`--script, --repo and --inputs are required. ${USAGE}`);
}
const slug = path.basename(script, ".ts");
if (!PRACTICE_SLUG.test(slug)) {
	stop(
		`${script}: the file name, less .ts, is the practice's identifier: lowercase letters, digits and hyphens`,
	);
}
await requireFolder("repo", repo);
const source = await read("script", script);
let change: Map<string, DiffFile> | null = null;
if (diff !== undefined) {
	change = parseDiff(await read("diff", diff));
	if (change.size === 0) {
		stop(`--diff: ${diff} holds no unified diff`);
	}
}
let reviewed: Candidate | undefined;
if (reviewFile !== undefined) {
	const parsed = parseCandidate(await json("review", reviewFile));
	if (!parsed.ok) {
		stop(
			`--review: ${reviewFile} is not a result: ${parsed.reasonCode}: ${oneLine(parsed.message)}`,
		);
	}
	reviewed = parsed.candidate;
}

const started = Date.now();
const admittedAt = new Date(started).toISOString();
const stagedInputs = await json("inputs", inputsFile);
if (out !== undefined) {
	await mkdir(out, { recursive: true }).catch((error: unknown) => {
		stop(`--out: ${messageOf(error)}`);
	});
}

// Without `--out`, each pointer names the file that this command prints. The section of the n-th
// review is `<slug>.review-<n>.md`; a review with an empty input has none.
const jsonPath = path.join(out ?? "", `${slug}.json`);
const sectionPath = (n: number) => path.join(out ?? "", `${slug}.review-${n}.md`);

interface AskedReview {
	/** The input, each lead with the quote that the host resolved. */
	input: ReviewOptions;
	/** The section that the review session reads; none when the input is empty. */
	section?: string;
	candidate?: Candidate;
	unrated?: { reason: UnratedReason; message: string };
}
const reviews: AskedReview[] = [];

const staged = await stagePracticeScripts({ [slug]: source }, {});
let outcome: ProgramOutcome;
try {
	const program: PracticeProgram = {
		script: { path: path.join(staged.practices, `${slug}.ts`), source: Buffer.from(source) },
	};
	const parsed = parseInputs(slug, stagedInputs);
	if ("error" in parsed) {
		outcome = {
			...notReached(program, "unavailable"),
			status: "failed",
			error: `the review's inputs are not valid: ${parsed.error}`,
			durationMs: 0,
		};
	} else {
		const { inputs } = parsed;
		const { item } = inputs;
		let head: string | null = null;
		if (isJsonObject(item)) {
			if (typeof item.commitId === "string") {
				head = item.commitId;
			} else if (typeof item.commit_sha === "string") {
				head = item.commit_sha;
			}
		}

		const captured = change === null || head === null ? null : { base: "", head, files: change };
		const sources: EvidenceSources = {
			repo: path.resolve(repo),
			headRevision: head,
			treeSourceFile: TREE_SOURCE_PATH,
			contextDir: null,
			contextReference: CONTEXT_REFERENCE,
			change: captured,
			sourceKindOf: () => undefined,
		};
		const files: JobFiles | null =
			head === null ? null : { head: { commit: head }, change: captured };
		const receipts = noReceipts();
		outcome = await runPracticeProgram(
			program,
			{
				slug,
				inputs,
				files,
				now: admittedAt,
				deadline: started + DEADLINE_MS,
			},
			{
				reader: new EvidenceReader(sources, receipts),
				models: {},
				budget: new ModelBudget(0, 1),
				review: async (options) => {
					const asked: AskedReview = { input: options };
					reviews.push(asked);
					try {
						const leads = await resolveLeads(options.leads ?? [], sources);
						asked.input = leads.length === 0 ? options : { ...options, leads };
						const prepared =
							leads.length > 0 ||
							Object.keys(options.facts ?? {}).length > 0 ||
							(options.directions ?? []).length > 0 ||
							(options.context ?? []).length > 0 ||
							(options.limitations ?? []).length > 0;
						if (prepared) {
							asked.section = renderReviewInput(
								options,
								leads,
								CONTEXT_REFERENCE,
								`\`${jsonPath}\``,
							);
						}
					} catch (error) {
						asked.unrated = { reason: reasonOf(error), message: messageOf(error) };
						throw error;
					}
					if (reviewed === undefined) {
						const message =
							"this tool opens no review session: give --review <candidate.json> for the result that review() returns";
						asked.unrated = { reason: "unavailable", message };
						throw new UnratedError("unavailable", message);
					}
					asked.candidate = reviewed;
					return reviewed;
				},
				signal: new AbortController().signal,
				log: (line) => {
					console.error(line);
				},
				receipts,
				// The tool captures no records, so the change is the only source a practice can require here.
				requiredSources: requiredSources(
					[DIFF_SOURCE],
					captured === null ? [] : [[`${CONTEXT_REFERENCE}/change.json`, DIFF_SOURCE]],
				),
			},
		);
	}
} finally {
	await rm(staged.root, { recursive: true, force: true });
}

const { candidate, error } = outcome;
const report = `${JSON.stringify(
	{
		practiceSlug: slug,
		program: outcome.program,
		status: outcome.status,
		stopReason: outcome.stopReason,
		...(error === undefined ? {} : { error }),
		models: outcome.models,
		calls: outcome.calls,
		coverageReached: outcome.coverageReached,
		durationMs: outcome.durationMs,
		reviews: reviews.map(({ input, section, candidate: returned, unrated }, index) => ({
			input,
			section: section === undefined ? null : sectionPath(index + 1),
			...(returned === undefined ? {} : { candidate: returned }),
			...(unrated === undefined ? {} : { unrated }),
		})),
		...(candidate === undefined ? {} : { candidate }),
	},
	null,
	2,
)}\n`;
const printed = [`==> ${jsonPath} <==\n${report}`];
if (out !== undefined) {
	await writeFile(jsonPath, report);
}
for (const [index, { section }] of reviews.entries()) {
	if (section !== undefined) {
		printed.push(`==> ${sectionPath(index + 1)} <==\n${section}`);
		if (out !== undefined) {
			await writeFile(sectionPath(index + 1), section);
		}
	}
}
console.log(printed.join("\n"));
console.error(
	candidate === undefined
		? `${slug}: ${outcome.status} (${outcome.stopReason ?? "no result"})${error === undefined ? "" : `: ${oneLine(error)}`}`
		: `${slug}: ok: the program returned a ${candidate.outcome} result`,
);
process.exitCode = candidate === undefined ? 1 : 0;
