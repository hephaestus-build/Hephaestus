import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, waitFor } from "storybook/test";

import { STORY_NOW } from "@/stories/story-clock";

import { MessageActions } from "./MessageActions";

/**
 * MessageActions component provides contextual actions for chat messages.
 * Shows different actions based on message type: edit/copy for user messages, copy/vote for assistant messages.
 * Features hover-based visibility and proper vote state management.
 */
const meta = {
	component: MessageActions,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	argTypes: {
		className: {
			description: "Optional CSS class name",
			control: "text",
		},
		messageContentToCopy: {
			description: "The text content to copy",
			control: "text",
		},
		messageRole: {
			description: "The role of the message (user or assistant)",
			control: "select",
			options: ["user", "assistant", "system"],
		},
		vote: {
			description: "Current vote state for the message",
			control: "object",
		},
		isLoading: {
			description: "Whether actions are currently loading",
			control: "boolean",
		},
		isInEditMode: {
			description: "Whether the message is in edit mode",
			control: "boolean",
		},
		onCopy: {
			description: "Callback when copy action is triggered",
			control: false,
		},
		onVote: {
			description: "Callback when vote action is triggered (assistant messages only)",
			control: false,
		},
		onEdit: {
			description: "Callback when edit action is triggered (user messages only)",
			control: false,
		},
	},
	args: {
		className: undefined,
		messageContentToCopy: "This is a sample message that demonstrates the action buttons.",
		messageRole: "assistant",
		isLoading: false,
		isInEditMode: false,
		onCopy: fn(),
		onVote: fn(),
		onEdit: fn(),
	},
	decorators: [
		(Story) => (
			<div className="group/message max-w-md rounded-lg border p-4">
				<div className="mb-2 text-sm text-muted-foreground">
					Hover or use the keyboard to explore message actions
				</div>
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof MessageActions>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default assistant message actions with copy and vote buttons.
 */
export const AssistantMessage: Story = {
	play: async ({ canvas, userEvent }) => {
		const copy = canvas.getByRole("button", { name: "Copy message" });
		await userEvent.tab();
		await expect(copy).toHaveFocus();
		await waitFor(() => expect(copy).toBeVisible());
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

/**
 * User message actions with copy and edit buttons.
 */
export const UserMessage: Story = {
	args: {
		messageRole: "user",
		onVote: undefined, // User messages don't have vote functionality
	},
};

/**
 * Assistant message with an upvote applied.
 */
export const AssistantUpvoted: Story = {
	args: {
		vote: {
			messageId: "msg-1",
			isUpvoted: true,
			updatedAt: new Date(STORY_NOW),
		},
	},
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

/**
 * Assistant message with a downvote applied.
 */
export const AssistantDownvoted: Story = {
	args: {
		vote: {
			messageId: "msg-1",
			isUpvoted: false,
			updatedAt: new Date(STORY_NOW),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Good response" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await expect(canvas.getByRole("button", { name: "Bad response" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
	},
};

/**
 * User message in edit mode - actions are hidden.
 */
export const UserMessageInEditMode: Story = {
	args: {
		messageRole: "user",
		isInEditMode: true,
		onVote: undefined,
	},
};

/**
 * Loading state - actions are hidden.
 */
export const Loading: Story = {
	args: {
		isLoading: true,
	},
};

/**
 * Empty message text - actions are hidden.
 */
export const EmptyMessage: Story = {
	args: {
		messageContentToCopy: "",
	},
};

/**
 * Long message text for copy functionality.
 */
export const LongMessage: Story = {
	args: {
		messageContentToCopy: `This is a much longer message that demonstrates how the copy functionality works with substantial content. 

It includes multiple paragraphs, line breaks, and various types of content that might appear in a real chat conversation.

Here's some code:
\`\`\`javascript
function example() {
  console.log("Hello, world!");
}
\`\`\`

And here's a list:
1. First item
2. Second item  
3. Third item

This comprehensive message shows how the MessageActions component handles longer content while maintaining clean, accessible interactions.`,
	},
};
