import assert from "node:assert/strict";
import test from "node:test";

import {
	type Channel,
	type ComposedFeedbackEnvelope,
	type ComposedFeedbackUnit,
	notReachedNote,
	undeliverableUnits,
	validateFeedbackEvidence,
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
		["primary-1", "review-loop"],
		["support-1", "handoff"],
	]);

	assert.equal(
		validateFeedbackEvidence("review-loop", ["primary-1", "support-1"], practices),
		null,
	);
	assert.match(
		validateFeedbackEvidence("review-loop", ["support-1"], practices) ?? "",
		/primary practice 'review-loop'/,
	);
	assert.match(
		validateFeedbackEvidence("review-loop", ["missing"], practices) ?? "",
		/does not name an admitted observation/,
	);
});

void test("a review that reached every practice says nothing about coverage", () => {
	assert.equal(notReachedNote([]), "");
});

void test("a review names the practices it never settled and forbids a verdict on them", () => {
	const one = notReachedNote(["ships-tests-with-the-change"]);
	assert.match(one, /one of its practices: ships-tests-with-the-change\./);
	assert.match(one, /Say nothing about them, for or against/);
	assert.match(one, /do not describe this review as complete/);

	const many = notReachedNote(["ships-tests-with-the-change", "describe-what-and-why"]);
	assert.match(many, /2 of its practices: ships-tests-with-the-change, describe-what-and-why\./);
});
