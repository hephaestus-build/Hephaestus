import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { AUTOMATION } from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";

import { ActivityAutomationList } from "./ActivityAutomationList";

const meta = {
	component: ActivityAutomationList,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { automation: AUTOMATION },
} satisfies Meta<typeof ActivityAutomationList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** In name order, with no position, and each says why it is automation. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("link").map((link) => link.textContent)).toStrictEqual([
			"dependabot[bot]",
			"Release Robot",
		]);
		await expect(canvas.getByText(/^Bot account/u)).toBeVisible();
		await expect(canvas.getByText(/^Treated as automation/u)).toBeVisible();
	},
};

export const Empty: Story = {
	args: { automation: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("link")).toHaveLength(0);
	},
};
