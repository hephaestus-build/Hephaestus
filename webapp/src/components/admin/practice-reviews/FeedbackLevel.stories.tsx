import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import type { ReviewFeedbackDetail } from "@/api/types.gen";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { hoursBefore } from "@/stories/story-clock";
import { expectGenuinelyDisabled } from "@/test/controls";
import { levelsOpenedBy } from "@/test/detail-stack";
import { precedes } from "@/test/dom";

import { FeedbackLevel } from "./FeedbackLevel";
import {
	feedbackDetail,
	longFeedbackDetail,
	reviewFeedbackDetail,
	workspacePractices,
} from "./fixtures";
import { feedbackLevel } from "./review-levels";

const ready = (feedback: ReviewFeedbackDetail) => ({ status: "ready" as const, feedback });

const delivered = feedbackDetail("99999999-6666-6666-6666-666666666666");

const partiallyDelivered: ReviewFeedbackDetail = {
	...delivered,
	deliveryState: "PARTIALLY_DELIVERED",
	deliveredAt: undefined,
	placements: [{ id: "summary-placement", placementType: "SUMMARY", postedCommentRef: "2481933" }],
	proposedPlacements: [
		{ type: "SUMMARY", body: "Review summary" },
		{
			type: "INLINE",
			body: "Use the established retry boundary here.",
			path: "server/src/main/java/example/LongProviderBoundaryName.java",
			startLine: 118,
		},
	],
	approval: { decision: "APPROVED", actorAccountId: 7, decidedAt: hoursBefore(5) },
};

const rejected: ReviewFeedbackDetail = {
	...partiallyDelivered,
	deliveryState: "DISCARDED",
	placements: [],
	approval: {
		decision: "REJECTED",
		actorAccountId: 8,
		decidedAt: hoursBefore(4),
		rejectionReason: "MISSING_CONTEXT",
		rejectionNote: "The review did not account for the provider's retry contract.",
	},
};

/** A proposal: the exact summary and line comments approval would send, written against a revision. */
const awaitingApproval: ReviewFeedbackDetail = {
	...reviewFeedbackDetail,
	deliveryState: "AWAITING_APPROVAL",
	deliveredAt: undefined,
	suppressionReason: undefined,
	placements: [],
	proposedPlacements: [
		{ type: "SUMMARY", body: reviewFeedbackDetail.body ?? "Review summary" },
		{
			type: "INLINE",
			body: "Catch the expected transport error and let programming errors surface.",
			path: "src/main/java/example/RetryService.java",
			startLine: 48,
		},
		{
			type: "INLINE",
			body: "Explain why this branch changes behavior.",
			path: "src/main/java/example/ReviewHandler.java",
			startLine: 91,
			endLine: 94,
		},
	],
	reviewedRevision: "27f4e88c9f5a",
};

/**
 * One piece of feedback over whichever list it was opened from: who it is for, what it says, what
 * became of it and what it was based on.
 *
 * Feedback awaiting approval is the same level rather than a page of its own: the package is
 * expanded, because approving it unread is the failure to design out, and the decision is the
 * footer, so it is made where the feedback is read.
 */
const meta = {
	component: FeedbackLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		feedback: ready(reviewFeedbackDetail),
		practices: workspacePractices,
		isDeciding: false,
		onApprove: fn(),
		onReject: fn(),
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack entry={feedbackLevel(reviewFeedbackDetail.id)} path={args.path} size="detailWide">
			{(level) => <FeedbackLevel {...args} {...level} />}
		</InLevelStack>
	),
} satisfies Meta<typeof FeedbackLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A chip in the header: what became of the feedback, under the title, where standing goes. */
async function expectHeaderChip(panel: HTMLElement, label: string) {
	const [chip] = within(panel).getAllByText(label);
	if (!chip) {
		throw new Error(`No "${label}" on the level`);
	}
	await expect(precedes(within(panel).getByRole("heading", { level: 2 }), chip)).toBe(true);
}

export const NotDelivered: Story = {
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expect(panel.getByRole("heading", { level: 2 })).toHaveTextContent(/^Feedback for /u);
		await expectHeaderChip(panelElement, "Withheld");
		await expectHeaderChip(panelElement, "On the work");
		await expect(
			levelsOpenedBy(panel.getByRole("link", { name: "See everything reviewed on this work" })),
		).toEqual([expect.stringMatching(/^work:pull-request:/u)]);
		await expect(levelsOpenedBy(panel.getByRole("link", { name: "in a review" }))).toEqual([
			`review:${reviewFeedbackDetail.agentJobId}`,
		]);
		panel.getByText("Found while reviewing past work, which is measured but never sent.");
		// A record, not a proposal: nothing is left to decide.
		await expect(
			panel.queryByRole("button", { name: "Approve for delivery" }),
		).not.toBeInTheDocument();
	},
};

export const Delivered: Story = {
	args: { feedback: ready(delivered) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Delivered");
		panel.getByText(/As an inline note on the work/u);
		panel.getByText("server/application/src/main/resources/application.yml:118–120");
		await expect(panel.queryByRole("button", { name: "Reject feedback" })).not.toBeInTheDocument();
	},
};

export const PartiallyDelivered: Story = {
	args: { feedback: ready(partiallyDelivered) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("1 of 2 comments confirmed delivered")).toBeVisible();
		panel.getByText("Human decision");
		panel.getByText("Approved");
	},
};

export const Rejected: Story = {
	args: { feedback: ready(rejected) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expectHeaderChip(panelElement, "Rejected");
		panel.getByText("Human decision");
		// The category is the registry's words, not the stored value.
		await expect(panel.getByText("Missing important context")).toBeVisible();
		panel.getByText("The review did not account for the provider's retry contract.");
		await expect(panel.queryByText(/comments confirmed delivered/u)).not.toBeInTheDocument();
	},
};

/**
 * The longest realistic record, at the narrowest width: a rendered body with code, and every
 * observation it was based on.
 */
export const Reflow: Story = {
	args: { feedback: ready(longFeedbackDetail) },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

/** Feedback that replaced an earlier piece links to it, and each source observation opens over it. */
export const LongFeedback: Story = {
	args: { feedback: ready(longFeedbackDetail) },
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText(/2 issues to tighten in this change/u)).toBeVisible();
		await expect(
			levelsOpenedBy(
				panel.getByRole("link", {
					name: "A cache miss and a permission failure come back as the same 404",
				}),
			),
		).toEqual(["observation:66666666-6666-6666-6666-666666666666"]);
		panel.getByRole("link", {
			name: "Three of the new tests are named after the method they call",
		});
		await expect(
			levelsOpenedBy(panel.getByRole("link", { name: "See the feedback this replaced" })),
		).toEqual([expect.stringMatching(/^feedback:/u)]);
		await userEvent.click(panel.getByRole("tab", { name: "Source" }));
		await expect(panel.getByRole("tabpanel", { name: "Source" }).textContent).toContain("```java");
	},
};

export const PreparedForConversation: Story = {
	args: { feedback: ready(feedbackDetail("11111111-4444-4444-4444-444444444444")) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		await expectHeaderChip(panelElement, "Prepared for conversation");
		await expectHeaderChip(panelElement, "In conversation");
		within(panelElement).getByText("#engineering");
	},
};

/**
 * Awaiting approval, the level leads with what approval authorizes and ends in the decision. What
 * will be sent is expanded — one summary and every line comment — rather than behind a disclosure.
 */
export const AwaitingApproval: Story = {
	args: { feedback: ready(awaitingApproval) },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveTextContent(/^Feedback for /u);
		await expect(
			panel.getByText(/Approval authorizes the summary and every line comment below/u),
		).toBeVisible();
		await expect(panel.getByText("1 summary and 2 line comments")).toBeVisible();
		await expect(panel.getByText("src/main/java/example/RetryService.java")).toBeVisible();
		await expect(panel.getByText("27f4e88c9f5a")).toBeVisible();
		// The record's own sections wait until there is a record: nothing has become of it yet.
		await expect(
			panel.queryByRole("heading", { name: "What became of it" }),
		).not.toBeInTheDocument();
		const [firstObservation] = awaitingApproval.observations;
		if (!firstObservation) {
			throw new Error("The proposal story needs a supporting observation");
		}
		panel.getByRole("link", { name: firstObservation.summary });

		await userEvent.click(panel.getByRole("button", { name: "Approve for delivery" }));
		await expect(args.onApprove).toHaveBeenCalledOnce();
	},
};

export const AwaitingApprovalReflow: Story = {
	args: { feedback: ready(awaitingApproval) },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(within(panel).getByRole("button", { name: "Approve for delivery" })).toBeVisible();
		await expectNoPanelOverflow(panel);
	},
};

const PREVIOUS_ID = "aaaaaaaa-0000-4000-8000-000000000001";
const NEXT_ID = "aaaaaaaa-0000-4000-8000-000000000003";

/**
 * Opened from the approval queue: where it sits among all awaiting approval, oldest first, a step
 * either way that swaps this level for its neighbour, and a decision that moves on to the next.
 */
export const InApprovalQueue: Story = {
	args: {
		feedback: ready(awaitingApproval),
		queue: { position: 2, total: 7, previous: PREVIOUS_ID, next: NEXT_ID },
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		const steps = within(panel.getByRole("navigation", { name: "Feedback awaiting approval" }));
		steps.getByText("2 of 7");
		// A step replaces the level in front rather than stacking one over it.
		await expect(levelsOpenedBy(steps.getByRole("link", { name: "Previous" }))).toEqual([
			`feedback:${PREVIOUS_ID}`,
		]);
		await expect(levelsOpenedBy(steps.getByRole("link", { name: "Next" }))).toEqual([
			`feedback:${NEXT_ID}`,
		]);
		await userEvent.click(panel.getByRole("button", { name: "Approve and next" }));
		await expect(args.onApprove).toHaveBeenCalledOnce();
	},
};

/**
 * The last in the queue, with no step forward — but the ones before it may have been skipped on the
 * way here and still wait, so a decision moves on to them and the button does not promise to close.
 */
export const LastInApprovalQueue: Story = {
	args: {
		feedback: ready(awaitingApproval),
		queue: { position: 7, total: 7, previous: PREVIOUS_ID },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("7 of 7");
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Next" }));
		panel.getByRole("button", { name: "Approve and next" });
	},
};

/** The only one awaiting approval: no steps to take, and approving it closes the level. */
export const OnlyInApprovalQueue: Story = {
	args: {
		feedback: ready(awaitingApproval),
		queue: { position: 1, total: 1 },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.queryByRole("navigation", { name: "Feedback awaiting approval" }),
		).not.toBeInTheDocument();
		panel.getByRole("button", { name: "Approve and close" });
	},
};

const top = (element: HTMLElement) => element.getBoundingClientRect().top;

export const InApprovalQueueReflow: Story = {
	args: InApprovalQueue.args,
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(within(panel).getByRole("button", { name: "Approve and next" })).toBeVisible();
		await expectNoPanelOverflow(panel);
		// The stacked footer shows the steps first, as the tab order reaches them first.
		const steps = within(panel).getByRole("navigation", { name: "Feedback awaiting approval" });
		await expect(top(steps)).toBeLessThan(
			top(within(panel).getByRole("button", { name: "Approve and next" })),
		);
		await expect(top(steps)).toBeLessThan(
			top(within(panel).getByRole("button", { name: "Reject feedback" })),
		);
	},
};

/** A proposal with no package has nothing to authorize, so only rejection is offered. */
export const PackageUnavailable: Story = {
	args: { feedback: ready({ ...awaitingApproval, proposedPlacements: [] }) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("alert")).toHaveTextContent("This review package is unavailable");
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Approve for delivery" }));
		await expect(panel.getByRole("button", { name: "Reject feedback" })).toBeEnabled();
	},
};

/** Nothing to check it against is worth saying, and is not a reason to withhold the decision. */
export const AwaitingApprovalWithoutObservations: Story = {
	args: { feedback: ready({ ...awaitingApproval, observations: [] }) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("No observations are linked to this feedback")).toBeVisible();
		await expect(panel.getByRole("button", { name: "Approve for delivery" })).toBeEnabled();
	},
};

export const RejectingWithContext: Story = {
	args: { feedback: ready(awaitingApproval) },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Reject feedback" }));
		const popover = await screen.findByRole("dialog", { name: "Reject this feedback" });
		const note = within(popover).getByLabelText("Note");
		await expectSettledVisible(note);
		// No category, no rejection: the reason is what the rejection is for.
		await expectGenuinelyDisabled(within(popover).getByRole("button", { name: "Reject feedback" }));
		await userEvent.click(within(popover).getByText("Missing important context"));
		await userEvent.type(note, "The fallback path is not represented in the review.");
		await userEvent.click(within(popover).getByRole("button", { name: "Reject feedback" }));
		await expect(args.onReject).toHaveBeenCalledWith(
			"MISSING_CONTEXT",
			"The fallback path is not represented in the review.",
		);
		await expect(args.onApprove).not.toHaveBeenCalled();
	},
};

export const Deciding: Story = {
	args: { feedback: ready(awaitingApproval), isDeciding: true },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Approve for delivery" }));
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Reject feedback" }));
	},
};

/**
 * The drawer is named by its title, so the heading stands while the record loads; the path stays
 * usable, and no decision is offered about feedback not yet read.
 */
export const Loading: Story = {
	args: { feedback: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveAccessibleName("Loading feedback");
		panel.getByRole("button", { name: "Practice reviews" });
		await expect(panel.queryByText("Couldn't load this feedback")).not.toBeInTheDocument();
		await expect(
			panel.queryByRole("button", { name: "Approve for delivery" }),
		).not.toBeInTheDocument();
	},
};

export const LoadFailed: Story = {
	args: {
		feedback: {
			status: "error",
			error: { status: 500, detail: "Something went wrong." },
			onRetry: fn(),
		},
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Couldn't load this feedback")).toBeVisible();
		// With no record to name it, the level is named for what it is.
		await expect(screen.getByRole("dialog")).toHaveAccessibleName("Feedback");
		await userEvent.click(panel.getByRole("button", { name: "Retry" }));
		if (args.feedback.status !== "error") {
			throw new Error("This story is the error branch");
		}
		await expect(args.feedback.onRetry).toHaveBeenCalledOnce();
	},
};
