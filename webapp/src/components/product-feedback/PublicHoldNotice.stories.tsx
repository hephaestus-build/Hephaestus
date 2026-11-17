import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { minutesAfter, minutesBefore } from "@/stories/story-clock";

import { PublicHoldNotice } from "./PublicHoldNotice";

const meta = {
	component: PublicHoldNotice,
	args: { state: "HELD", filedAfter: minutesAfter(4), wantsPublic: true, reader: "reporter" },
	tags: ["autodocs"],
} satisfies Meta<typeof PublicHoldNotice>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The reporter reads when the report becomes public and what they can do until then. */
export const Held: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				/^It becomes a public issue at about \d{1,2}:\d{2} [ap]m \(in about 4 minutes\) unless you keep it private or edit it\. A last check can still keep it private\.$/u,
			),
		).toBeVisible();
	},
};

/** An admin reads the same time, with the reporter as the one who can still act. */
export const HeldForAdmin: Story = {
	args: { reader: "admin" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/unless the reporter keeps it private or edits it\./u),
		).toBeVisible();
	},
};

export const Drafting: Story = {
	args: { state: "DRAFTING", filedAfter: minutesAfter(3) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				/^Once drafted, it can become a public issue at about \d{1,2}:\d{2} [ap]m unless you keep it private\.$/u,
			),
		).toBeVisible();
	},
};

/** The hold ended while the draft was still being written: it files as soon as the draft is ready. */
export const DraftingPastHold: Story = {
	args: { state: "DRAFTING", filedAfter: minutesBefore(1) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Once drafted, it can become a public issue right away unless you keep it private.",
			),
		).toBeVisible();
	},
};

/** A private report is not on its way to a public issue, so the notice says nothing. */
export const Private: Story = {
	args: { state: "DRAFTING", wantsPublic: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/public issue/u)).not.toBeInTheDocument();
	},
};
