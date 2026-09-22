import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { DeliveryTrace } from "./DeliveryTrace";

const composedAt = new Date("2026-07-28T13:42:00Z");
const deliveredAt = new Date("2026-07-28T13:43:00Z");

const meta = {
	component: DeliveryTrace,
	parameters: { layout: "padded", chromatic: { viewports: [320, 768] } },
	tags: ["autodocs"],
	args: {
		feedback: {
			channel: "IN_CONTEXT",
			deliveryState: "DELIVERED",
			createdAt: composedAt,
			deliveredAt,
			placements: [{ id: "p1", placementType: "SUMMARY" }],
		},
	},
} satisfies Meta<typeof DeliveryTrace>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Delivered: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("Composed");
		canvas.getByText("Delivered");
		canvas.getByText(/As a summary comment on the work/u);
	},
};

export const Withheld: Story = {
	args: {
		feedback: {
			channel: "IN_CONTEXT",
			deliveryState: "SUPPRESSED",
			suppressionReason: "ARTIFACT_MERGED",
			createdAt: composedAt,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("The work moved on");
		canvas.getByText("The work was already merged, so a note on it would arrive too late.");
		canvas.getByText("Withheld");
	},
};

/** `PREPARED` carries no delivered timestamp, and names the moment its own lane is waiting for. */
export const PreparedForConversation: Story = {
	args: {
		feedback: {
			channel: "IN_CHAT",
			deliveryState: "PREPARED",
			createdAt: composedAt,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Prepared for conversation");
		canvas.getByText("In conversation");
	},
};

/**
 * The in-app lane waits on nobody: the developer opening their own practice pages is what delivers
 * it, so the row must not borrow the conversation lane's words and promise a chat that will never
 * happen to it. The operator sees this much; the composed text is withheld from them.
 */
export const PreparedForThePracticePages: Story = {
	args: {
		feedback: {
			channel: "IN_APP",
			deliveryState: "PREPARED",
			createdAt: composedAt,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Prepared for their practice pages");
		canvas.getByText("On their practice pages");
	},
};

export const DeliveredInConversation: Story = {
	args: {
		feedback: {
			channel: "IN_CHAT",
			deliveryState: "DELIVERED",
			createdAt: composedAt,
			deliveredAt,
			placements: [{ id: "p1", placementType: "CONVERSATION_TURN" }],
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Delivered in conversation");
		canvas.getByText(/As a turn in the conversation/u);
	},
};

/** A queue entry that timed out: the server stores it as an ordinary withholding. */
export const WithheldNeverRaised: Story = {
	args: {
		feedback: {
			channel: "IN_CHAT",
			deliveryState: "SUPPRESSED",
			suppressionReason: "CONVERSATION_EXPIRED",
			createdAt: composedAt,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Withheld, never raised");
		canvas.getByText("Housekeeping");
	},
};

/** A gate never had a say, so there is no middle step — the attempt itself failed. */
export const FailedToDeliver: Story = {
	args: {
		feedback: {
			channel: "IN_CONTEXT",
			deliveryState: "FAILED",
			createdAt: composedAt,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Failed to deliver");
	},
};

export const ManyInlineNotes: Story = {
	args: {
		feedback: {
			channel: "IN_CONTEXT",
			deliveryState: "DELIVERED",
			createdAt: composedAt,
			deliveredAt,
			placements: [
				{ id: "p1", placementType: "SUMMARY" },
				{ id: "p2", placementType: "INLINE" },
				{ id: "p3", placementType: "INLINE" },
				{ id: "p4", placementType: "INLINE" },
			],
		},
	},
	play: async ({ canvas, canvasElement }) => {
		canvas.getByText(/As a summary comment on the work/u);
		// Counted over the whole trace rather than queried: a repeat would render inside the same
		// paragraph and still satisfy a `getByText`.
		const inlinePhrases = canvasElement.textContent.match(/As an inline note on the work/gu) ?? [];
		await expect(inlinePhrases).toHaveLength(1);
	},
};

export const ReplacedByNewer: Story = {
	args: {
		feedback: {
			channel: "IN_CONTEXT",
			deliveryState: "SUPERSEDED",
			createdAt: composedAt,
			deliveredAt,
			placements: [{ id: "p1", placementType: "SUMMARY" }],
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Replaced by newer");
		canvas.getByText(/delivered/u);
	},
};

export const Narrow: Story = {
	args: {
		feedback: {
			channel: "IN_CONTEXT",
			deliveryState: "SUPPRESSED",
			suppressionReason: "VOLUME_CAPPED",
			createdAt: composedAt,
		},
	},
	parameters: { viewport: { defaultViewport: "reflow" } },
	render: (args) => (
		<div className="w-64 border p-3">
			<DeliveryTrace {...args} />
		</div>
	),
};
