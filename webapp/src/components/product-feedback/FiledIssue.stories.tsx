import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { FiledIssue } from "./FiledIssue";

const meta = {
	component: FiledIssue,
	args: {
		title: "Practice page jumps to the top when the filter changes",
		text: "## Problem\n\nChanging the team filter scrolls the page back to the top.\n\n## Expected result\n\nThe page keeps its position.",
	},
	tags: ["autodocs"],
} satisfies Meta<typeof FiledIssue>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Closed until the reader asks, so a list of reports stays short. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Expected result")).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "The issue as filed" }));
		await expectSettledVisible(await canvas.findByText("Expected result"));
	},
};

/** A long unbroken string wraps inside the card at 320 pixels. */
export const LongText: Story = {
	args: {
		title:
			"https://example.org/a/very/long/address/that/has/no/spaces/at/all/and/keeps/going/and/going",
		text: `Seen on ${"/w/acme/practices/".repeat(12)}`,
	},
	decorators: [
		(Story) => (
			<div className="w-full max-w-80">
				<Story />
			</div>
		),
	],
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "The issue as filed" }));
		await expectSettledVisible(await canvas.findByText(/Seen on/u));
	},
};
