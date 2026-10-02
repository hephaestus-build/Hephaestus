import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { STORY_NOW } from "@/stories/story-clock";

import { CONVERSATION, REPLY_WITH_FEEDBACK, hephReply, userMessage } from "./fixtures";
import { MentorMessage } from "./MentorMessage";

const meta = {
	component: MentorMessage,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<div className="mx-auto max-w-3xl">
				<Story />
			</div>
		),
	],
	args: {
		message: hephReply(
			"reply",
			"Your team asks for pull request descriptions that **explain significant decisions**.",
		),
		onMessageEdit: fn(),
		onCopy: fn(),
		onVote: fn(),
	},
} satisfies Meta<typeof MentorMessage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Reply: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Heph")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Good response" }));
		await expect(args.onVote).toHaveBeenCalledWith("reply", true);
	},
};

/** Headings, a list, a task list and code, as Heph writes them. */
export const FormattedReply: Story = {
	args: { message: CONVERSATION[3] ?? hephReply("turn-4", "") },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Decision" })).toBeVisible();
		await expect(canvas.getByRole("checkbox", { name: "Completed task" })).toBeChecked();
	},
};

/**
 * Feedback Heph gave about an observation renders in place with the reply's markdown, and is copied
 * with the prose around it. A link stored before links carried their feedback shows nothing.
 */
export const ReplyWithFeedback: Story = {
	args: { message: REPLY_WITH_FEEDBACK },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("the decision")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Copy message" }));
		await expect(args.onCopy).toHaveBeenCalledWith(
			"I looked at your latest pull request, **Cache the practice catalog**.\n" +
				"Your description names **the decision**, a five-minute cache, but not why it beat invalidating on write.\n" +
				"What made you pick the time-based cache?",
		);
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

/** Still arriving: Heph's mark is alive and the reply offers nothing to act on yet. */
export const Streaming: Story = {
	args: { streaming: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Copy message" })).toBeNull();
	},
};

export const Interrupted: Story = {
	args: {
		message: {
			...hephReply("reply", "Your team asks for pull request descriptions that"),
			metadata: { status: "interrupted" },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/This reply was interrupted before it finished/u)).toBeVisible();
	},
};

export const ReadOnly: Story = {
	args: { readonly: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Copy message" })).toBeNull();
	},
};

export const OwnMessage: Story = {
	args: {
		message: userMessage("question", "Can you show me what a better description looks like?"),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Edit message" })).toBeEnabled();
		await expect(canvas.queryByRole("button", { name: "Good response" })).toBeNull();
	},
};

/** Edit swaps the message for an editor holding its words; sending asks Heph again from there. */
export const EditingOwnMessage: Story = {
	args: OwnMessage.args,
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Edit message" }));
		const editor = canvas.getByRole("textbox", { name: "Edit message" });
		await expect(editor).toHaveFocus();
		await userEvent.clear(editor);
		await userEvent.type(editor, "Show me a better description for a cache change.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(args.onMessageEdit).toHaveBeenCalledWith(
			"question",
			"Show me a better description for a cache change.",
		);
		await expect(canvas.queryByRole("textbox", { name: "Edit message" })).toBeNull();
	},
};
