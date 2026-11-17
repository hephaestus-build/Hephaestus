/**
 * Writes the composition set as Ichnaea records: `cases.jsonl`, `case-set.json` and `suite.json`.
 * `scripts/feedback-eval-ichnaea.test.ts` fails when they are stale.
 * Usage: node scripts/feedback-eval/ichnaea-generate.ts
 */
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

import { canonicalize, caseDigest, caseSetDigest } from "@hephaestus-build/ichnaea/contracts";

import { parseCompositions } from "./compositions.ts";
import { toIchnaeaCases, WITHHOLD_SLICE } from "./ichnaea-cases.ts";
import { composerSettings } from "./ichnaea-configuration.ts";

/** JSON from a file, unchecked: each caller checks the shape it reads. */
const parseJson = (text: string): unknown => JSON.parse(text);

const here = import.meta.dirname;

/** Model calls the composer may send for one case: its own bound, `panel.callsPerCase` in this checkout's gate.json. */
export const CALLS_PER_TRIAL = composerSettings(path.join(here, "..", "..")).callsPerCase;

/**
 * Each margin is the largest drop that does not matter to the product, registered by the measures' owner before the
 * first no-change run, so no measured noise can move it. REGRESSION needs no margin. The no-change runs give the
 * probability that an unchanged composer passes, which the decision states beside the verdict.
 * docs/contributor/feedback-quality-evals.mdx § Margins.
 */
const registered = (measure: string, margin: number, why: string) => ({
	measure,
	margin,
	marginStatus: "registered",
	marginReason: `${why} Registered by the coordinator for the maintainer on 16 November 2026, before the first no-change run.`,
});

export const PRIMARY = [
	registered(
		"hephaestus/accepted",
		0.1,
		"Up to a 10-point drop in first-pass acceptance costs retries but no trust; more than that degrades delivery noticeably.",
	),
	registered(
		"hephaestus/mechanically-clean",
		0.05,
		"Mechanical defects are visible to every reader and should be near zero; a 5-point rise is a real regression.",
	),
	registered("hephaestus/raises-expected", 0.1, "Missed raises lose value but send nothing wrong."),
	registered(
		"hephaestus/withholds-expected",
		0.05,
		"A wrong send is a trust failure, so the cap is the tightest.",
	),
];

export function suite(
	caseSetDigestValue: string,
	counts: { cases: number; withhold: number },
): Record<string, unknown> {
	return {
		schema: "ichnaea.suite/1",
		id: "composition-set",
		version: "2",
		objective:
			"A change to the feedback composer or its prompts does not make its review decisions worse.",
		construct:
			"The composer's raise and withhold decisions, main's acceptance and mechanical cleanliness, on fixed synthetic cases.",
		decision:
			"Merge, hold or roll back a change to the feedback composer, its prompts or the practices it reads.",
		scope: `The ${counts.cases} cases of the composition set under fixed-cases: on these ${counts.cases} cases, beyond run-to-run noise.`,
		nonCoverage:
			"New inputs, the wording quality of feedback, the developer's practice pages and conversations, and the practice review runtime.",
		estimand: "fixed-cases",
		caseSet: {
			id: "composition-set",
			path: "scripts/feedback-eval/ichnaea/case-set.json",
			digest: caseSetDigestValue,
		},
		replicates: 2,
		replicatesFor: [
			{
				slice: WITHHOLD_SLICE.name,
				value: WITHHOLD_SLICE.value,
				replicates: 3,
				reason: `Withhold correctness applies to ${counts.withhold} cases. A third trial on each raises its power to find a drop, for ${counts.withhold} more model calls per arm.`,
			},
		],
		primary: PRIMARY,
		secondary: ["hephaestus/case-good"],
		// Both arms, every planned trial at its bound. A retry of an endpoint failure is outside the plan: the compose script
		// bounds it with ATTEMPTS and records it in attempts-r<k>.jsonl.
		budget: {
			maxCalls: 2 * (2 * counts.cases + counts.withhold) * CALLS_PER_TRIAL,
			callsPerTrial: CALLS_PER_TRIAL,
		},
		policy: { onRegression: "block", onInconclusive: "report", onEstimateOnly: "report" },
		threats: [
			"The composition set is public and used to develop prompts, so the decision can overfit it.",
			"The cases are synthetic; they stand for the work the composer meets, not a sample of it.",
		],
		measureReviewDate: "2027-05-01",
	};
}

const json = (value: unknown): string => `${JSON.stringify(value, null, "\t")}\n`;

/** The records, as the files hold them. */
export async function records(): Promise<{ cases: string; caseSet: string; suite: string }> {
	const set = parseCompositions(
		parseJson(readFileSync(path.join(here, "compositions.json"), "utf8")),
	);
	const cases = toIchnaeaCases(set);
	const entries = await Promise.all(
		cases.map(async (c) => ({
			id: String(c.id),
			digest: await caseDigest(c),
			pool: "development" as const,
		})),
	);
	const digest = await caseSetDigest(entries);
	const caseSet = {
		schema: "ichnaea.caseSet/1",
		id: "composition-set",
		description: `The ${cases.length} synthetic composition cases of the feedback eval.`,
		digest,
		cases: entries,
	};
	return {
		cases: `${cases.map((c) => canonicalize(c)).join("\n")}\n`,
		caseSet: json(caseSet),
		suite: json(
			suite(digest, {
				cases: cases.length,
				withhold: cases.filter((c) =>
					Object.entries(c.slices ?? {}).some(
						([name, value]) => name === WITHHOLD_SLICE.name && value === WITHHOLD_SLICE.value,
					),
				).length,
			}),
		),
	};
}

if (import.meta.main) {
	const out = await records();
	writeFileSync(path.join(here, "ichnaea", "cases.jsonl"), out.cases);
	writeFileSync(path.join(here, "ichnaea", "case-set.json"), out.caseSet);
	writeFileSync(path.join(here, "ichnaea", "suite.json"), out.suite);
	console.log("wrote the composition set and its suite as Ichnaea records");
}
