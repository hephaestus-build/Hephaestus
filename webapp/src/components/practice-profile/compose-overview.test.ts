import { describe, expect, it } from "vitest";

import type { HeldPractice, ProfileChange, ReviewedWorkRef } from "@/api/types.gen";
import { ATTENTION_DEFS } from "@/components/practice-vocabulary/attention-defs";
import type { FeedbackTextSegment } from "@/components/practice-vocabulary/feedback-text";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { ARTIFACT_KIND } from "@/lib/artifact-kinds";

import {
	composeGroupOverview,
	composeNextStep,
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
const githubPullRequest = (n: number): ReviewedWorkRef => ({
	id: String(n),
	kind: ARTIFACT_KIND.pullRequest,
	provider: "GITHUB",
	label: `#${n}`,
	url: `https://github.com/example/repo/pull/${n}`,
});
const thread = (label: string): ReviewedWorkRef => ({
	id: label,
	kind: ARTIFACT_KIND.conversationThread,
	label,
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
	// the clean run it emptied was: a row in "What needs your attention" is built from both.
	...(type.startsWith("FEEDBACK_") ? { feedbackId: `${slug}-feedback` } : {}),
	...(type === "FEEDBACK_RESET" ? { cleanNeeded: 3 } : {}),
	evidence: [pullRequest(421), pullRequest(425)],
	...extra,
});

const held = (slug: string, name: string, cleanWork: number): HeldPractice => ({
	practiceSlug: slug,
	practiceName: name,
	groupSlug: "review-ready-work",
	holdsAs: "every reviewer comment gets a visible answer",
	cleanWork,
	workKind: ARTIFACT_KIND.pullRequest,
	since: new Date("2026-08-20T09:00:00Z"),
});

type Overview = Parameters<typeof composeOverview>[0];

const overview = (partial: Partial<Overview>): Overview => ({
	...EMPTY_OVERVIEW,
	latestRun: { jobId: "job-1", at: AT, reviewedWork: pullRequest(425) },
	...partial,
});

/** `n` changes cycling through the kinds, over a catalog of names. */
function synthetic(n: number): ProfileChange[] {
	const kinds: ProfileChange["type"][] = [
		"STANDING_MOVED",
		"FEEDBACK_NEW",
		"TREND_TURNED",
		"STANDING_MOVED",
		"FIRST_OBSERVED",
		"GROUP_MOVED",
	];
	return Array.from({ length: n }, (_, index) => {
		const type = kinds[index % kinds.length] ?? "STANDING_MOVED";
		const from = index % 2 === 0 ? "MIXED" : "DEVELOPING";
		return change(type, `practice-${index}`, `Practice ${index}`, {
			groupSlug: `group-${index % 3}`,
			groupName: `Group ${index % 3}`,
			from,
			to: type === "TREND_TURNED" ? "IMPROVING" : "STRENGTH",
			direction: "UP",
		});
	});
}

describe("the paragraph's count", () => {
	it("never mixes a digit with a word inside one clause", () => {
		const { changed } = composeOverview(overview({ changes: synthetic(16) }));
		const clause = plain(changed)
			.split(". ")
			.find((part) => part.includes("changed"));
		expect(clause).toMatch(/\d+ more practices and \d+ groups? changed/u);
		expect(clause).not.toMatch(/\b(?:one|two|three|four|five|six|seven|eight|nine)\b/u);
	});
});

describe("a sentence's opening", () => {
	it("capitalises a generated word", () => {
		const composed = composeOverview(
			overview({
				changes: [1, 2].map((n) =>
					change("STANDING_MOVED", `up-${n}`, `Up ${n}`, { from: "MIXED", to: "STRENGTH" }),
				),
			}),
		);
		expect(plain(composed.changed)).toBe("Two practices changed since the latest run.");
	});

	it("leaves a practice name alone, as the link it is", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("STANDING_MOVED", "scope", "scope", {
						from: "STRENGTH",
						to: "MIXED",
						direction: "DOWN",
					}),
				],
			}),
		);
		expect(composed.changed[0]).toStrictEqual({ type: "practice", slug: "scope", name: "scope" });
	});
});

describe("composeOverview", () => {
	it("says nothing at all before the first run", () => {
		const composed = composeOverview(EMPTY_OVERVIEW);
		expect(composed.latestRun).toBeUndefined();
		expect(composed.holdingUp).toStrictEqual([]);
		expect(composed.changed).toStrictEqual([]);
		expect(composed.rest).toStrictEqual([]);
		expect(composed.reviewedWork).toStrictEqual([]);
	});

	it("says that nothing moved when a run found no change", () => {
		const composed = composeOverview(overview({}));
		expect(plain(composed.changed)).toBe("Nothing moved since the latest run.");
		expect(composed.restCount).toBe(0);
	});

	it("counts first sightings as changes, not as moves, when it names nothing", () => {
		const composed = composeOverview(
			overview({
				changes: [1, 2].map((n) => change("FIRST_OBSERVED", `first-${n}`, `First ${n}`)),
			}),
		);
		expect(plain(composed.changed)).toBe("Two practices changed since the latest run.");
		expect(composed.restCount).toBe(2);
	});

	it("reads a move with no direction — into or out of a standing no review settled — as no slip", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("STANDING_MOVED", "scope", "Scope", { from: "STRENGTH", to: "NOT_OBSERVED" }),
				],
			}),
		);
		expect(plain(composed.changed)).toBe("One practice changed since the latest run.");
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual(["Moved up"]);
	});

	it("gives new feedback a row rather than a sentence, and the paragraph nothing to say", () => {
		const composed = composeOverview(
			overview({
				changes: [change("FEEDBACK_NEW", "scope-one-concern", "Scope the change to one concern")],
			}),
		);
		expect(composed.needsAttention).toHaveLength(1);
		const [row] = composed.needsAttention;
		expect(row?.practiceName).toBe("Scope the change to one concern");
		expect(row?.feedbackId).toBe("scope-one-concern-feedback");
		expect(plain(row?.sentence)).toBe("There is new feedback, seen on !421 and !425");
		expect(row?.sentence).toContainEqual({ type: "work", ref: pullRequest(421) });
		// The block said it, so the paragraph neither repeats it nor counts it.
		expect(plain(composed.changed)).toBe("");
		expect(composed.rest).toStrictEqual([]);
	});

	it("names one slip and counts the rest, with the new feedback in a row — three events", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("FEEDBACK_NEW", "scope", "Scope the change to one concern"),
					change("STANDING_MOVED", "diff-size", "Keep the diff reviewable in one sitting", {
						from: "MIXED",
						to: "DEVELOPING",
						direction: "DOWN",
						evidence: [pullRequest(423)],
					}),
					change("STANDING_MOVED", "linked-issue", "Link the issue the change closes", {
						from: "MIXED",
						to: "STRENGTH",
					}),
				],
			}),
		);
		expect(composed.needsAttention.map((row) => row.practiceName)).toStrictEqual([
			"Scope the change to one concern",
		]);
		expect(plain(composed.changed)).toBe(
			"Keep the diff reviewable in one sitting moved to Needs attention after !423. " +
				"One more practice changed as well.",
		);
		expect(composed.restCount).toBe(1);
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual(["Moved up"]);
		expect(plain(composed.rest[0]?.segments)).toBe(
			"Link the issue the change closes moved to Going well after !421 and !425.",
		);
	});

	it("keeps the paragraph to one slip and one count, the rows to two — nine events", () => {
		const changes = [
			...[1, 2, 3].map((n) => change("FEEDBACK_NEW", `new-${n}`, `New ${n}`)),
			...[1, 2].map((n) =>
				change("STANDING_MOVED", `down-${n}`, `Down ${n}`, {
					from: "STRENGTH",
					to: "MIXED",
					direction: "DOWN",
				}),
			),
			...[1, 2, 3].map((n) =>
				change("STANDING_MOVED", `up-${n}`, `Up ${n}`, { from: "MIXED", to: "STRENGTH" }),
			),
			change("GROUP_MOVED", "unused", "unused", {
				groupSlug: "communication",
				groupName: "Communicating in the open",
				from: "MIXED",
				to: "STRENGTH",
			}),
		];
		const composed = composeOverview(overview({ changes }));
		// Two pieces of new feedback take a row each; the further one, the other slip and the moves
		// up are one count, since the paragraph never adds bad news up sentence by sentence.
		expect(composed.needsAttention.map((row) => row.practiceName)).toStrictEqual([
			"New 1",
			"New 2",
		]);
		expect(plain(composed.changed)).toBe(
			"Down 1 moved to Mixed feedback after !421 and !425. " +
				"Five more practices and one group changed as well.",
		);
		// What the paragraph counted is exactly what unfolds, the named events left out.
		expect(composed.restCount).toBe(6);
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual([
			"New feedback",
			"Moved down",
			"Moved up",
		]);
		expect(plain(composed.rest[0]?.segments)).toBe(
			"New 3 has new feedback, seen on !421 and !425.",
		);
		expect(plain(composed.rest[1]?.segments)).toBe(
			"Down 2 moved to Mixed feedback after !421 and !425.",
		);
	});

	it("unfolds the rest by kind, four named per kind then a count — 22 events", () => {
		const composed = composeOverview(overview({ changes: synthetic(22) }));
		const sentences = plain(composed.changed).split(". ");
		// The paragraph never outgrows its budget, however many events there are: nothing slipped
		// here, the new feedback is two rows, so what is left is the one count.
		expect(sentences).toHaveLength(1);
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual([
			"New feedback",
			"Moved up",
			"Seen for the first time",
			"Trends turned",
		]);
		const movedUp = plain(composed.rest[1]?.segments);
		// Four subjects named, however few sentences that takes: the three practices that moved
		// alike are one sentence and the group that moved with them is its own.
		expect(movedUp).toBe(
			"Practice 0, Practice 3 and Practice 6 moved to Going well after !421 and !425. " +
				"Group 2 moved to Going well after !421 and !425. " +
				'Five further practices moved the same way; the "All practice groups" table lists them.',
		);
		const trends = plain(composed.rest[3]?.segments);
		expect(trends).toBe(
			"Practice 2, Practice 8, Practice 14 and Practice 20 now show More positive recently " +
				"over !421 and !425.",
		);
		expect(trends).not.toMatch(/the same way/u);
	});

	it("closes the new-feedback rest in that kind's words, not as a move", () => {
		// Two pieces take a row and four are named here, so one is left to count.
		const composed = composeOverview(
			overview({
				changes: Array.from({ length: 7 }, (_, index) =>
					change("FEEDBACK_NEW", `new-${index}`, `New ${index}`),
				),
			}),
		);
		const fresh = composed.rest.find((paragraph) => paragraph.title === "New feedback");
		expect(plain(fresh?.segments)).toMatch(
			/One further practice has new feedback; the "All practice groups" table lists it\.$/u,
		);
		expect(plain(fresh?.segments)).not.toMatch(/moved/u);
	});

	it("closes the trend rest with the trends turning, not moving", () => {
		const composed = composeOverview(
			overview({
				changes: Array.from({ length: 6 }, (_, index) =>
					change("TREND_TURNED", `trend-${index}`, `Trend ${index}`, {
						from: "STEADY",
						to: "IMPROVING",
					}),
				),
			}),
		);
		const trends = composed.rest.find((paragraph) => paragraph.title === "Trends turned");
		expect(plain(trends?.segments)).toMatch(
			/Two further practices saw their trends turn; the "All practice groups" table lists them\.$/u,
		);
		expect(plain(trends?.segments)).not.toMatch(/moved/u);
	});

	it("names a group that moved as the group itself, not as a practice", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("GROUP_MOVED", "", "", {
						groupSlug: "communication",
						groupName: "Communicating in the open",
						from: "STRENGTH",
						to: "MIXED",
						direction: "DOWN",
						evidence: [thread("#releases")],
					}),
				],
			}),
		);
		expect(plain(composed.changed)).toBe(
			"Communicating in the open moved to Mixed feedback after #releases.",
		);
		expect(composed.changed).toContainEqual({
			type: "group",
			slug: "communication",
			name: "Communicating in the open",
		});
		expect(composed.changed.some((segment) => segment.type === "practice")).toBe(false);
	});

	it("names a group in the unfolded rest as the group itself", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("STANDING_MOVED", "scope", "Scope the change to one concern", {
						from: "MIXED",
						to: "STRENGTH",
						evidence: [githubPullRequest(19)],
					}),
					change("GROUP_MOVED", "", "", {
						groupSlug: "acting-on-review-feedback",
						groupName: "Acting on review feedback",
						from: "MIXED",
						to: "STRENGTH",
						evidence: [githubPullRequest(19)],
					}),
				],
			}),
		);
		// The practice and the group made the same move, but a group is not one of the practices:
		// each is its own sentence, and the group carries the slug its icon and colour come from.
		expect(plain(composed.rest[0]?.segments)).toBe(
			"Scope the change to one concern moved to Going well after #19. " +
				"Acting on review feedback moved to Going well after #19.",
		);
		expect(composed.rest[0]?.segments).toContainEqual({
			type: "group",
			slug: "acting-on-review-feedback",
			name: "Acting on review feedback",
		});
	});
});

describe("composeOverview held rows", () => {
	it("puts resolved feedback first, then the practices that held longest, at most three", () => {
		const composed = composeOverview(
			overview({
				holdingUp: [
					held("link-issue", "Link the issue the change closes", 9),
					held("respond", "Respond to each review comment", 4),
					held("commit-subjects", "Write commit subjects a reviewer can follow", 6),
				],
				changes: [
					change("FEEDBACK_RESOLVED", "describe", "Describe what changed and why", {
						resolvedBy: "DEVELOPER",
						evidence: [pullRequest(421), pullRequest(423), pullRequest(425)],
					}),
				],
			}),
		);
		expect(composed.holdingUp.map((row) => row.practiceName)).toStrictEqual([
			"Describe what changed and why",
			"Link the issue the change closes",
			"Write commit subjects a reviewer can follow",
		]);
		// The evidence on feedback the developer resolved is the work it was seen on, so the note
		// says who resolved it and names none of it.
		expect(composed.holdingUp[0]).toMatchObject({
			resolved: true,
			statement: "Marked as addressed",
		});
		expect(plain(composed.holdingUp[0]?.note)).toBe("on 9 September");
		expect(composed.holdingUp[0]?.note.some((segment) => segment.type === "work")).toBe(false);
		expect(composed.holdingUp[1]).toMatchObject({
			statement: "Every reviewer comment gets a visible answer",
		});
		expect(plain(composed.holdingUp[1]?.note)).toBe("held across nine pull requests");
		expect(composed.holdingUpNote).toBe("Another practice held too.");
	});

	it("names held work by the provider's noun", () => {
		const composed = composeOverview(
			overview({ holdingUp: [{ ...held("a", "A", 3), workProvider: "GITLAB" }] }),
		);
		expect(plain(composed.holdingUp[0]?.note)).toBe("held across three merge requests");
	});

	it("says a piece of feedback the work resolved was resolved by the work, naming it", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("FEEDBACK_RESOLVED", "describe", "Describe what changed and why", {
						resolvedBy: "WORK",
						evidence: [pullRequest(428), pullRequest(427), pullRequest(425)],
					}),
					change("FEEDBACK_RESOLVED", "scope", "Scope the change to one concern", {
						resolvedBy: "DEVELOPER",
					}),
				],
			}),
		);
		expect(composed.holdingUp[0]).toMatchObject({
			resolved: true,
			statement: "Resolved by the work",
		});
		expect(plain(composed.holdingUp[0]?.note)).toBe("after !428, !427 and !425 came back clean");
		expect(composed.holdingUp[0]?.note.filter((segment) => segment.type === "work")).toHaveLength(
			3,
		);
		expect(composed.holdingUp[1]).toMatchObject({
			resolved: true,
			statement: "Marked as addressed",
		});
		expect(plain(composed.holdingUp[1]?.note)).toBe("on 9 September");
	});

	it("claims no sentence for a practice the catalog has none for, and says what the work showed", () => {
		const composed = composeOverview(
			overview({
				holdingUp: [
					{
						...held("own", "A practice of our own", 4),
						holdsAs: undefined,
						workProvider: "GITLAB",
					},
					{ ...held("quiet", "Held on nothing yet", 0), holdsAs: undefined },
				],
			}),
		);
		expect(composed.holdingUp[0]).toStrictEqual({
			practiceSlug: "own",
			practiceName: "A practice of our own",
			note: [{ type: "text", text: "Held across four merge requests" }],
		});
		expect(composed.holdingUp[1]).toStrictEqual({
			practiceSlug: "quiet",
			practiceName: "Held on nothing yet",
			note: [],
		});
	});

	it("counts the rows it hid by kind: resolved feedback is not a practice that held", () => {
		const composed = composeOverview(
			overview({
				changes: Array.from({ length: 4 }, (_, index) =>
					change("FEEDBACK_RESOLVED", `resolved-${index}`, `Resolved ${index}`, {
						resolvedBy: "WORK",
					}),
				),
			}),
		);
		expect(composed.holdingUp).toHaveLength(3);
		expect(composed.holdingUpNote).toBe("Another piece of feedback resolved too.");
	});

	it("names both kinds in one sentence when the rows hid some of each", () => {
		const composed = composeOverview(
			overview({
				holdingUp: [held("a", "A", 5), held("b", "B", 3)],
				changes: Array.from({ length: 4 }, (_, index) =>
					change("FEEDBACK_RESOLVED", `resolved-${index}`, `Resolved ${index}`, {
						resolvedBy: "WORK",
					}),
				),
			}),
		);
		expect(composed.holdingUpNote).toBe(
			"Another piece of feedback resolved and two practices held too.",
		);
	});

	it("has no note when every held practice fits", () => {
		const composed = composeOverview(overview({ holdingUp: [held("a", "A", 3)] }));
		expect(composed.holdingUpNote).toBeUndefined();
	});
});

describe("what needs your attention", () => {
	it("says where the clean run fell back to and on which work", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("FEEDBACK_RESET", "diff-size", "Keep the diff reviewable in one sitting", {
						evidence: [pullRequest(425)],
					}),
				],
			}),
		);
		const [row] = composed.needsAttention;
		expect(row?.practiceName).toBe("Keep the diff reviewable in one sitting");
		expect(row?.feedbackId).toBe("diff-size-feedback");
		expect(plain(row?.sentence)).toBe("Back to 0 of 3 clean after !425");
		expect(row?.sentence).toContainEqual({ type: "work", ref: pullRequest(425) });
		// Status lives in the glyph the row carries, never in coloured words.
		expect(row?.def).toBe(ATTENTION_DEFS.reset);
	});

	it("says only that the run is empty when the wire did not say how long it is", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("FEEDBACK_RESET", "diff-size", "Keep the diff", {
						cleanNeeded: undefined,
						evidence: [pullRequest(425)],
					}),
				],
			}),
		);
		expect(plain(composed.needsAttention[0]?.sentence)).toBe("Back to no clean work after !425");
	});

	it("puts the fall backs first, then the new feedback, each newest first", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("FEEDBACK_NEW", "new-old", "New older", { at: new Date("2026-09-01T09:00:00Z") }),
					change("FEEDBACK_RESET", "reset-one", "Reset one"),
				],
			}),
		);
		expect(composed.needsAttention.map((row) => row.practiceName)).toStrictEqual([
			"Reset one",
			"New older",
		]);
		expect(composed.needsAttention.map((row) => row.def)).toStrictEqual([
			ATTENTION_DEFS.reset,
			ATTENTION_DEFS.new,
		]);
	});

	it("caps the rows at two and leaves the rest to the paragraph's count", () => {
		const composed = composeOverview(
			overview({
				changes: [
					...[1, 2].map((n) => change("FEEDBACK_RESET", `reset-${n}`, `Reset ${n}`)),
					...[1, 2].map((n) => change("FEEDBACK_NEW", `new-${n}`, `New ${n}`)),
				],
			}),
		);
		expect(composed.needsAttention.map((row) => row.practiceName)).toStrictEqual([
			"Reset 1",
			"Reset 2",
		]);
		expect(plain(composed.changed)).toBe("Two more practices changed as well.");
		expect(composed.restCount).toBe(2);
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual(["New feedback"]);
	});

	it("has no rows when nothing asks for the reader today", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("STANDING_MOVED", "diff-size", "Keep the diff reviewable in one sitting", {
						from: "MIXED",
						to: "DEVELOPING",
						direction: "DOWN",
					}),
				],
			}),
		);
		expect(composed.needsAttention).toStrictEqual([]);
	});

	it("leaves a standing that slipped in the paragraph: a standing is a balance, not a thing to do", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("FEEDBACK_RESET", "reset-one", "Reset one", { evidence: [pullRequest(425)] }),
					change("STANDING_MOVED", "diff-size", "Keep the diff reviewable in one sitting", {
						from: "MIXED",
						to: "DEVELOPING",
						direction: "DOWN",
						evidence: [pullRequest(423)],
					}),
				],
			}),
		);
		expect(composed.needsAttention.map((row) => row.practiceName)).toStrictEqual(["Reset one"]);
		expect(plain(composed.changed)).toBe(
			"Keep the diff reviewable in one sitting moved to Needs attention after !423.",
		);
	});

	it("unfolds the fall backs it only counted in that kind's own words", () => {
		const composed = composeOverview(
			overview({
				changes: Array.from({ length: 3 }, (_, index) =>
					change("FEEDBACK_RESET", `reset-${index}`, `Reset ${index}`, {
						evidence: [pullRequest(425)],
					}),
				),
			}),
		);
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual([
			"Back to no clean work",
		]);
		expect(plain(composed.rest[0]?.segments)).toBe("Reset 2 is back to 0 of 3 clean after !425.");
	});
});

describe("dedupe", () => {
	it("keeps one event per practice per block by precedence: new feedback wins over a move up", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("STANDING_MOVED", "scope", "Scope", { from: "MIXED", to: "STRENGTH" }),
					change("FEEDBACK_NEW", "scope", "Scope"),
				],
			}),
		);
		expect(composed.needsAttention.map((row) => row.practiceName)).toStrictEqual(["Scope"]);
		expect(plain(composed.changed)).toBe("");
		expect(composed.restCount).toBe(0);
	});

	it("keeps the fall back over new feedback when one practice did both", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("FEEDBACK_NEW", "scope", "Scope"),
					change("FEEDBACK_RESET", "scope", "Scope", { evidence: [pullRequest(425)] }),
				],
			}),
		);
		expect(composed.needsAttention.map((row) => plain(row.sentence))).toStrictEqual([
			"Back to 0 of 3 clean after !425",
		]);
	});

	it.each([
		["fell back", change("FEEDBACK_RESET", "scope", "Scope")],
		[
			"slipped",
			change("STANDING_MOVED", "scope", "Scope", {
				from: "STRENGTH",
				to: "MIXED",
				direction: "DOWN",
			}),
		],
		["got new feedback", change("FEEDBACK_NEW", "scope", "Scope")],
	])("drops a held practice that %s: holding and slipping contradict", (_how, slip) => {
		const composed = composeOverview(
			overview({ holdingUp: [held("scope", "Scope", 4)], changes: [slip] }),
		);
		expect(composed.holdingUp).toStrictEqual([]);
	});

	it("keeps a held practice whose trend turned: holding and moving do not contradict", () => {
		const composed = composeOverview(
			overview({
				holdingUp: [held("scope", "Scope", 4)],
				changes: [change("TREND_TURNED", "scope", "Scope", { to: "IMPROVING" })],
			}),
		);
		expect(composed.holdingUp.map((row) => row.practiceName)).toStrictEqual(["Scope"]);
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual(["Trends turned"]);
	});

	it("keeps a move up and a move down of the same practice to the one with precedence", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("STANDING_MOVED", "scope", "Scope", { from: "DEVELOPING", to: "MIXED" }),
					change("STANDING_MOVED", "scope", "Scope", {
						from: "MIXED",
						to: "DEVELOPING",
						direction: "DOWN",
					}),
				],
			}),
		);
		expect(plain(composed.changed)).toBe("Scope moved to Needs attention after !421 and !425.");
		expect(composed.restCount).toBe(0);
	});
});

describe("the reviewed work", () => {
	it("groups the work by kind and provider in the footer's order and keeps the addresses", () => {
		const composed = composeOverview(
			overview({
				reviewedWork: [
					thread("#releases"),
					pullRequest(421),
					githubPullRequest(7),
					pullRequest(425),
					thread("#incidents"),
				],
			}),
		);
		// GitLab's merge requests and GitHub's pull requests are two groups, so each gets its noun.
		expect(composed.reviewedWork).toStrictEqual([
			{
				kind: ARTIFACT_KIND.pullRequest,
				provider: "GITLAB",
				items: [pullRequest(421), pullRequest(425)],
			},
			{ kind: ARTIFACT_KIND.pullRequest, provider: "GITHUB", items: [githubPullRequest(7)] },
			{
				kind: ARTIFACT_KIND.conversationThread,
				provider: undefined,
				items: [thread("#releases"), thread("#incidents")],
			},
		]);
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
		expect(sentences).toStrictEqual([
			"Scope the change to one concern has new feedback.",
			"Describe what changed and why resolved.",
		]);
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
		expect(plain(practiceSentences(group("WORK")).describe)).toBe(
			"Feedback resolved by the work after !428, !427 and !425 came back clean.",
		);
		expect(plain(practiceSentences(group("DEVELOPER")).describe)).toBe(
			"Feedback marked as addressed on 9 September.",
		);
	});

	it("join the practices that made the same move into one bullet, and agree with them", () => {
		const sentences = groupSentences({
			groupSlug: "g",
			groupName: "G",
			changes: [1, 2, 3, 4].map((n) => change("FEEDBACK_NEW", `p-${n}`, `P ${n}`)),
		});
		expect(sentences).toStrictEqual(["P 1, P 2, P 3 and P 4 have new feedback."]);
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
	it("narrows the held rows to the group and gives each practice its own sentence", () => {
		const composed = composeGroupOverview(
			overview({
				holdingUp: [held("respond", "Respond to each review comment", 4)],
				reviewedWork: [pullRequest(425)],
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
					change("FEEDBACK_NEW", "other", "Other", { groupSlug: "other", groupName: "Other" }),
				],
			}),
			"review-ready-work",
		);
		expect(composed.holdingUp.map((row) => row.practiceName)).toStrictEqual([
			"Describe what changed and why",
			"Respond to each review comment",
		]);
		expect(composed.reviewedWork).toHaveLength(1);
		expect(plain(composed.practiceSentences.scope)).toBe(
			"There is new feedback, seen on !421 and !425.",
		);
		expect(plain(composed.practiceSentences.describe)).toBe("Feedback resolved.");
		expect(plain(composed.practiceSentences["diff-size"])).toBe(
			"Moved to Needs attention after !423.",
		);
		expect(composed.practiceSentences.other).toBeUndefined();
	});

	it("is empty for a group the overview does not mention, with the reviewed work still there", () => {
		const input = overview({ holdingUp: [held("a", "A", 2)], reviewedWork: [pullRequest(425)] });
		const composed = composeGroupOverview(input, "other");
		expect(composed.holdingUp).toStrictEqual([]);
		expect(composed.practiceSentences).toStrictEqual({});
		expect(composed.reviewedWork).toStrictEqual(composeOverview(input).reviewedWork);
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
		expect(plain(sentences.first)).toBe("Seen for the first time on !1.");
		expect(plain(sentences.trend)).toBe("Now shows More difficulties recently.");
	});

	it("say a practice was seen for the first time even when the wire names no work", () => {
		const sentences = practiceSentences({
			groupSlug: "g",
			groupName: "G",
			changes: [change("FIRST_OBSERVED", "first", "First", { evidence: [] })],
		});
		expect(plain(sentences.first)).toBe("Seen for the first time.");
	});
});

describe("the unfolded rest", () => {
	/** `n` practices seen for the first time in one run, each on the same pull request. */
	const firstSeen = (n: number) =>
		Array.from({ length: n }, (_, index) =>
			change("FIRST_OBSERVED", `first-${index}`, `First ${index}`, {
				evidence: [githubPullRequest(17)],
			}),
		);

	it("says a practice was seen for the first time, on the work it was seen on", () => {
		const composed = composeOverview(overview({ changes: firstSeen(1) }));
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual([
			"Seen for the first time",
		]);
		expect(plain(composed.rest[0]?.segments)).toBe("First 0 was seen for the first time on #17.");
	});

	it("counts the first sightings it did not name in their own words", () => {
		const composed = composeOverview(overview({ changes: firstSeen(12) }));
		expect(plain(composed.rest[0]?.segments)).toContain(
			'Eight further practices were seen for the first time; the "All practice groups" table lists them.',
		);
	});

	it("agrees with a single first sighting it did not name", () => {
		const composed = composeOverview(overview({ changes: firstSeen(5) }));
		expect(plain(composed.rest[0]?.segments)).toContain(
			'One further practice was seen for the first time; the "All practice groups" table lists it.',
		);
	});

	/** `n` practices that moved to the same standing on the same pull request. */
	const movedUp = (n: number) =>
		Array.from({ length: n }, (_, index) =>
			change("STANDING_MOVED", `up-${index}`, `Up ${index}`, {
				from: "MIXED",
				to: "STRENGTH",
				evidence: [githubPullRequest(19)],
			}),
		);

	it("names the practices that made the same move in one sentence, and agrees with them", () => {
		const composed = composeOverview(overview({ changes: movedUp(3) }));
		expect(plain(composed.rest[0]?.segments)).toBe(
			"Up 0, Up 1 and Up 2 moved to Going well after #19.",
		);
		// Each keeps its own pill, so every practice named is still one the reader can open.
		expect(
			composed.rest[0]?.segments.filter((segment) => segment.type === "practice"),
		).toHaveLength(3);
	});

	it("splits the sentence where the work the move was seen on differs", () => {
		const composed = composeOverview(
			overview({
				changes: [
					...movedUp(2),
					change("STANDING_MOVED", "up-elsewhere", "Up elsewhere", {
						from: "MIXED",
						to: "STRENGTH",
						evidence: [githubPullRequest(20)],
					}),
				],
			}),
		);
		// The third practice moved to the same standing, but the sentence names the work it was
		// seen on, so it cannot ride on a sentence that names other work.
		expect(plain(composed.rest[0]?.segments)).toBe(
			"Up 0 and Up 1 moved to Going well after #19. Up elsewhere moved to Going well after #20.",
		);
	});

	it("counts the subjects a joined sentence did not name, not the sentences", () => {
		const composed = composeOverview(overview({ changes: movedUp(6) }));
		expect(plain(composed.rest[0]?.segments)).toBe(
			"Up 0, Up 1, Up 2 and Up 3 moved to Going well after #19. " +
				'Two further practices moved the same way; the "All practice groups" table lists them.',
		);
	});
});

describe("composeNextStep", () => {
	const card = (overrides: Partial<PracticeFeedbackCardEntry> = {}): PracticeFeedbackCardEntry => ({
		feedbackId: "f1",
		practiceSlug: "scope",
		practiceName: "Scope the change to one concern",
		group: { slug: "review-ready-work", name: "Packaging work for review" },
		headline: "Merge requests bundle a fix with a refactor",
		body: "",
		reviewedWork: [
			{ ref: pullRequest(418), date: new Date("2026-09-06"), outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(421), date: new Date("2026-09-03"), outcome: "OMISSION_GAP" },
		],
		nextStep: "Open the fix first as its own merge request.",
		condition: [],
		cleanWork: [{ ref: pullRequest(423), date: new Date("2026-09-08") }],
		cleanNeeded: 3,
		state: "open",
		timestamp: new Date("2026-09-09T14:10:00.000Z"),
		...overrides,
	});

	it("names the work the card was seen on, and leaves out the clean work", () => {
		expect(plain(composeNextStep([card()], "review-ready-work"))).toBe(
			"Open the fix first as its own merge request. That is the open next step on Scope the change to one concern, seen on !418 and !421.",
		);
	});

	it("says nothing about a card with no next step, and nothing for a resolved one", () => {
		expect(composeNextStep([card({ nextStep: "" })], "review-ready-work")).toBeUndefined();
		expect(composeNextStep([card({ state: "resolved" })], "review-ready-work")).toBeUndefined();
	});

	it("skips a newer card without an next step for the newest one that has one", () => {
		const later = card({
			feedbackId: "f2",
			practiceSlug: "describe",
			practiceName: "Describe what changed and why",
			nextStep: "",
			timestamp: new Date("2026-09-10T09:00:00.000Z"),
		});
		expect(plain(composeNextStep([card(), later], "review-ready-work"))).toContain(
			"on Scope the change to one concern",
		);
	});
});
