import assert from "node:assert/strict";
import { test } from "node:test";

import {
	type Bucket,
	DEVELOPERS,
	READER_CARDS,
	READER_RUNS,
	SPLITS,
	type SeedRun,
	bucketOf,
	readerCards,
} from "./lib/practices-demo.ts";

/** A part of a split shows only with this many developers in it, the reader counted. */
const SMALLEST_PART = 4;

/** Which group each practice the reader is reviewed on sits in, as the bundled catalog has it. */
const GROUP_OF: Record<string, string> = {
	"validates-and-escapes-untrusted-input": "secure-by-default-changes",
	"changes-dependencies-deliberately": "secure-by-default-changes",
	"scope-one-reviewable-change": "review-ready-work",
	"describe-what-and-why": "review-ready-work",
	"honours-linked-issue-acceptance-criteria": "review-ready-work",
	"ready-and-traceable-handoff": "review-ready-work",
	"commit-subjects-explain-each-change": "review-ready-work",
	"leaves-useful-specific-review-comments": "constructive-code-review",
	"ships-tests-with-the-change": "testing-discipline",
	"issue-has-checkable-outcome": "actionable-issue-authoring",
	"issue-states-an-actionable-problem": "actionable-issue-authoring",
};

interface Reading {
	work: string;
	at: number;
	met: boolean;
}

/** The reader's decided readings of one practice, oldest first, each piece of work at its newest run. */
function readings(practice: string): Reading[] {
	const newest = new Map<string, Reading>();
	for (const run of READER_RUNS) {
		for (const observation of run.observations) {
			if (observation.practice !== practice || !["MET", "NOT_MET"].includes(observation.outcome)) {
				continue;
			}
			const work = `${run.artifact.kind}#${run.artifact.number}`;
			const at = Date.parse(run.at);
			if ((newest.get(work)?.at ?? -Infinity) < at) {
				newest.set(work, { work, at, met: observation.outcome === "MET" });
			}
		}
	}
	return [...newest.values()].toSorted((a, b) => a.at - b.at);
}

/** `StandingScale.classify`: above 0.8 a strength, from 0.37 mixed, below it needs attention. */
function classify(share: number): Bucket {
	if (share > 0.8) {
		return "well";
	}
	return share >= 0.37 ? "mixed" : "needs";
}

/**
 * The reader's standing per group, read the way the server reads it: the newest four pieces of work
 * weighted by recency, a practice's share averaged over the group (`StandingScale`).
 */
function readerBuckets(): Map<string, Bucket> {
	const weights = [1, 0.4, 0.16, 0.064];
	const full = weights.reduce((sum, weight) => sum + weight, 0);
	const shares = new Map<string, number[]>();
	for (const [practice, group] of Object.entries(GROUP_OF)) {
		const decided = readings(practice).toReversed().slice(0, weights.length);
		if (decided.length === 0) {
			continue;
		}
		const missed = decided.reduce(
			(sum, reading, age) => sum + (reading.met ? 0 : (weights[age] ?? 0)),
			0,
		);
		shares.set(group, [...(shares.get(group) ?? []), 1 - missed / full]);
	}
	const buckets = new Map<string, Bucket>();
	for (const group of Object.keys(SPLITS)) {
		const groupShares = shares.get(group);
		if (groupShares === undefined) {
			buckets.set(group, "none");
			continue;
		}
		const share = groupShares.reduce((sum, value) => sum + value, 0) / groupShares.length;
		buckets.set(group, classify(share));
	}
	return buckets;
}

void test("each group splits the synthetic developers by its numbers", () => {
	for (const [groupIndex, [group, split]] of Object.entries(SPLITS).entries()) {
		const counts = { needs: 0, mixed: 0, well: 0, none: 0 };
		for (let index = 0; index < DEVELOPERS; index += 1) {
			counts[bucketOf(split, groupIndex, index)] += 1;
		}
		assert.deepEqual(Object.values(counts), split, group);
	}
});

void test("the reader's groups take every standing", () => {
	const buckets = readerBuckets();
	assert.equal(buckets.get("testing-discipline"), "needs");
	assert.equal(buckets.get("review-ready-work"), "mixed");
	assert.equal(buckets.get("secure-by-default-changes"), "well");
	assert.equal(buckets.get("code-craftsmanship"), "none");
});

void test("only testing discipline and issue traceability are held back", () => {
	const buckets = readerBuckets();
	const heldBack = Object.entries(SPLITS)
		.filter(([group, [needs, mixed, well]]) => {
			const reader = buckets.get(group);
			const parts = { needs, mixed, well };
			return Object.entries(parts).some(
				([bucket, count]) => count + (reader === bucket ? 1 : 0) < SMALLEST_PART,
			);
		})
		.map(([group]) => group);
	assert.deepEqual(heldBack, ["testing-discipline", "issue-traceability-and-lifecycle"]);
});

type State = "new" | "read" | "disputed" | "resolved by the work" | "addressed" | "not applicable";

/** What the page shows a card as: open, or closed by whichever came first, the work or the reader. */
function stateOf(card: (typeof READER_CARDS)[number]): State {
	let clean = 0;
	let byWork = Infinity;
	for (const reading of readings(card.practice)) {
		if (reading.at <= Date.parse(card.createdAt)) {
			continue;
		}
		clean = reading.met ? clean + 1 : 0;
		if (clean === 3) {
			byWork = reading.at;
			break;
		}
	}
	const resolution = card.response?.resolution;
	const byReader =
		card.response !== undefined && (resolution === "ADDRESSED" || resolution === "NOT_APPLICABLE")
			? Date.parse(card.response.at)
			: Infinity;
	if (byWork < byReader) {
		return "resolved by the work";
	}
	if (byReader < Infinity) {
		return resolution === "ADDRESSED" ? "addressed" : "not applicable";
	}
	if (resolution === "DISPUTED") {
		return "disputed";
	}
	return card.deliveredAt === undefined ? "new" : "read";
}

void test("the reader's cards take every state a card shows", () => {
	assert.deepEqual(
		new Set(READER_CARDS.map(stateOf)),
		new Set<State>([
			"new",
			"read",
			"disputed",
			"resolved by the work",
			"addressed",
			"not applicable",
		]),
	);
});

void test("each card stands on problems the reader's runs recorded on its practice", () => {
	const jobIds = new Map(READER_RUNS.map((run) => [run.key, `job-${run.key}`]));
	const outcomes = new Map<string, string>();
	const observationIds = new Map(
		READER_RUNS.flatMap((run: SeedRun) =>
			run.observations.map((observation) => {
				const key = `${run.key}/${observation.practice}`;
				outcomes.set(key, observation.outcome);
				return [key, key] as const;
			}),
		),
	);
	const cards = readerCards(5, jobIds, observationIds);
	assert.equal(cards.length, READER_CARDS.length);
	assert.equal(new Set(cards.map((card) => card.id)).size, cards.length);
	for (const card of cards) {
		assert.ok(
			card.evidence.every((key) => outcomes.get(key) === "NOT_MET"),
			card.practiceSlug,
		);
		assert.ok(card.response === null || card.deliveredAt !== null, card.practiceSlug);
		assert.ok(
			card.response?.resolution !== "DISPUTED" || card.response.explanation !== null,
			card.practiceSlug,
		);
	}
});
