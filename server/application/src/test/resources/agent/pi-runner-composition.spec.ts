import assert from "node:assert/strict";
import test from "node:test";

import {
	type Channel,
	type ComposedFeedbackEnvelope,
	type ComposedFeedbackUnit,
	notReachedNote,
	undeliverableUnits,
	validateFeedbackEvidence,
	sameLinesNote,
} from "../../../main/resources/agent/pi-runner-composition.ts";

const supersede = (threadKey: string): ComposedFeedbackUnit => ({
	action: "SUPERSEDE",
	channel: "IN_CONTEXT",
	practiceSlug: "writes-focused-pull-requests",
	supersedesThreadKey: threadKey,
});

const target = (threadKey: string, channel: Channel = "IN_CONTEXT") => ({
	threadKey,
	channel,
	practiceSlug: "writes-focused-pull-requests",
});

void test("an envelope that lists the threads its units supersede delivers all of them", () => {
	const envelope = {
		preparedTargets: [target("t-1"), target("t-2")],
		units: [supersede("t-1"), supersede("t-2")],
	};

	assert.deepEqual(undeliverableUnits(envelope), []);
});

void test("a superseding unit is undeliverable when the envelope lists no threads", () => {
	const envelope = { preparedTargets: [], units: [supersede("t-1")] };

	assert.deepEqual(undeliverableUnits(envelope), [supersede("t-1")]);
});

void test("only the unit naming an unlisted thread is undeliverable", () => {
	const envelope = {
		preparedTargets: [target("t-1")],
		units: [supersede("t-1"), supersede("t-9")],
	};

	assert.deepEqual(undeliverableUnits(envelope), [supersede("t-9")]);
});

void test("a thread cannot be superseded from another lane", () => {
	const envelope = { preparedTargets: [target("t-1", "IN_APP")], units: [supersede("t-1")] };

	assert.deepEqual(undeliverableUnits(envelope), [supersede("t-1")]);
});

void test("units that supersede nothing are unaffected by an empty thread list", () => {
	const envelope: ComposedFeedbackEnvelope = {
		preparedTargets: [],
		units: [
			{ action: "NEW", channel: "IN_APP", practiceSlug: "p" },
			{ action: "WITHHOLD", channel: "IN_CHAT", practiceSlug: "p", withholdReason: "ALREADY_SAID" },
		],
	};

	assert.deepEqual(undeliverableUnits(envelope), []);
});

void test("an envelope missing the fields entirely reports nothing rather than throwing", () => {
	assert.deepEqual(undeliverableUnits({}), []);
	assert.deepEqual(undeliverableUnits(undefined), []);
});

void test("one feedback intervention may synthesize related practice observations", () => {
	const practices = new Map([
		["primary-1", { practiceSlug: "review-loop", outcome: "NOT_MET" }],
		["support-1", { practiceSlug: "handoff", outcome: "MET" }],
	]);

	assert.equal(
		validateFeedbackEvidence(
			"review-loop",
			["primary-1", "support-1"],
			practices,
			"IN_CHAT",
			"NEW",
		),
		null,
	);
	assert.match(
		validateFeedbackEvidence("review-loop", ["support-1"], practices, "IN_CHAT", "NEW") ?? "",
		/primary practice 'review-loop'/u,
	);
	assert.match(
		validateFeedbackEvidence("review-loop", ["missing"], practices, "IN_CHAT", "NEW") ?? "",
		/does not name an admitted observation/u,
	);
});

void test("a related negative cannot anchor private feedback on a positive primary practice", () => {
	const observations = new Map([
		["confirmed-criteria", { practiceSlug: "acceptance-criteria", outcome: "MET" }],
		["unconfirmed-outcome", { practiceSlug: "issue-outcome", outcome: "NOT_MET" }],
	]);
	const basedOn = ["confirmed-criteria", "unconfirmed-outcome"];

	for (const channel of ["IN_CHAT", "IN_APP"] as const) {
		assert.match(
			validateFeedbackEvidence("acceptance-criteria", basedOn, observations, channel, "NEW") ?? "",
			/NOT_MET for the primary practice 'acceptance-criteria'/u,
		);
		assert.equal(
			validateFeedbackEvidence("issue-outcome", basedOn, observations, channel, "NEW"),
			null,
		);
	}
});

void test("feedback on the work may reinforce a strength but cannot withhold it", () => {
	const observations = new Map([["strength", { practiceSlug: "review-loop", outcome: "MET" }]]);
	for (const action of ["NEW", "SUPERSEDE"] as const) {
		assert.equal(
			validateFeedbackEvidence("review-loop", ["strength"], observations, "IN_CONTEXT", action),
			null,
		);
	}
	assert.match(
		validateFeedbackEvidence("review-loop", ["strength"], observations, "IN_CONTEXT", "WITHHOLD") ??
			"",
		/NOT_MET for the primary practice/u,
	);
});

void test("a review that reached every practice says nothing about coverage", () => {
	assert.equal(notReachedNote([]), "");
});

void test("a review names the practices it never settled and forbids a verdict on them", () => {
	const one = notReachedNote(["ships-tests-with-the-change"]);
	assert.match(one, /one of its practices: ships-tests-with-the-change\./u);
	assert.match(one, /Say nothing about them, for or against/u);
	assert.match(one, /do not describe this review as complete/u);

	const many = notReachedNote(["ships-tests-with-the-change", "describe-what-and-why"]);
	assert.match(many, /2 of its practices: ships-tests-with-the-change, describe-what-and-why\./u);
});

const cite = (path: string, startLine: number) => ({ path, startLine });

void test("negatives that quote the same line are named as one likely event; one practice alone is not", () => {
	const note = sameLinesNote([
		{
			id: "a",
			practiceSlug: "scope-one-reviewable-change",
			outcome: "NOT_MET",
			citations: [cite("inputs/context/metadata.json", 18)],
		},
		{
			id: "b",
			practiceSlug: "ready-and-traceable-handoff",
			outcome: "NOT_MET",
			citations: [cite("inputs/context/metadata.json", 18)],
		},
		{
			id: "c",
			practiceSlug: "ships-tests-with-the-change",
			outcome: "NOT_MET",
			citations: [cite("App/Model.swift", 9)],
		},
		{
			id: "d",
			practiceSlug: "ships-tests-with-the-change",
			outcome: "NOT_MET",
			citations: [cite("App/Model.swift", 9)],
		},
		// A MET on the same line is not part of an event to write about.
		{
			id: "e",
			practiceSlug: "describe-what-and-why",
			outcome: "MET",
			citations: [cite("inputs/context/metadata.json", 18)],
		},
	]);
	assert.match(note, /^NOT_MET measurements that quote the same line/u);
	assert.match(
		note,
		/- inputs\/context\/metadata\.json:18: scope-one-reviewable-change \(a\), ready-and-traceable-handoff \(b\)\n/u,
	);
	assert.doesNotMatch(note, /App\/Model\.swift/u);
	assert.equal(
		sameLinesNote([{ id: "a", practiceSlug: "x", outcome: "NOT_MET", citations: [] }]),
		"",
	);
});
