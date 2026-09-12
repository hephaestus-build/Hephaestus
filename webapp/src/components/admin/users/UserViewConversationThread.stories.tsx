import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { ChatMessage } from "@/lib/types";

import { UserViewConversationThread } from "./UserViewConversationThread";

const messages: ChatMessage[] = [
	{
		id: "msg-1",
		role: "user",
		parts: [{ type: "text", text: "How do I split a large pull request?" }],
	},
	{
		id: "msg-2",
		role: "assistant",
		parts: [
			{
				type: "text",
				text: "Start from the reviewer's seat: each piece should be reviewable on its own, so cut along a boundary a reader can verify without the rest.",
			},
		],
	},
];

const meta = {
	component: UserViewConversationThread,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", messages },
		onBack: fn(),
	},
} satisfies Meta<typeof UserViewConversationThread>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("log")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Back to conversations" }));
		await expect(args.onBack).toHaveBeenCalledOnce();
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", messages: [] } },
};

export const Loading: Story = { args: { state: { status: "loading" } } };

export const Unreadable: Story = {
	args: { state: { status: "unreadable" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This conversation could not be displayed")).toBeVisible();
	},
};

const retry = fn();

export const LoadFailed: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 503, detail: "User view audit is unavailable" },
			onRetry: retry,
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalledOnce();
	},
};
