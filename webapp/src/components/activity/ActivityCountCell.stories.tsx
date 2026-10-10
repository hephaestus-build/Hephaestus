import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { withProvider, withStandardPage } from "@/stories/decorators";
import { settledPopup } from "@/stories/overlay";

import { ActivityCountCell } from "./ActivityCountCell";

const counts = {
	contributions: 17,
	pullRequestsOpened: 4,
	pullRequestsMerged: 3,
	pullRequestsReviewed: 12,
	peopleHelped: 9,
	issuesOpened: 1,
	comments: 0,
	activeWeeks: 5,
};

const meta = {
	component: ActivityCountCell,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { category: "pull-requests", counts, providerType: "GITHUB" },
} satisfies Meta<typeof ActivityCountCell>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Opened, then merged: each part in its provider icon and colour, named by its phrase. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: /^4 pull requests opened$/iu })).toBeVisible();
		await expect(canvas.getByRole("img", { name: /^3 .*merged/iu })).toBeVisible();
	},
};

/** Opened but none merged: the first part stands alone, with no dash beside it. */
export const FirstPartOnly: Story = {
	args: { counts: { ...counts, pullRequestsMerged: 0 } },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("img")).toHaveLength(1);
		await expect(canvas.queryByText("—")).not.toBeInTheDocument();
	},
};

/** Nothing at all is one dash for the whole cell, read as "None". */
export const Empty: Story = {
	args: { counts: { ...counts, pullRequestsOpened: 0, pullRequestsMerged: 0 } },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("—")).toHaveLength(1);
		canvas.getByText("None");
		await expect(canvas.queryAllByRole("img")).toHaveLength(0);
	},
};

/** Whose work the reviews were on: an icon and a number, spelled out in its name and its tooltip. */
export const PeopleHelped: Story = {
	args: { category: "reviews" },
	play: async ({ canvas, userEvent }) => {
		const helped = canvas.getByRole("img", { name: "Reviewed the work of 9 people" });
		await userEvent.hover(helped);
		await expect(await settledPopup()).toHaveTextContent("Reviewed the work of 9 people");
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: /merge requests? opened$/iu })).toBeVisible();
	},
};
