import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";
import { expectNoPageOverflow } from "@/stories/reflow";
import { FeedbackDetailPage } from "./FeedbackDetailPage";
import {
	feedbackDetail,
	longFeedbackDetail,
	reviewFeedbackDetail,
	workspacePractices,
} from "./fixtures";

const partiallyDeliveredFeedback = {
	...feedbackDetail("99999999-6666-6666-6666-666666666666"),
	deliveryState: "PARTIALLY_DELIVERED" as const,
	deliveredAt: undefined,
	placements: [
		{ id: "summary-placement", placementType: "SUMMARY" as const, postedCommentRef: "2481933" },
	],
	proposedPlacements: [
		{ type: "SUMMARY" as const, body: "Review summary" },
		{
			type: "INLINE" as const,
			body: "Use the established retry boundary here.",
			path: "server/src/main/java/example/LongProviderBoundaryName.java",
			startLine: 118,
		},
	],
	approval: {
		decision: "APPROVED" as const,
		actorAccountId: 7,
		decidedAt: new Date("2026-08-26T08:30:00Z"),
	},
};

const rejectedFeedback = {
	...partiallyDeliveredFeedback,
	deliveryState: "DISCARDED" as const,
	placements: [],
	approval: {
		decision: "REJECTED" as const,
		actorAccountId: 8,
		decidedAt: new Date("2026-08-26T09:00:00Z"),
		rejectionReason: "MISSING_CONTEXT" as const,
		rejectionNote: "The review did not account for the provider's retry contract.",
	},
};

const meta = {
	component: FeedbackDetailPage,
	parameters: {
		layout: "padded",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		search: {
			agentJobId: reviewFeedbackDetail.agentJobId,
			deliveryState: undefined,
			withheldFamily: undefined,
			channel: undefined,
		},
		state: { status: "ready", feedback: reviewFeedbackDetail },
		practices: workspacePractices,
	},
} satisfies Meta<typeof FeedbackDetailPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NotDelivered: Story = {
	play: async ({ canvas }) => {
		await expect(await canvas.findAllByText("Withheld")).toHaveLength(2);
		canvas.getByRole("link", { name: "See everything reviewed on this work" });
		canvas.getByRole("link", { name: "in a review" });
		await expect(canvas.queryByText("Technical details")).not.toBeInTheDocument();
		canvas.getByText("Policy kept it quiet");
		canvas.getByText("Found while reviewing past work, which is measured but never sent.");
		await expectNoPageOverflow();
	},
};

export const Delivered: Story = {
	args: {
		state: { status: "ready", feedback: feedbackDetail("99999999-6666-6666-6666-666666666666") },
	},
	play: async ({ canvas }) => {
		await expect(await canvas.findAllByText("Delivered")).toHaveLength(1);
		canvas.getByText(/As an inline note on the work/u);
		canvas.getByText("server/application/src/main/resources/application.yml:118–120");
	},
};

export const PartiallyDelivered: Story = {
	args: { state: { status: "ready", feedback: partiallyDeliveredFeedback } },
	play: async ({ canvas }) => {
		await canvas.findByText("1 of 2 comments confirmed delivered");
		canvas.getByText("Human decision");
		canvas.getByText("Approved");
		await expectNoPageOverflow();
	},
};

export const Rejected: Story = {
	args: { state: { status: "ready", feedback: rejectedFeedback } },
	play: async ({ canvas }) => {
		const audit = canvas.getByText("Human decision").parentElement;
		if (!audit) {
			throw new Error("Human decision did not render in its audit card");
		}
		await expect(within(audit).getByText("Rejected")).toBeVisible();
		within(audit).getByText("Missing important context");
		within(audit).getByText("The review did not account for the provider's retry contract.");
		await expect(canvas.queryByText(/comments confirmed delivered/u)).not.toBeInTheDocument();
	},
};

export const NoObservations: Story = {
	args: { state: { status: "ready", feedback: { ...reviewFeedbackDetail, observations: [] } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No observations are linked to this feedback")).toBeVisible();
	},
};

export const LongFeedback: Story = {
	args: { state: { status: "ready", feedback: longFeedbackDetail } },
	parameters: { chromatic: { viewports: [320, 1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText(/2 issues to tighten in this change/u);
		canvas.getByText(/without HTTP\./u);
		canvas.getByText(/repository\.findVisible/u);
		canvas.getByRole("link", {
			name: "A cache miss and a permission failure come back as the same 404",
		});
		canvas.getByRole("link", {
			name: "Three of the new tests are named after the method they call",
		});
		canvas.getByText("What this feedback is about");
		canvas.getByText("Supporting this feedback");
		await expectNoPageOverflow();
	},
};

export const PreparedForConversation: Story = {
	args: {
		state: { status: "ready", feedback: feedbackDetail("11111111-4444-4444-4444-444444444444") },
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findAllByText(/for conversation/u);
		canvas.getByText("How should we roll back the pricing migration?");
	},
};

export const RenderedAndSource: Story = {
	args: { state: { status: "ready", feedback: longFeedbackDetail } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByRole("link", { name: "See the feedback this replaced" });
		await canvas.findByText(/2 issues to tighten in this change/u);
		await userEvent.click(canvas.getByRole("tab", { name: "Source" }));
		await expect(canvas.getByRole("tabpanel", { name: "Source" }).textContent).toContain("```java");
		await userEvent.click(canvas.getByRole("tab", { name: "Rendered" }));
		await expect(canvas.getByRole("tabpanel", { name: "Rendered" }).textContent).toContain(
			"2 issues to tighten in this change",
		);
	},
};

export const PracticeSaysWhatItIs: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas, userEvent }) => {
		const productLanguage = workspacePractices.find((p) => p.slug === "product-language");
		if (!productLanguage) {
			throw new Error("The practice fixtures no longer cover product-language");
		}
		await userEvent.hover(await canvas.findByRole("link", { name: /Product language/u }));
		await screen.findByText(productLanguage.whyItMatters ?? "");
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("link", { name: "Delivery" });
		await expect(canvas.queryByText("Couldn't load this feedback")).not.toBeInTheDocument();
	},
};

export const LoadFailed: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 500, detail: "Something went wrong." },
			onRetry: fn(),
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("Couldn't load this feedback");
	},
};
