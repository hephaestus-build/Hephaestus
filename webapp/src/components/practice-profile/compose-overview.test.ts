import { describe, expect, it } from "vitest";

import type {
	HeldPractice,
	PracticeProfileOverview,
	ProfileChange,
	ReviewedWorkRef,
} from "@/api/types.gen";
import { count, type FeedbackTextSegment } from "@/components/common/feedback-text";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { ARTIFACT_KIND } from "@/lib/artifact-kinds";

import {
	composeNextStep,
	composeOverview,
	EMPTY_OVERVIEW,
	groupOverviewOf,
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
	evidence: [pullRequest(421), pullRequest(425)],
	...extra,
});

const held = (slug: string, name: string, cleanWork: number): HeldPractice => ({
	practiceSlug: slug,
	practiceName: name,
	groupSlug: "review-ready-work",
	holdsAs: "every comment got an answer before the next push",
	cleanWork,
	workKind: ARTIFACT_KIND.pullRequest,
	since: new Date("2026-08-20T09:00:00Z"),
});

const overview = (partial: Partial<PracticeProfileOverview>): PracticeProfileOverview => ({
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
		});
	});
}

describe("count", () => {
	it("spells a count below ten and writes digits from ten", () => {
		expect(count(1, "practice", "practices")).toBe("one practice");
		expect(count(9, "practice", "practices")).toBe("nine practices");
		expect(count(10, "practice", "practices")).toBe("10 practices");
		expect(count(22, "practice", "practices")).toBe("22 practices");
	});

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
		expect(plain(composed.changed)).toBe("Two practices moved since the latest run.");
	});

	it("leaves a digit alone", () => {
		const composed = composeOverview(
			overview({
				changes: Array.from({ length: 12 }, (_, n) =>
					change("STANDING_MOVED", `up-${n}`, `Up ${n}`, { from: "MIXED", to: "STRENGTH" }),
				),
			}),
		);
		expect(plain(composed.changed)).toBe("12 practices moved since the latest run.");
	});

	it("leaves a practice name alone, as the link it is", () => {
		const composed = composeOverview(
			overview({
				changes: [change("STANDING_MOVED", "scope", "scope", { from: "STRENGTH", to: "MIXED" })],
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

	it("names one change in full", () => {
		const composed = composeOverview(
			overview({
				changes: [change("FEEDBACK_NEW", "scope-one-concern", "Scope the change to one concern")],
			}),
		);
		expect(plain(composed.changed)).toBe(
			"There is new feedback on Scope the change to one concern, seen on !421 and !425.",
		);
		expect(composed.changed).toContainEqual({
			type: "practice",
			slug: "scope-one-concern",
			name: "Scope the change to one concern",
		});
		expect(composed.changed).toContainEqual({ type: "work", ref: pullRequest(421) });
		expect(composed.rest).toStrictEqual([]);
	});

	it("names new feedback and one slip, then counts the rest — three events", () => {
		const composed = composeOverview(
			overview({
				changes: [
					change("FEEDBACK_NEW", "scope", "Scope the change to one concern"),
					change("STANDING_MOVED", "diff-size", "Keep the diff reviewable in one sitting", {
						from: "MIXED",
						to: "DEVELOPING",
						evidence: [pullRequest(423)],
					}),
					change("STANDING_MOVED", "linked-issue", "Link the issue the change closes", {
						from: "MIXED",
						to: "STRENGTH",
					}),
				],
			}),
		);
		expect(plain(composed.changed)).toBe(
			"There is new feedback on Scope the change to one concern, seen on !421 and !425. " +
				"Keep the diff reviewable in one sitting moved to Needs attention after !423. " +
				"One more practice changed as well; the practices table lists them.",
		);
		expect(composed.restCount).toBe(1);
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual(["Moved up"]);
		expect(plain(composed.rest[0]?.segments)).toBe(
			"Link the issue the change closes is now Going well after !421 and !425.",
		);
	});

	it("keeps the paragraph to two pieces of new feedback, one slip and one count — nine events", () => {
		const changes = [
			...[1, 2, 3].map((n) => change("FEEDBACK_NEW", `new-${n}`, `New ${n}`)),
			...[1, 2].map((n) =>
				change("STANDING_MOVED", `down-${n}`, `Down ${n}`, { from: "STRENGTH", to: "MIXED" }),
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
		// The further piece of new feedback, the other slip and the moves up are one count: the
		// paragraph never adds bad news up sentence by sentence. The two it names got their
		// feedback on the same work, so they are named together rather than twice over.
		expect(plain(composed.changed)).toBe(
			"There is new feedback on New 1 and New 2, seen on !421 and !425. " +
				"Down 1 moved to Mixed feedback after !421 and !425. " +
				"Five more practices and one group changed as well; the practices table lists them.",
		);
		// What the paragraph counted is exactly what unfolds, the named events left out.
		expect(composed.restCount).toBe(6);
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual([
			"New feedback",
			"Moved down",
			"Moved up",
		]);
		expect(plain(composed.rest[0]?.segments)).toBe(
			"There is new feedback on New 3, seen on !421 and !425.",
		);
		expect(plain(composed.rest[1]?.segments)).toBe(
			"Down 2 is now Mixed feedback after !421 and !425.",
		);
	});

	it("unfolds the rest by kind, four named per kind then a count — 22 events", () => {
		const composed = composeOverview(overview({ changes: synthetic(22) }));
		const sentences = plain(composed.changed).split(". ");
		// The paragraph never outgrows its budget, however many events there are: two named pieces
		// of new feedback — one sentence, since they were seen on the same work — and one count.
		expect(sentences).toHaveLength(2);
		expect(composed.rest.map((paragraph) => paragraph.title)).toStrictEqual([
			"New feedback",
			"Moved up",
			"Seen for the first time",
			"Trends turned",
		]);
		const movedUp = plain(composed.rest[1]?.segments);
		// Four subjects named, however few sentences that takes: the three practices that moved
		// alike are one sentence and the group that moved with them is its own.
		expect(movedUp).toContain(
			"Practice 0, Practice 3 and Practice 6 are now Going well after !421 and !425.",
		);
		expect(movedUp).toContain("Group 2 is now Going well after !421 and !425.");
		expect(movedUp).toMatch(
			/further practices? (?:and \w+ groups? )?moved the same way; the practices table lists them\.$/u,
		);
		const trends = plain(composed.rest[3]?.segments);
		expect(trends).toBe(
			"Practice 2, Practice 8, Practice 14 and Practice 20 now show More positive recently " +
				"over !421 and !425.",
		);
		expect(trends).not.toMatch(/the same way/u);
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
			"Scope the change to one concern is now Going well after #19. " +
				"Acting on review feedback is now Going well after #19.",
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
			statement: "Every comment got an answer before the next push",
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

	it("has no note when every held practice fits", () => {
		const composed = composeOverview(overview({ holdingUp: [held("a", "A", 3)] }));
		expect(composed.holdingUpNote).toBeUndefined();
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
		expect(plain(composed.changed)).toBe("There is new feedback on Scope, seen on !421 and !425.");
		expect(composed.restCount).toBe(0);
	});

	it("drops a held practice that also slipped: holding and slipping contradict", () => {
		const composed = composeOverview(
			overview({
				holdingUp: [held("scope", "Scope", 4)],
				changes: [change("STANDING_MOVED", "scope", "Scope", { from: "STRENGTH", to: "MIXED" })],
			}),
		);
		expect(composed.holdingUp).toStrictEqual([]);
		expect(plain(composed.changed)).toContain("Scope moved to Mixed feedback");
	});

	it("drops a held practice that got new feedback", () => {
		const composed = composeOverview(
			overview({
				holdingUp: [held("scope", "Scope", 4)],
				changes: [change("FEEDBACK_NEW", "scope", "Scope")],
			}),
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
					change("STANDING_MOVED", "scope", "Scope", { from: "MIXED", to: "DEVELOPING" }),
				],
			}),
		);
		expect(plain(composed.changed)).toBe("Scope moved to Needs attention after !421 and !425.");
		expect(composed.restCount).toBe(0);
	});
});

describe("composeReviewedWork", () => {
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

/** The group's sentences for the practices table, and each practice's own for the group level. */
const groupSentences = (group: ChangesInGroup) =>
	composeOverview(overview({ changes: inGroup(group) })).groupSentences[group.groupSlug]?.map(
		plain,
	);
const practiceSentences = (group: ChangesInGroup) =>
	groupOverviewOf(composeOverview(overview({ changes: inGroup(group) })), group.groupSlug)
		.practiceSentences;

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

	it("keep every practice of a joined bullet as its own pill", () => {
		const composed = composeOverview(
			overview({
				changes: [1, 2].map((n) => change("FEEDBACK_NEW", `p-${n}`, `P ${n}`)),
			}),
		);
		const bullet = composed.groupSentences["review-ready-work"]?.[0];
		expect(bullet?.filter((segment) => segment.type === "practice")).toStrictEqual([
			{ type: "practice", slug: "p-1", name: "P 1" },
			{ type: "practice", slug: "p-2", name: "P 2" },
		]);
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

describe("groupOverviewOf", () => {
	it("narrows the held rows to the group and gives each practice its own sentence", () => {
		const composed = groupOverviewOf(
			composeOverview(
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
							evidence: [pullRequest(423)],
						}),
						// Another group's change stays out of this group's level.
						change("FEEDBACK_NEW", "other", "Other", { groupSlug: "other", groupName: "Other" }),
					],
				}),
			),
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
		const page = composeOverview(
			overview({ holdingUp: [held("a", "A", 2)], reviewedWork: [pullRequest(425)] }),
		);
		const composed = groupOverviewOf(page, "other");
		expect(composed.holdingUp).toStrictEqual([]);
		expect(composed.practiceSentences).toStrictEqual({});
		expect(composed.reviewedWork).toBe(page.reviewedWork);
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
			"Eight further practices were seen for the first time; the practices table lists them.",
		);
	});

	it("agrees with a single first sighting it did not name", () => {
		const composed = composeOverview(overview({ changes: firstSeen(5) }));
		expect(plain(composed.rest[0]?.segments)).toContain(
			"One further practice was seen for the first time; the practices table lists them.",
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
			"Up 0, Up 1 and Up 2 are now Going well after #19.",
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
			"Up 0 and Up 1 are now Going well after #19. Up elsewhere is now Going well after #20.",
		);
	});

	it("counts the subjects a joined sentence did not name, not the sentences", () => {
		const composed = composeOverview(overview({ changes: movedUp(6) }));
		expect(plain(composed.rest[0]?.segments)).toBe(
			"Up 0, Up 1, Up 2 and Up 3 are now Going well after #19. " +
				"Two further practices moved the same way; the practices table lists them.",
		);
	});

	it("leaves the other kinds reading as they did", () => {
		const composed = composeOverview(
			overview({
				changes: [
					...[1, 2, 3].map((n) =>
						change("FEEDBACK_NEW", `new-${n}`, `New ${n}`, { evidence: [githubPullRequest(17)] }),
					),
					change("STANDING_MOVED", "up", "Up", {
						from: "MIXED",
						to: "STRENGTH",
						evidence: [githubPullRequest(17)],
					}),
					change("TREND_TURNED", "trend", "Trend", {
						to: "IMPROVING",
						evidence: [githubPullRequest(17)],
					}),
				],
			}),
		);
		const byTitle = Object.fromEntries(
			composed.rest.map((paragraph) => [paragraph.title, plain(paragraph.segments)]),
		);
		expect(byTitle["New feedback"]).toBe("There is new feedback on New 3, seen on #17.");
		expect(byTitle["Moved up"]).toBe("Up is now Going well after #17.");
		expect(byTitle["Trends turned"]).toBe("Trend now shows More positive recently over #17.");
	});
});

describe("composeNextStep", () => {
	const card = (overrides: Partial<PracticeFeedbackCardEntry> = {}): PracticeFeedbackCardEntry => ({
		feedbackId: "f1",
		practiceSlug: "scope",
		practiceName: "Scope the change to one concern",
		groupSlug: "review-ready-work",
		groupName: "Packaging work for review",
		headline: "Merge requests bundle a fix with a refactor",
		body: "",
		reviewedWork: [
			{ ref: pullRequest(418), date: "2026-09-06", outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(421), date: "2026-09-03", outcome: "OMISSION_GAP" },
		],
		nextStep: "Open the fix first as its own merge request.",
		condition: [],
		cleanWork: [pullRequest(423)],
		cleanNeeded: 3,
		state: "open",
		timestamp: "2026-09-09T14:10:00.000Z",
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
			timestamp: "2026-09-10T09:00:00.000Z",
		});
		expect(plain(composeNextStep([card(), later], "review-ready-work"))).toContain(
			"on Scope the change to one concern",
		);
	});
});
