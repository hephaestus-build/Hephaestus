import { describe, expect, it } from "vitest";

import type { ProfileChange, ReviewedWorkRef } from "@/api/types.gen";
import type { FeedbackTextSegment } from "@/components/practice-vocabulary/feedback-text";
import { ARTIFACT_KIND } from "@/lib/artifact-kinds";

import {
	composeChangeSentences,
	composeGroupOverview,
	composeOverview,
	EMPTY_OVERVIEW,
} from "./compose-overview";

const AT = new Date("2026-09-09T14:10:00Z");

/** A GitLab merge request, so its noun is the provider's. */
const pullRequest = (n: number): ReviewedWorkRef => ({
	id: String(n),
	kind: ARTIFACT_KIND.pullRequest,
	provider: "GITLAB",
	label: `!${n}`,
	url: `https://gitlab.example/mr/${n}`,
});
/** The sentence as a reader reads it, pills, groups and links and all. */
function readSegment(segment: FeedbackTextSegment): string {
	if (segment.type === "text") {
		return segment.text;
	}
	return segment.type === "work" ? segment.ref.label : segment.name;
}
const plain = (segments: FeedbackTextSegment[] | undefined) =>
	(segments ?? []).map(readSegment).join("");

/** A practice row's bullets, read as one line. */
const plainBullets = (bullets: FeedbackTextSegment[][] | undefined) =>
	(bullets ?? []).map(plain).join(" ");

const change = (
	type: ProfileChange["type"],
	slug: string,
	name: string,
	extra: Partial<ProfileChange> = {},
): ProfileChange => ({
	type,
	at: AT,
	practiceSlug: slug,
	practiceName: name,
	groupSlug: "review-ready-work",
	groupName: "Packaging work for review",
	// A feedback change on the wire always names the piece it is about, and a fall back says what
	// the clean run it emptied was.
	...(type.startsWith("FEEDBACK_") ? { feedbackId: `${slug}-feedback` } : {}),
	...(type === "FEEDBACK_RESET" ? { cleanNeeded: 3 } : {}),
	evidence: [pullRequest(421), pullRequest(425)],
	...extra,
});

type Overview = Parameters<typeof composeOverview>[0];

const overview = (partial: Partial<Overview>): Overview => ({
	...EMPTY_OVERVIEW,
	latestRun: { reviewId: "job-1", at: AT, reviewedWork: pullRequest(425) },
	...partial,
});

describe("composeChangeSentences", () => {
	it("says each subject's one change once, what needs the reader first, and never new feedback twice", () => {
		const sentences = composeChangeSentences([
			change("STANDING_MOVED", "describe-changes", "Describe changes", {
				from: "DEVELOPING",
				to: "STRENGTH",
				direction: "UP",
			}),
			change("TREND_TURNED", "describe-changes", "Describe changes", {
				from: "STABLE",
				to: "IMPROVING",
			}),
			change("FEEDBACK_RESET", "link-issues", "Link issues", {
				feedbackId: "f1",
				cleanNeeded: 3,
				evidence: [pullRequest(7)],
			}),
		]).map(plain);

		expect(sentences).toHaveLength(2);
		expect(sentences[0]).toMatch(/^Link issues /u);
		expect(sentences[1]).toMatch(/^Describe changes /u);
	});

	it("says nothing for a week without changes", () => {
		expect(composeChangeSentences([])).toStrictEqual([]);
	});
});

describe("composeOverview", () => {
	it("has no latest run and no group sentences before the first run", () => {
		const composed = composeOverview(EMPTY_OVERVIEW);
		expect(composed.latestRun).toBeUndefined();
		expect(composed.groupSentences).toStrictEqual({});
	});

	it("keys the table's sentences by every group the overview mentions", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("STANDING_MOVED", "scope", "Scope", {
						groupSlug: "communication",
						groupName: "Communicating in the open",
						from: "MIXED",
						to: "STRENGTH",
					}),
				],
			}),
		);
		expect(composed.latestRun?.reviewId).toBe("job-1");
		expect(composed.groupSentences.communication?.map(plain)).toStrictEqual([
			"Scope moved to Going well.",
		]);
	});
});

describe("a fall back", () => {
	it("says where the clean run fell back to and on which work", () => {
		const sentences = composeGroupOverview(
			overview({
				changes: [
					change("FEEDBACK_RESET", "diff-size", "Keep the diff reviewable in one sitting", {
						evidence: [pullRequest(425)],
					}),
				],
			}),
			"review-ready-work",
		).practiceSentences;
		expect(plainBullets(sentences["diff-size"])).toBe("Back to 0 of 3 clean after !425.");
		expect(sentences["diff-size"]?.flat()).toContainEqual({ type: "work", ref: pullRequest(425) });
	});

	it("says only that the run is empty when the wire did not say how long it is", () => {
		const sentences = composeGroupOverview(
			overview({
				changes: [
					change("FEEDBACK_RESET", "diff-size", "Keep the diff", {
						cleanNeeded: undefined,
						evidence: [pullRequest(425)],
					}),
				],
			}),
			"review-ready-work",
		).practiceSentences;
		expect(plainBullets(sentences["diff-size"])).toBe("Back to no clean work after !425.");
	});
});

describe("dedupe", () => {
	const sentencesOf = (changes: ProfileChange[]) =>
		composeGroupOverview(overview({ changes }), "review-ready-work").practiceSentences;

	it("says no sentence for new feedback: the card says it, so a move up in the same run shows", () => {
		const sentences = sentencesOf([
			change("STANDING_MOVED", "scope", "Scope", { from: "MIXED", to: "STRENGTH" }),
			change("FEEDBACK_NEW", "scope", "Scope"),
		]);
		expect(plainBullets(sentences.scope)).toBe("Moved to Going well after !421 and !425.");
	});

	it("keeps the fall back over new feedback when one practice did both", () => {
		const sentences = sentencesOf([
			change("FEEDBACK_NEW", "scope", "Scope"),
			change("FEEDBACK_RESET", "scope", "Scope", { evidence: [pullRequest(425)] }),
		]);
		expect(plainBullets(sentences.scope)).toBe("Back to 0 of 3 clean after !425.");
	});

	it("keeps a move up and a move down of the same practice to the one with precedence", () => {
		const sentences = sentencesOf([
			change("STANDING_MOVED", "scope", "Scope", { from: "DEVELOPING", to: "MIXED" }),
			change("STANDING_MOVED", "scope", "Scope", {
				from: "MIXED",
				to: "DEVELOPING",
				direction: "DOWN",
			}),
		]);
		expect(plainBullets(sentences.scope)).toBe("Moved to Needs attention after !421 and !425.");
	});
});

/** Changes as the wire carries them for one group: each stamped with the group it is in. */
interface ChangesInGroup {
	groupSlug: string;
	groupName: string;
	changes: ProfileChange[];
}
const inGroup = ({ groupSlug, groupName, changes }: ChangesInGroup): ProfileChange[] =>
	changes.map((entry) => ({ ...entry, groupSlug, groupName }));

/** The group's sentences for the "All practice groups" table, and each practice's own for its level. */
const groupSentences = (group: ChangesInGroup) =>
	composeOverview(overview({ changes: inGroup(group) })).groupSentences[group.groupSlug]?.map(
		plain,
	);
const practiceSentences = (group: ChangesInGroup) =>
	composeGroupOverview(overview({ changes: inGroup(group) }), group.groupSlug).practiceSentences;

describe("group sentences", () => {
	it("say what changed in the group, one sentence per event, what needs the reader first", () => {
		const sentences = groupSentences({
			groupSlug: "review-ready-work",
			groupName: "Packaging work for review",
			changes: [
				change("FEEDBACK_RESOLVED", "describe", "Describe what changed and why"),
				change("FEEDBACK_NEW", "scope", "Scope the change to one concern"),
			],
		});
		// New feedback is the card's to say, not the table's.
		expect(sentences).toStrictEqual(["Describe what changed and why resolved."]);
	});

	it("word a resolution by what resolved it", () => {
		const group = (resolvedBy: ProfileChange["resolvedBy"]): ChangesInGroup => ({
			groupSlug: "review-ready-work",
			groupName: "Packaging work for review",
			changes: [
				change("FEEDBACK_RESOLVED", "describe", "Describe what changed and why", {
					resolvedBy,
					evidence: [pullRequest(428), pullRequest(427), pullRequest(425)],
				}),
			],
		});
		expect(groupSentences(group("WORK"))).toStrictEqual([
			"Describe what changed and why resolved by the work after !428, !427 and !425 came back clean.",
		]);
		expect(groupSentences(group("DEVELOPER"))).toStrictEqual([
			"Describe what changed and why marked as addressed on 9 September.",
		]);
		expect(plainBullets(practiceSentences(group("WORK")).describe)).toBe(
			"Feedback resolved by the work after !428, !427 and !425 came back clean.",
		);
		expect(plainBullets(practiceSentences(group("DEVELOPER")).describe)).toBe(
			"Feedback marked as addressed on 9 September.",
		);
	});

	it("join the practices that made the same move into one bullet, and agree with them", () => {
		const sentences = groupSentences({
			groupSlug: "g",
			groupName: "G",
			changes: [1, 2, 3, 4].map((n) =>
				change("FIRST_OBSERVED", `p-${n}`, `P ${n}`, { evidence: [] }),
			),
		});
		expect(sentences).toStrictEqual(["P 1, P 2, P 3 and P 4 were seen for the first time."]);
	});

	it("split one kind by the transition, so two moves are two bullets", () => {
		const sentences = groupSentences({
			groupSlug: "g",
			groupName: "G",
			changes: [
				change("TREND_TURNED", "a", "A", { to: "IMPROVING", evidence: [] }),
				change("TREND_TURNED", "b", "B", { to: "DECLINING", evidence: [] }),
				change("TREND_TURNED", "c", "C", { to: "IMPROVING", evidence: [] }),
			],
		});
		expect(sentences).toStrictEqual([
			"A and C now show More positive recently.",
			"B now shows More difficulties recently.",
		]);
	});

	it("leave a move only the group made its own bullet, which never repeats the group's name", () => {
		const sentences = groupSentences({
			groupSlug: "g",
			groupName: "G",
			changes: [
				change("FIRST_OBSERVED", "a", "A", { evidence: [] }),
				change("GROUP_MOVED", "", "", { from: "MIXED", to: "STRENGTH", evidence: [] }),
				change("FIRST_OBSERVED", "b", "B", { evidence: [] }),
			],
		});
		// The group moved up, so its bullet comes with the moves up, before the first sightings.
		expect(sentences).toStrictEqual([
			"The group moved to Going well.",
			"A and B were seen for the first time.",
		]);
	});

	it("carry the group on the bullet of the one practice that made the same move", () => {
		const sentences = groupSentences({
			groupSlug: "g",
			groupName: "G",
			changes: [
				change("STANDING_MOVED", "a", "A", { from: "MIXED", to: "STRENGTH", evidence: [] }),
				change("GROUP_MOVED", "", "", { from: "MIXED", to: "STRENGTH", evidence: [] }),
			],
		});
		expect(sentences).toStrictEqual(["A moved to Going well, and the group with it."]);
	});

	it("agree with the practices the group moved with, and leave a different move alone", () => {
		const sentences = groupSentences({
			groupSlug: "g",
			groupName: "G",
			changes: [
				change("STANDING_MOVED", "a", "A", { from: "MIXED", to: "STRENGTH", evidence: [] }),
				change("STANDING_MOVED", "b", "B", { from: "MIXED", to: "STRENGTH", evidence: [] }),
				change("STANDING_MOVED", "c", "C", { from: "DEVELOPING", to: "MIXED", evidence: [] }),
				change("GROUP_MOVED", "", "", { from: "MIXED", to: "STRENGTH", evidence: [] }),
			],
		});
		expect(sentences).toStrictEqual([
			"A and B moved to Going well, and the group with them.",
			"C moved to Mixed feedback.",
		]);
	});

	it("say nothing when nothing changed", () => {
		expect(groupSentences({ groupSlug: "g", groupName: "G", changes: [] })).toBeUndefined();
	});
});

describe("composeGroupOverview", () => {
	it("gives each practice of the group its own sentence", () => {
		const composed = composeGroupOverview(
			overview({
				changes: [
					change("FEEDBACK_NEW", "scope", "Scope the change to one concern"),
					change("FEEDBACK_RESOLVED", "describe", "Describe what changed and why", {
						evidence: [],
					}),
					change("STANDING_MOVED", "diff-size", "Keep the diff reviewable", {
						from: "MIXED",
						to: "DEVELOPING",
						direction: "DOWN",
						evidence: [pullRequest(423)],
					}),
					// Another group's change stays out of this group's level.
					change("FIRST_OBSERVED", "other", "Other", { groupSlug: "other", groupName: "Other" }),
				],
			}),
			"review-ready-work",
		);
		// New feedback leaves the row without a sentence: the pattern label and the card say it.
		expect(composed.practiceSentences.scope).toBeUndefined();
		expect(plainBullets(composed.practiceSentences.describe)).toBe("Feedback resolved.");
		expect(plainBullets(composed.practiceSentences["diff-size"])).toBe(
			"Moved to Needs attention after !423.",
		);
		expect(composed.practiceSentences.other).toBeUndefined();
	});

	it("is empty for a group the overview does not mention", () => {
		const composed = composeGroupOverview(overview({}), "other");
		expect(composed.practiceSentences).toStrictEqual({});
	});
});

describe("practice sentences", () => {
	it("say how a practice was first seen and what its trend shows", () => {
		const sentences = practiceSentences({
			groupSlug: "g",
			groupName: "G",
			changes: [
				change("FIRST_OBSERVED", "first", "First", { to: "STRENGTH", evidence: [pullRequest(1)] }),
				change("TREND_TURNED", "trend", "Trend", { to: "DECLINING", evidence: [] }),
			],
		});
		// A first sighting names the work it was seen on, never the standing it landed at: that is
		// the badge on the same row.
		expect(plainBullets(sentences.first)).toBe("Seen for the first time on !1.");
		expect(plainBullets(sentences.trend)).toBe("Now shows More difficulties recently.");
	});

	it("say a practice was seen for the first time even when the wire names no work", () => {
		const sentences = practiceSentences({
			groupSlug: "g",
			groupName: "G",
			changes: [change("FIRST_OBSERVED", "first", "First", { evidence: [] })],
		});
		expect(plainBullets(sentences.first)).toBe("Seen for the first time.");
	});
});
