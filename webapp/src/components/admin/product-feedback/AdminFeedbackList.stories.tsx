import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { FeedbackItem } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";

import { AdminFeedbackList } from "./AdminFeedbackList";
import {
	bugReport,
	draftingFeedback,
	filedPrivateFeedback,
	filedPublicFeedback,
	openFeedback,
	resolvedBugReport,
	resolvedFeedback,
} from "./fixtures";

const meta = {
	component: AdminFeedbackList,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	args: { pendingIds: new Set<string>(), onTriage: fn(), onFile: fn() },
	argTypes: { pendingIds: { control: false } },
	tags: ["autodocs"],
} satisfies Meta<typeof AdminFeedbackList>;

export default meta;
type Story = StoryObj<typeof meta>;

const ready = (
	items: FeedbackItem[],
	filter: "OPEN" | "IN_FLIGHT" | "ON_GITHUB" | "RESOLVED" | "ALL",
	onPageChange = fn(),
) => ({ status: "ready" as const, items, filter, page: 0, totalPages: 1, onPageChange });

export const Open: Story = {
	args: { state: ready(openFeedback, "OPEN") },
	play: async ({ canvas, args }) => {
		await expect(canvas.getAllByText("Bug")).toHaveLength(1);
		await expect(canvas.getAllByText("Idea")).toHaveLength(1);
		await expect(canvas.getAllByText("General")).toHaveLength(2);
		// An erased account and a submission from outside any workspace still read as sentences.
		await expect(canvas.getByText("Deleted account")).toBeVisible();
		await expect(canvas.getAllByText("No workspace")).toHaveLength(1);
		await expect(canvas.getAllByRole("button", { name: "Mark resolved" })).toHaveLength(4);
		// The first card is the bug report, so the item handed back is the one the reader pressed on.
		const [firstCard] = canvas.getAllByRole("listitem");
		if (firstCard) {
			await userEvent.click(within(firstCard).getByRole("button", { name: "Mark resolved" }));
		}
		await expect(args.onTriage).toHaveBeenCalledWith(bugReport, true);
	},
};

/** Hephaestus is still working, so an admin has nothing to press. */
export const OnItsWay: Story = {
	args: { state: ready([draftingFeedback], "IN_FLIGHT") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Drafting")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Mark resolved" })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: "File on GitHub" })).not.toBeInTheDocument();
	},
};

export const NothingOnItsWay: Story = {
	args: { state: ready([], "IN_FLIGHT") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing on its way")).toBeVisible();
	},
};

/** An admin sees the issue, also a private one, with the words the reporter sees. */
export const OnGitHub: Story = {
	args: { state: ready([filedPublicFeedback, filedPrivateFeedback], "ON_GITHUB") },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /Public issue #412/u })).toBeVisible();
		await expect(canvas.getByRole("link", { name: /Private issue #18/u })).toBeVisible();
		await expect(
			canvas.getByText(/Private because it may describe a security problem/u),
		).toBeVisible();
		await expect(canvas.getAllByRole("button", { name: "The issue as filed" })).toHaveLength(2);
	},
};

export const NothingOnGitHub: Story = {
	args: { state: ready([], "ON_GITHUB") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No open issues")).toBeVisible();
	},
};

/** A saved report that a destination can take now. */
export const CanFile: Story = {
	args: { state: ready([{ ...bugReport, canFile: true }], "OPEN") },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "File on GitHub" }));
		await expect(args.onFile).not.toHaveBeenCalled();
		const confirm = within(await screen.findByRole("alertdialog"));
		await userEvent.click(confirm.getByRole("button", { name: "File on GitHub" }));
		await expect(args.onFile).toHaveBeenCalledOnce();
	},
};

/** Its author was told only about the admins, so it moves as written and no model reads it. */
export const SavedBeforeDisclosure: Story = {
	args: { state: ready([{ ...bugReport, canFile: true, savedBeforeDisclosure: true }], "OPEN") },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "File on GitHub" }));
		const confirm = within(await screen.findByRole("alertdialog"));
		await expect(confirm.getByText(/No AI model reads it/u)).toBeVisible();
	},
};

export const Filing: Story = {
	args: {
		state: ready([{ ...bugReport, canFile: true }], "OPEN"),
		filingIds: new Set([bugReport.id]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Filing…" })).toBeDisabled();
	},
};

export const Resolved: Story = {
	args: { state: ready(resolvedFeedback, "RESOLVED") },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText(/Resolved by Ada Lovelace/u)).toBeVisible();
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
		await expect(canvas.getAllByRole("button", { name: "Mark resolved" })).toHaveLength(3);
	},
};

export const Empty: Story = {
	args: { state: ready([], "OPEN") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing waits for you")).toBeVisible();
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
