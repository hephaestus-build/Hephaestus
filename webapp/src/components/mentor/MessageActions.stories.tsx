import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor } from "storybook/test";

import { STORY_NOW } from "@/stories/story-clock";

import { MessageActions } from "./MessageActions";

const meta = {
	component: MessageActions,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		content: "Your team asks for pull request descriptions that explain significant decisions.",
		onCopy: fn(),
		onVote: fn(),
	},
	decorators: [
		(Story) => (
			// The row reveals itself on the message's hover, so the story stands in for the message.
			<div className="group/message">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof MessageActions>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Under Heph's reply: copy and the two votes, reachable from the keyboard though hidden at rest. */
export const OnReply: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const copy = canvas.getByRole("button", { name: "Copy message" });
		await userEvent.tab();
		await expect(copy).toHaveFocus();
		await waitFor(async () => expect(copy).toBeVisible());
		await userEvent.keyboard("{Enter}");
		await expect(args.onCopy).toHaveBeenCalledWith(
			"Your team asks for pull request descriptions that explain significant decisions.",
		);
		await expect(canvas.getByRole("button", { name: "Good response" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await expect(canvas.getByRole("button", { name: "Bad response" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
	},
};

/** Under the reader's own message: copy and edit, and no votes. */
export const OnOwnMessage: Story = {
	args: { onVote: undefined, onEdit: fn() },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Edit message" }));
		await expect(args.onEdit).toHaveBeenCalledOnce();
		await expect(canvas.queryByRole("button", { name: "Good response" })).toBeNull();
	},
};

export const Upvoted: Story = {
	args: { vote: { messageId: "reply", isUpvoted: true, updatedAt: new Date(STORY_NOW) } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Good response" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(canvas.getByRole("button", { name: "Bad response" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
	},
};

export const Downvoted: Story = {
	args: { vote: { messageId: "reply", isUpvoted: false, updatedAt: new Date(STORY_NOW) } },
	play: async ({ args, canvas, userEvent }) => {
		const bad = canvas.getByRole("button", { name: "Bad response" });
		await expect(bad).toHaveAttribute("aria-pressed", "true");
		await userEvent.click(canvas.getByRole("button", { name: "Good response" }));
		await expect(args.onVote).toHaveBeenCalledWith(true);
	},
};
