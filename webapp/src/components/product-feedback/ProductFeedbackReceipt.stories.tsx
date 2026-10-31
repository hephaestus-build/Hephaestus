import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { minutesAfter } from "@/stories/story-clock";

import {
	draftingReport,
	emailedReport,
	filingReport,
	heldReport,
	privateIssue,
	publicIssue,
	securityReport,
	stoppedReport,
} from "./fixtures";
import { ProductFeedbackReceipt } from "./ProductFeedbackReceipt";

const meta = {
	component: ProductFeedbackReceipt,
	args: {
		report: heldReport,
		publicRepository: "hephaestus-build/Hephaestus",
		onKeepPrivate: fn(),
		onEdit: fn(async () => true),
	},
	decorators: [
		(Story) => (
			<div className="max-w-lg p-4">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackReceipt>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Held: the countdown names the repository, and both reverses are offered until filing. */
export const Default: Story = {
	play: async ({ canvas, args }) => {
		await expect(
			canvas.getByText(
				"Thanks. It’s saved. Unless a check keeps it private, it becomes a public issue in hephaestus-build/Hephaestus in about 4 minutes.",
			),
		).toBeVisible();
		await expect(canvas.getByText("Removed: 1 email address")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Keep private" }));
		await expect(args.onKeepPrivate).toHaveBeenCalledOnce();
	},
};

/** Edit opens the drafted title and description in place, and Save sends both. */
export const Editing: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Edit" }));
		const title = canvas.getByRole("textbox", { name: "Issue title" });
		await userEvent.clear(title);
		await userEvent.type(title, "Filter change resets the scroll");
		await userEvent.click(canvas.getByRole("button", { name: "Save issue" }));
		await expect(args.onEdit).toHaveBeenCalledWith({
			title: "Filter change resets the scroll",
			description: heldReport.draftDescription,
		});
	},
};

export const KeepingPrivate: Story = {
	args: { pending: "keep-private" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Keeping private…" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Edit" })).toBeDisabled();
	},
};

/** While the model drafts, the hold still counts down; there is nothing to edit yet. */
export const Drafting: Story = {
	args: { report: { ...draftingReport, filedAfter: minutesAfter(5) } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Drafting the issue…")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Edit" })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Keep private" })).toBeEnabled();
	},
};

/** Filing says neither "public" nor "filed": the state does not say so yet. */
export const Filing: Story = {
	args: { report: filingReport },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/filing it on GitHub now/u)).toBeVisible();
		await expect(canvas.queryByText(/public/u)).toBeNull();
	},
};

export const FiledPublic: Story = {
	args: { report: publicIssue },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /Issue #1234/u })).toHaveAttribute(
			"href",
			"https://github.com/hephaestus-build/Hephaestus/issues/1234",
		);
	},
};

/** A report the person kept private says so in words, not as a code. */
export const KeptPrivate: Story = {
	args: { report: privateIssue },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"The maintainers have it as a private issue on GitHub. You chose to keep it private.",
			),
		).toBeVisible();
	},
};

export const KeptPrivateByCheck: Story = {
	args: { report: securityReport },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/may be about a security problem/u)).toBeVisible();
	},
};

export const SentToAdmins: Story = {
	args: { report: emailedReport },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Thanks. The admins of this instance have it.")).toBeVisible();
	},
};

export const Stopped: Story = {
	args: { report: stoppedReport },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/We could not file it on GitHub/u)).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};
