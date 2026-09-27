import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { MEMBERS } from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";

import { MemberActivityList } from "./MemberActivityList";

const meta = {
	component: MemberActivityList,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: { status: "ready", members: MEMBERS },
		providerType: "GITHUB",
		range: "7d",
	},
} satisfies Meta<typeof MemberActivityList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// In the order given, which is by name: the most active member stays second.
		const names = canvas.getAllByRole("link").map((link) => link.textContent);
		await expect(names).toStrictEqual(["Ada Lovelace", "Bob Brenner", "Chen Wei"]);
		await expect(canvas.getByRole("link", { name: "Chen Wei" })).toHaveAccessibleDescription(
			"No activity in the last 7 days",
		);
		await expect(canvas.getByRole("link", { name: "Bob Brenner" })).toHaveAccessibleDescription(
			/Pull requests: five opened and four merged\s*Reviews: six approvals, two change requests and one comment-only review/u,
		);
	},
};

export const NoMembers: Story = {
	args: { state: { status: "ready", members: [] } },
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
};
