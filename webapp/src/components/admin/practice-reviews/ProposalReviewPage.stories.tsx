import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";
import { ProposalReviewPage } from "./ProposalReviewPage";
import { reviewFeedbackDetail, workspacePractices } from "./story-mock-data";

const feedback = {
	...reviewFeedbackDetail,
	deliveryState: "AWAITING_APPROVAL" as const,
	deliveredAt: undefined,
	placements: [],
	proposedPlacements: [
		{ type: "SUMMARY" as const, body: reviewFeedbackDetail.body ?? "Review summary" },
		{
			type: "INLINE" as const,
			body: "Catch the expected transport error and let programming errors surface.",
			path: "src/main/java/example/RetryService.java",
			startLine: 48,
		},
		{
			type: "INLINE" as const,
			body: "Explain why this branch changes behavior.",
			path: "src/main/java/example/ReviewHandler.java",
			startLine: 91,
			endLine: 94,
		},
	],
	reviewedRevision: "27f4e88c9f5a",
};

const meta = {
	title: "Workspace admin/Practice reviews/Proposal review",
	component: ProposalReviewPage,
	parameters: {
		layout: "padded",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		feedback,
		practices: workspacePractices,
		onApprove: fn(),
		onReject: fn(),
	},
} satisfies Meta<typeof ProposalReviewPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Ready: Story = {
	play: async ({ canvas, args }) => {
		const firstObservation = feedback.observations[0];
		if (!firstObservation) {
			throw new Error("The proposal story needs a supporting observation");
		}
		await expect(canvas.getByRole("heading", { name: /Feedback for/u })).toBeVisible();
		await expect(canvas.getByText("1 summary and 2 line comments")).toBeVisible();
		await expect(canvas.getByText("src/main/java/example/RetryService.java")).toBeVisible();
		await expect(
			canvas.getByRole("link", {
				name: firstObservation.summary,
			}),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Approve for delivery" }));
		await expect(args.onApprove).toHaveBeenCalledWith(feedback.id);
		await expectNoPageOverflow();
	},
};

export const PackageUnavailable: Story = {
	args: { feedback: { ...feedback, proposedPlacements: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("This review package is unavailable");
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Approve for delivery" }));
		await expect(canvas.getByRole("button", { name: "Reject feedback" })).toBeEnabled();
	},
};

export const NoObservations: Story = {
	args: { feedback: { ...feedback, observations: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No observations are linked to this review")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Approve for delivery" })).toBeEnabled();
	},
};

export const RejectingWithContext: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Reject feedback" }));
		const popover = await screen.findByRole("dialog");
		await userEvent.click(within(popover).getByText("Missing important context"));
		await userEvent.type(
			within(popover).getByLabelText("Note"),
			"The fallback path is not represented in the review.",
		);
		await userEvent.click(within(popover).getByRole("button", { name: "Reject feedback" }));
		await expect(args.onReject).toHaveBeenCalledWith(
			feedback.id,
			"MISSING_CONTEXT",
			"The fallback path is not represented in the review.",
		);
	},
};

export const Deciding: Story = {
	args: { isDeciding: true },
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Approve for delivery" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Reject feedback" }));
	},
};
