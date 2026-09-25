import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, within } from "storybook/test";

import type { FeedbackResponseRequest, ObservationDetail } from "@/api/types.gen";
import { formatDay } from "@/lib/dates";
import { expectSettledVisible } from "@/stories/overlay";
import {
	nextStepWithoutDelivery,
	searchedAndFoundNothing,
} from "@/stories/practice-detail-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { daysBefore } from "@/stories/story-clock";
import { expectGenuinelyDisabled } from "@/test/controls";

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
	feedbackResponse: { feedbackId: "00000000-0000-0000-0000-000000000102", usefulness: "HELPFUL" },
	practiceSlug: "explains-decisions",
	practiceName: "Explain significant decisions",
	summary: "The reasoning is recorded next to the changed behavior",
	assessmentStatus: "ASSESSED",
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
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "BAD",
	severity: "MAJOR",
	observedAt: daysBefore(2),
	origin: "LIVE",
	claimCurrentness: "CURRENT",
	...reviewedWork,
} satisfies ObservationDetail;

/**
 * One row per outcome the registry can name, with nothing to open under any of them. The
 * assessment is the behaviour's desirability, so the two absent rows read against the grain: an
 * undesirable behaviour absent is the risk avoided, a desirable one absent is the gap.
 */
const outcomes: ObservationDetail[] = [
	problemSeen,
	{
		id: "00000000-0000-0000-0000-000000000201",
		practiceSlug: "avoids-unsafe-defaults",
		practiceName: "Avoid unsafe defaults",
		summary: "The boundary does not fall back to an unsafe value",
		assessmentStatus: "ASSESSED",
		presence: "ABSENT",
		assessment: "BAD",
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
		assessmentStatus: "ASSESSED",
		presence: "ABSENT",
		assessment: "GOOD",
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
		assessmentStatus: "NOT_APPLICABLE",
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
		assessmentStatus: "UNDETERMINED",
		observedAt: daysBefore(2),
		origin: "LIVE",
		claimCurrentness: "CURRENT",
		...reviewedWork,
	},
];

const meta = {
	component: ReviewObservationRow,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { observation: strength, onRespond: fn() },
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
		const row = canvas.getByRole("button", { name: new RegExp(strength.summary, "u") });
		await expect(row).toHaveAttribute("aria-expanded", "true");
		await expect(canvas.getByText(strength.practiceName)).toBeVisible();
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
		const response = canvas.getByRole("group", { name: "Your response" });
		await expect(
			within(response)
				.getAllByRole("button")
				.map((button) => button.textContent),
		).toStrictEqual(["Addressed", "Disputed", "Not applicable"]);
		// The comment adds context to an answer, so it stays hidden until one is given.
		await expect(canvas.queryByRole("textbox")).toBeNull();
	},
};

/**
 * The practice or the work has changed since this was reviewed, and the review cited nothing: the
 * row says so in one line over its body, and draws no label over evidence it does not have.
 */
export const StaleWithoutEvidence: Story = {
	args: {
		observation: {
			...strength,
			claimCurrentness: "STALE",
			evidenceRationale: undefined,
			evidence: undefined,
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("The practice or the reviewed work changed after this observation."),
		).toBeVisible();
		await expect(canvas.queryByText("Why it was noted")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Evidence")).not.toBeInTheDocument();
		await expect(canvas.getByText("Next step")).toBeVisible();
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
			feedbackResponse: {
				feedbackId: "00000000-0000-0000-0000-000000000102",
				usefulness: "HELPFUL",
				resolution: "ADDRESSED",
				comment: "Split the change into two commits so the reasoning reads on its own.",
			},
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
		await expect(canvas.getAllByText(strength.practiceName)).toHaveLength(1);
		await expect(canvas.getByText(strength.practiceName)).toBeVisible();
	},
};

/**
 * Nothing to open: the outcome line alone, and the row is no control — the chip is the one button,
 * the way a keyboard reaches its sentence. Nothing ranks a problem seen.
 */
export const OutcomeMatrix: Story = {
	render: (args) => (
		<>
			{outcomes.map((observation) => (
				<ReviewObservationRow key={observation.id} {...args} observation={observation} />
			))}
		</>
	),
	play: async ({ canvas }) => {
		const buttons = canvas.getAllByRole("button");
		await expect(buttons.map((button) => button.textContent)).toStrictEqual([
			"Needs improvement",
			"Risk avoided",
			"Expected practice missing",
			"Not applicable",
			"Undetermined",
		]);
		for (const button of buttons) {
			await expect(button).not.toHaveAttribute("aria-expanded");
		}
		await expect(canvas.queryByText("Major")).toBeNull();
		await expect(canvas.queryByText("Critical")).toBeNull();
	},
};

/**
 * A response on its way: every button waits, and the pressed one is the answer being written, not
 * the one it replaces — the row does not flick back to the old answer and forward again when it
 * lands.
 */
export const FeedbackPending: Story = {
	args: {
		observation: {
			...strength,
			feedbackResponse: {
				feedbackId: "00000000-0000-0000-0000-000000000102",
				resolution: "ADDRESSED",
			},
		},
	},
	// The write never lands here: the first response sent stays the one being written.
	render: (args) => (
		<Stateful<FeedbackResponseRequest | undefined> initial={args.pendingResponse}>
			{(pendingResponse, setPendingResponse) => (
				<ReviewObservationRow
					{...args}
					pendingResponse={pendingResponse}
					onRespond={(observation, response) => {
						args.onRespond?.(observation, response);
						setPendingResponse(response);
					}}
				/>
			)}
		</Stateful>
	),
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Not applicable" }));
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		await expect(args.onRespond).toHaveBeenCalledOnce();
		const response = canvas.getByRole("group", { name: "Your response" });
		await expect(within(response).getByRole("button", { name: "Not applicable" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(within(response).getByRole("button", { name: "Addressed" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		for (const button of within(response).getAllByRole("button")) {
			await expectGenuinelyDisabled(button);
		}
	},
};

/**
 * A press opens the band in the response's own tint. Skip records the response without a comment;
 * Send records it with the one written under it.
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
		await expect(args.onRespond).toHaveBeenLastCalledWith(strength, {
			usefulness: "HELPFUL",
			resolution: "ADDRESSED",
			comment: undefined,
		});
		await expect(canvas.queryByRole("textbox")).toBeNull();

		await userEvent.click(canvas.getByRole("button", { name: "Not applicable" }));
		await userEvent.type(
			canvas.getByRole("textbox", { name: "Anything to add?" }),
			"Adopted in the follow-up.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onRespond).toHaveBeenLastCalledWith(strength, {
			usefulness: "HELPFUL",
			resolution: "NOT_APPLICABLE",
			comment: "Adopted in the follow-up.",
		});
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
			usefulness: "HELPFUL",
			resolution: "DISPUTED",
			comment: "The value is derived, not chosen.",
		});
	},
};

/** The chip explains its outcome on hover with the registry's sentence. */
export const OutcomeExplained: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.hover(canvas.getByText("Strength shown"));
		await expectSettledVisible(await screen.findByText(/worth keeping/u));
	},
};

/**
 * At 320px the summary takes the line, and the outcome and the badges — here the origin of a row
 * the review did not raise live — wrap under it.
 */
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
 * it read in the registry's words, and how far it reached — so an expected practice reported
 * missing can be judged against the search that reported it.
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
		await expect(
			canvas.getByText("The code changes and Files and history in the repository"),
		).toBeVisible();
		// The boundary says how far the search reached, so the term says the same.
		await expect(canvas.getByText("How far it reached:")).toBeVisible();
		await expect(
			canvas.getByText(/^every test file the diff touches/u, { selector: "p" }),
		).toBeVisible();
		// The reviewer writes Markdown: what it quotes is code, not a line of stray backticks.
		const rationale = canvas.getByText(/^The branch is new in this change/u, { selector: "p" });
		await expect(within(rationale).getByText("loadFromCache").tagName).toBe("CODE");
		await expect(canvas.queryByText(/`/u)).toBeNull();
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
