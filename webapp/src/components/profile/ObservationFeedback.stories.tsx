import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-state-defs";
import { ownFeedback } from "@/stories/practice-detail-story-mock-data";
import { daysBefore } from "@/stories/story-clock";

import { ObservationFeedback } from "./ObservationFeedback";

const COMMENT_URL = "https://github.com/acme/checkout/pull/902#discussion_r1";

const onWork = ownFeedback("00000000-0000-4000-8000-0000000005a1", {
	copies: [
		{
			id: "00000000-0000-4000-8000-0000000005b1",
			kind: "LINE_NOTE",
			externalUrl: COMMENT_URL,
			postedAt: daysBefore(2),
		},
	],
});
const onPage = ownFeedback("00000000-0000-4000-8000-0000000005a2", {
	destination: "PRACTICE_PAGE",
});
const inConversation = ownFeedback("00000000-0000-4000-8000-0000000005a3", {
	destination: "CONVERSATION",
});

const meta = {
	component: ObservationFeedback,
	args: {
		feedback: [onWork, onPage, inConversation],
		links: {
			practicePage: (feedbackId) => <a href={`#feedback-${feedbackId}`} />,
			conversation: <a href="#conversation" />,
		},
	},
	argTypes: { links: { control: false } },
	tags: ["autodocs"],
} satisfies Meta<typeof ObservationFeedback>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Delivered feedback at each destination, each with the way to read it where it is. */
export const Delivered: Story = {
	play: async ({ canvas }) => {
		const pieces = within(canvas.getByRole("list", { name: "Feedback from this observation" }))
			.getAllByRole("listitem")
			.map((piece) => within(piece));
		await expect(pieces).toHaveLength(3);
		const [work, page, conversation] = pieces;
		for (const piece of pieces) {
			await expect(piece.getByText(DELIVERY_STATE_DEFS.DELIVERED.label)).toBeVisible();
		}
		await expect(work?.getByText("On the work")).toBeVisible();
		await expect(
			work?.getByRole("link", { name: /Read the comment on the work/u }),
		).toHaveAttribute("href", COMMENT_URL);
		await expect(
			page?.getByRole("link", { name: "Read it on this practice’s Feedback tab" }),
		).toHaveAttribute("href", `#feedback-${onPage.id}`);
		await expect(
			conversation?.getByRole("link", { name: "Read it in your conversation with Heph" }),
		).toHaveAttribute("href", "#conversation");
	},
};

/**
 * Feedback that did not reach the reader says so in the delivery states every feedback surface uses,
 * and offers no way to read it in Hephaestus.
 */
export const NotDelivered: Story = {
	args: {
		feedback: [
			{ ...onPage, state: "READY", deliveredAt: null },
			{ ...inConversation, state: "WITHHELD", deliveredAt: null },
			ownFeedback("00000000-0000-4000-8000-0000000005a4", {
				destination: "PRACTICE_PAGE",
				state: "UNCONFIRMED",
				deliveredAt: null,
			}),
			ownFeedback("00000000-0000-4000-8000-0000000005a5", { state: "FAILED", deliveredAt: null }),
		],
	},
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list", { name: "Feedback from this observation" });
		for (const state of ["READY", "WITHHELD", "UNCONFIRMED", "FAILED"] as const) {
			await expect(within(list).getByText(DELIVERY_STATE_DEFS[state].label)).toBeVisible();
		}
		await expect(within(list).queryByText(DELIVERY_STATE_DEFS.DELIVERED.label)).toBeNull();
		await expect(within(list).queryByRole("link")).toBeNull();
	},
};

/** An observation that gave no feedback draws no block at all, not an empty list. */
export const Empty: Story = {
	args: { feedback: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("list")).toBeNull();
	},
};
