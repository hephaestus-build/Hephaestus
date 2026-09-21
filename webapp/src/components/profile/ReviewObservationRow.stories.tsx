import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen } from "storybook/test";

import type { ObservationDetail } from "@/api/types.gen";
import { daysBefore } from "@/components/common/story-clock";
import { formatDay } from "@/lib/dates";
import {
	couldNotSettleIt,
	nextStepWithoutDelivery,
	nothingToJudge,
	searchedAndFoundNothing,
} from "@/stories/practice-detail-story-mock-data";
import { expectSettledVisible } from "@/test/overlay";
import { expectNoPageOverflow } from "@/test/reflow";

import { ReviewObservationRow } from "./ReviewObservationRow";

/** The pull request every observation here was seen on. */
const reviewedWork = {
	artifactId: 4821,
	artifactKind: "scm.pull_request",
	artifactUrl: "https://github.com/owner/repo/pull/4821",
} as const;

/** Everything the feed can carry about one observation, so every block has something to show. */
const strength = {
	id: "00000000-0000-0000-0000-000000000101",
	feedbackId: "00000000-0000-0000-0000-000000000102",
	practiceSlug: "explains-decisions",
	practiceName: "Explain significant decisions",
	summary: "The reasoning is recorded next to the changed behavior",
	presence: "PRESENT",
	assessment: "GOOD",
	observedAt: daysBefore(2),
	origin: "LIVE",
	claimCurrentness: "CURRENT",
	...reviewedWork,
	evidenceRationale:
		"The comment above the changed branch states why the timeout was raised, so a later reader does not have to reconstruct it from the diff.",
	deliveredFeedback:
		"Keep doing this where a value is chosen rather than derived — the reasoning is what a reviewer cannot recover on their own.",
	evidence: {
		detector: "practice-observer",
		citations: [
			{
				sourceKind: "scm.pull-request.diff",
				artifactPath: "owner/repo#4821",
				path: "server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/AgentClient.java",
				side: "NEW",
				startLine: 88,
				endLine: 90,
				quote:
					"// Raised from 30s: the precompute step regularly needs 45s on a cold cache.\n" +
					"private static final Duration TIMEOUT = Duration.ofSeconds(90);",
				quoteRedacted: false,
			},
			{
				sourceKind: "scm.pull-request.diff",
				artifactPath: "owner/repo#4821",
				path: "server/application/src/main/resources/application.yml",
				side: "NEW",
				startLine: 12,
				endLine: 12,
				quote: "agent.timeout: 90s",
				quoteRedacted: false,
			},
		],
	},
} satisfies ObservationDetail;

/** A problem seen. The wire ranks it, and the row deliberately does not show the rank. */
const problemSeen = {
	id: "00000000-0000-0000-0000-000000000301",
	practiceSlug: "does-not-swallow-errors",
	practiceName: "Do not swallow recoverable errors",
	summary: "The exception is caught and discarded",
	presence: "PRESENT",
	assessment: "BAD",
	severity: "MAJOR",
	observedAt: daysBefore(2),
	origin: "LIVE",
	claimCurrentness: "CURRENT",
	...reviewedWork,
} satisfies ObservationDetail;

/** One row per outcome the registry can name, with nothing to open under any of them. */
const outcomes: ObservationDetail[] = [
	problemSeen,
	{
		id: "00000000-0000-0000-0000-000000000201",
		practiceSlug: "avoids-unsafe-defaults",
		practiceName: "Avoid unsafe defaults",
		summary: "The boundary does not fall back to an unsafe value",
		presence: "ABSENT",
		assessment: "GOOD",
		observedAt: daysBefore(2),
		origin: "LIVE",
		claimCurrentness: "CURRENT",
		...reviewedWork,
	},
	{
		id: "00000000-0000-0000-0000-000000000351",
		practiceSlug: "covers-new-behavior",
		practiceName: "Cover new behavior with a test",
		summary: "The new branch has no test exercising it",
		presence: "ABSENT",
		assessment: "BAD",
		severity: "CRITICAL",
		observedAt: daysBefore(2),
		origin: "LIVE",
		claimCurrentness: "CURRENT",
		...reviewedWork,
	},
	{
		id: "00000000-0000-0000-0000-000000000401",
		practiceSlug: "network-timeouts",
		practiceName: "Document network timeout behavior",
		summary: "This change performs no network request",
		presence: "NOT_APPLICABLE",
		observedAt: daysBefore(2),
		origin: "LIVE",
		claimCurrentness: "CURRENT",
		...reviewedWork,
	},
	{
		id: "00000000-0000-0000-0000-000000000501",
		practiceSlug: "keeps-docs-current",
		practiceName: "Keep documentation current",
		summary: "The evidence does not settle whether the page is current",
		presence: "INCONCLUSIVE",
		observedAt: daysBefore(2),
		origin: "LIVE",
		claimCurrentness: "CURRENT",
		...reviewedWork,
	},
];

/**
 * A row inside a review run's card, where the head above it already names the work: the card
 * passes `showWorkLink={false}`, so these stories do the same and `WithWorkLink` is the one
 * that shows the row standing alone.
 */
const meta = {
	title: "Profile/Review runs/Observation row",
	component: ReviewObservationRow,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { observation: strength, showWorkLink: false, onRespond: fn() },
	decorators: [
		(Story) => (
			<ul className="divide-y rounded-lg border">
				<Story />
			</ul>
		),
	],
} satisfies Meta<typeof ReviewObservationRow>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Open on arrival, as every row of a practice level is, with every block the feed carries under
 * its own label: why it was noted, the evidence with every quote shown, the next step, and the
 * response controls. The practice under the summary is the grey pill every practice surface
 * names a practice with.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		const row = canvas.getByRole("button", { name: new RegExp(strength.summary) });
		await expect(row).toHaveAttribute("aria-expanded", "true");
		await expect(
			canvas.getByText(strength.practiceName).closest('[data-slot="badge"]'),
		).not.toBeNull();
		await expect(canvas.getByText("Strength shown")).toBeVisible();
		// The day belongs to the run's card, which names it once above these rows.
		await expect(canvas.queryByText(formatDay(strength.observedAt))).toBeNull();
		// The four labels, in the order the reader needs them.
		const labels = ["Why it was noted", "Evidence", "Next step", "Your response"];
		const positions = labels.map((label) => canvas.getByText(label).getBoundingClientRect().top);
		await expect([...positions].sort((a, b) => a - b)).toStrictEqual(positions);
		// Every quote is shown, with nothing to unfold; the scanner that captured them is named once.
		await expect(
			canvas.getByText("private static final Duration TIMEOUT = Duration.ofSeconds(90);"),
		).toBeVisible();
		await expect(canvas.getByText("agent.timeout: 90s")).toBeVisible();
		await expect(canvas.getAllByText("practice-observer")).toHaveLength(1);
		// Inside the work's own card, the row does not link the work again.
		await expect(canvas.queryByRole("link")).toBeNull();
		await expect(canvas.getByRole("button", { name: "Disputed" })).toBeVisible();
	},
};

/** Closed until pressed: the head line alone, and a press opens the same body. */
export const Collapsed: Story = {
	args: { defaultOpen: false },
	play: async ({ canvas, userEvent }) => {
		const row = canvas.getByRole("button", { name: new RegExp(strength.summary) });
		await expect(row).toHaveAttribute("aria-expanded", "false");
		await expect(canvas.queryByText("Why it was noted")).not.toBeInTheDocument();
		await userEvent.click(row);
		await expect(row).toHaveAttribute("aria-expanded", "true");
		await expect(canvas.getByText("Why it was noted")).toBeVisible();
	},
};

/** An observation somebody asked for by hand carries the registry's badge for it. */
export const NotLive: Story = {
	args: { observation: { ...strength, origin: "MANUAL" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Requested")).toBeVisible();
	},
};

/**
 * Reviewed under rules that have since changed: the row says so in one line over its body and
 * hides nothing else.
 */
export const Stale: Story = {
	args: { observation: { ...strength, claimCurrentness: "STALE" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reviewed under earlier rules for this practice.")).toBeVisible();
		await expect(canvas.getByText("Why it was noted")).toBeVisible();
		await expect(canvas.getByText("Next step")).toBeVisible();
	},
};

/** A problem seen says so, and nothing on the row ranks how bad it is. */
export const NeedsImprovement: Story = {
	args: { observation: problemSeen },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Needs improvement")).toBeVisible();
		await expect(canvas.queryByText("Major")).toBeNull();
	},
};

/** No citations and no rationale: the labels for them are not drawn over nothing. */
export const WithoutEvidence: Story = {
	args: { observation: { ...strength, evidenceRationale: undefined, evidence: undefined } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Why it was noted")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Evidence")).not.toBeInTheDocument();
		await expect(canvas.getByText("Next step")).toBeVisible();
	},
};

/** Standing outside the work's card, the evidence links the reviewed work itself. */
export const WithWorkLink: Story = {
	args: { showWorkLink: true },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: /Open the pull or merge request/ }),
		).toHaveAttribute("href", reviewedWork.artifactUrl);
	},
};

/**
 * What the reader already said: the resolution stands pressed, the rating given on the feedback
 * card and the comment sent with the response are shown as they are.
 */
export const Responded: Story = {
	args: {
		observation: {
			...strength,
			feedbackUsefulness: "HELPFUL",
			feedbackResolution: "ADDRESSED",
			feedbackResponseComment:
				"Split the change into two commits so the reasoning reads on its own.",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Addressed" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(canvas.getByText("Helpful")).toBeVisible();
		await expect(
			canvas.getByText("Split the change into two commits so the reasoning reads on its own."),
		).toBeVisible();
	},
};

/** An observation with no words of its own is named by its practice alone, still as the pill. */
export const UntitledObservation: Story = {
	args: { observation: { ...strength, summary: "" }, showPracticeName: false },
	play: async ({ canvas }) => {
		const pill = canvas.getByText(strength.practiceName).closest('[data-slot="badge"]');
		await expect(pill).not.toBeNull();
		await expect(canvas.getAllByText(strength.practiceName)).toHaveLength(1);
	},
};

/** Nothing to open: the outcome line alone, with no chevron and no press. */
export const OutcomeMatrix: Story = {
	render: (args) => (
		<>
			{outcomes.map((observation) => (
				<ReviewObservationRow key={observation.id} {...args} observation={observation} />
			))}
		</>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Risk avoided")).toBeVisible();
		await expect(canvas.getByText("Expected practice missing")).toBeVisible();
		await expect(canvas.getByText("Not assessed")).toBeVisible();
		await expect(canvas.getByText("Not certain enough to say")).toBeVisible();
	},
};

export const FeedbackPending: Story = {
	args: {
		observation: { ...strength, feedbackResolution: "ADDRESSED" },
		isFeedbackResponsePending: true,
	},
};

/**
 * A press opens the band in the response's own tint; Skip records the response without a comment.
 */
export const RecordsAResponse: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Addressed" }));
		await expect(canvas.getByRole("button", { name: "Addressed" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		const field = canvas.getByRole("textbox", { name: "Anything to add?" });
		await expect(field).not.toBeRequired();
		await expect(args.onRespond).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		// The usefulness `strength` arrived with still travels: the endpoint replaces, so omitting
		// it would clear it.
		await expect(args.onRespond).toHaveBeenCalledWith(strength, {
			usefulness: undefined,
			resolution: "ADDRESSED",
			comment: undefined,
		});
		await expect(canvas.queryByRole("textbox")).toBeNull();
	},
};

export const CommentWaitsForAnAnswer: Story = {
	play: async ({ canvas }) => {
		// The comment adds context to an answer, so it stays hidden until one is given.
		await expect(canvas.getByRole("button", { name: "Addressed" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await expect(canvas.queryByRole("textbox")).toBeNull();
	},
};

/** A dispute is red, needs its sentence, and Skip only closes the band. */
export const DisputeWaitsForItsSentence: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Disputed" }));
		await expect(args.onRespond).not.toHaveBeenCalled();
		const field = canvas.getByRole("textbox", { name: "What was missed?" });
		await expect(field).toBeRequired();
		// An empty sentence does not send.
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onRespond).not.toHaveBeenCalled();
		await userEvent.type(field, "The value is derived, not chosen.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onRespond).toHaveBeenCalledWith(strength, {
			usefulness: undefined,
			resolution: "DISPUTED",
			comment: "The value is derived, not chosen.",
		});
	},
};

/** A comment travels with the response it was written under. */
export const RecordsAComment: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Not applicable" }));
		await userEvent.type(
			canvas.getByRole("textbox", { name: "Anything to add?" }),
			"Adopted in the follow-up.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onRespond).toHaveBeenCalledWith(strength, {
			usefulness: undefined,
			resolution: "NOT_APPLICABLE",
			comment: "Adopted in the follow-up.",
		});
	},
};

/** The chip explains its outcome on hover with the registry's sentence. */
export const OutcomeExplained: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.hover(canvas.getByText("Strength shown"));
		await expectSettledVisible(await screen.findByText(/the author is told so/));
	},
};

/** At 320px the summary takes the line and the outcome and badges wrap under it. */
export const MobileReflow: Story = {
	args: {
		observation: { ...strength, assessment: "BAD", severity: "MAJOR", origin: "BACKFILL" },
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Backfilled")).toBeVisible();
		await expectNoPageOverflow();
	},
};

/**
 * Nothing found where the review looked. "What was checked" says what it looked for, the sources
 * it read in the registry's words, and how far the absence reaches — so an expected practice
 * reported missing can be judged against the search that reported it.
 */
export const SearchedAndFoundNothing: Story = {
	args: { observation: searchedAndFoundNothing },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("What was checked")).toBeVisible();
		await expect(canvas.getByText("Looked for:")).toBeVisible();
		await expect(
			canvas.getByText("a test exercising the new caching branch of the loader"),
		).toBeVisible();
		// The sources are named as the registry names them, never by their wire kind.
		await expect(canvas.getByText("The code changes and Files in the repository")).toBeVisible();
		await expect(canvas.getByText("Not covered:")).toBeVisible();
		await expect(
			canvas.getByText("test files outside the paths this change touched"),
		).toBeVisible();
	},
};

/** Nothing for the practice to judge here: what it looks for, what was read, and what ruled it out. */
export const NothingToJudge: Story = {
	args: { observation: nothingToJudge },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not assessed")).toBeVisible();
		await expect(canvas.getByText("Looks for:")).toBeVisible();
		await expect(
			canvas.getByText("how a change handles a network call that times out"),
		).toBeVisible();
		await expect(canvas.getByText("The code changes")).toBeVisible();
		await expect(canvas.getByText("Nothing to judge because:")).toBeVisible();
		await expect(canvas.getByText("nothing in the diff calls out of the process")).toBeVisible();
	},
};

/** A question the work left open, and the one thing that would have answered it. */
export const CouldNotSettleIt: Story = {
	args: { observation: couldNotSettleIt },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not certain enough to say")).toBeVisible();
		await expect(canvas.getByText("Open question:")).toBeVisible();
		await expect(
			canvas.getByText("whether a reviewer asked for the package move in this same request"),
		).toBeVisible();
		await expect(canvas.getByText("Would settle it:")).toBeVisible();
		await expect(canvas.getByText("the review thread the description points at")).toBeVisible();
		// A warrant of its own is enough to open the row; there is no evidence and no rationale block.
		await expect(canvas.queryByText("Evidence")).not.toBeInTheDocument();
	},
};

/**
 * The next step the review wrote about this work, with nothing delivered from it. It is the row's
 * one next step either way: the delivery may have been withheld or rewritten, and two sentences
 * about the same observation cannot both be acted on.
 */
export const NextStepWithoutDelivery: Story = {
	args: { observation: nextStepWithoutDelivery },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Next step")).toBeVisible();
		await expect(
			canvas.getByText(
				"Split the commit so the rename can be reverted without the caching change.",
			),
		).toBeVisible();
		await expect(canvas.queryByText("What was checked")).not.toBeInTheDocument();
	},
};
