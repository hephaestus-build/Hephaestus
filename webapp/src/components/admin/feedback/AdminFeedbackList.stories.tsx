import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { FeedbackItem } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";

import { AdminFeedbackList } from "./AdminFeedbackList";
import {
	bugReport,
	openFeedback,
	resolvedBugReport,
	resolvedFeedback,
} from "./product-feedback-fixtures";

const meta = {
	title: "Instance admin/Product feedback/Feedback inbox",
	component: AdminFeedbackList,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	args: { pendingIds: new Set<string>(), onTriage: fn() },
	argTypes: { pendingIds: { control: false } },
	tags: ["autodocs"],
} satisfies Meta<typeof AdminFeedbackList>;

export default meta;
type Story = StoryObj<typeof meta>;

const ready = (
	items: FeedbackItem[],
	filter: "OPEN" | "RESOLVED" | "ALL",
	onPageChange = fn(),
) => ({ status: "ready" as const, items, filter, page: 0, totalPages: 1, onPageChange });

export const Open: Story = {
	args: { state: ready(openFeedback, "OPEN") },
	play: async ({ canvas, args }) => {
		// The registry decides the words; the item only carried the wire constant.
		await expect(canvas.getAllByText("Bug report")).toHaveLength(1);
		await expect(canvas.getAllByText("Feedback")).toHaveLength(2);
		// An erased account and a submission from outside any workspace still read as sentences.
		await expect(canvas.getByText("Deleted account")).toBeVisible();
		await expect(canvas.getAllByText("No workspace")).toHaveLength(1);
		const buttons = canvas.getAllByRole("button", { name: "Mark resolved" });
		await expect(buttons).toHaveLength(3);
		// The first card is the bug report, so the item handed back is the one the reader pressed on.
		for (const button of buttons.slice(0, 1)) await userEvent.click(button);
		await expect(args.onTriage).toHaveBeenCalledWith(bugReport, true);
	},
};

export const Resolved: Story = {
	args: { state: ready(resolvedFeedback, "RESOLVED") },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText(/Resolved by Ada Lovelace/)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Reopen" }));
		await expect(args.onTriage).toHaveBeenCalledWith(resolvedBugReport, false);
	},
};

export const Saving: Story = {
	args: {
		state: ready(openFeedback, "OPEN"),
		pendingIds: new Set([bugReport.id]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Saving…" })).toBeDisabled();
		await expect(canvas.getAllByRole("button", { name: "Mark resolved" })).toHaveLength(2);
	},
};

export const Empty: Story = {
	args: { state: ready([], "OPEN") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Inbox zero")).toBeVisible();
	},
};

export const EmptyAll: Story = {
	args: { state: ready([], "ALL") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No feedback yet")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const Error: Story = {
	args: { state: { status: "error", error: new TypeError("Failed to fetch"), onRetry: fn() } },
};
