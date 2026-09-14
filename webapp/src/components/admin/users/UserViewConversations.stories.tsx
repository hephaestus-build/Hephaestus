import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { ChatThreadSummary } from "@/api/types.gen";
import { daysBefore } from "@/components/common/story-clock";
import { expectNoPageOverflow } from "@/test/reflow";

import { UserViewConversations } from "./UserViewConversations";

const threads: ChatThreadSummary[] = [
	{ id: "thread-1", title: "How do I split a large pull request?", createdAt: daysBefore(2) },
	{ id: "thread-2", createdAt: daysBefore(9) },
];

const retry = fn();

const meta = {
	component: UserViewConversations,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", threads, totalPages: 1 },
		page: 0,
		onPageChange: fn(),
		onOpen: fn(),
	},
} satisfies Meta<typeof UserViewConversations>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Untitled conversation/ }));
		await expect(args.onOpen).toHaveBeenCalledWith("thread-2");
	},
};

export const Paged: Story = {
	args: { state: { status: "ready", threads, totalPages: 3 }, page: 0 },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Go to page 2" }));
		await expect(args.onPageChange).toHaveBeenCalledWith(1);
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", threads: [], totalPages: 0 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No existing conversations")).toBeVisible();
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

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

export const LongTitle: Story = {
	args: {
		state: {
			status: "ready",
			threads: [
				{
					id: "thread-3",
					title:
						"Why does the reviewer keep asking me to split a change that only touches the migration, the entity and the endpoint that depend on each other?",
					createdAt: daysBefore(1),
				},
			],
			totalPages: 1,
		},
	},
};

export const Reflow: Story = {
	args: { state: { status: "ready", threads, totalPages: 3 } },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
