import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { activeShare, diagnosticsPreview, failedTurn, textPreview } from "./fixtures";
import { SupportShareDialog } from "./SupportShareDialog";

/**
 * The person shares one question with one instance admin. The preview is the page the admin reads,
 * and the share waits for an admin to be chosen.
 */
const meta = {
	component: SupportShareDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		preview: { status: "ready", preview: diagnosticsPreview },
		includeText: false,
		onIncludeTextChange: fn(),
		recipientId: undefined,
		onRecipientChange: fn(),
		sharing: false,
		onShare: fn(),
	},
} satisfies Meta<typeof SupportShareDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The dialog, once it has landed: it is portalled to the page, not inside the story's canvas. */
async function openedDialog(): Promise<HTMLElement> {
	const dialog = await screen.findByRole("dialog", { name: "Share with support" });
	await expectSettledVisible(dialog);
	return dialog;
}

/** By default only how Hephaestus ran the question: states, times, ids, and no words. */
export const DiagnosticsOnly: Story = {
	play: async () => {
		const dialog = within(await openedDialog());
		const facts = within(dialog.getByLabelText("How Hephaestus ran this question"));
		await expect(facts.getByText("Failed")).toBeVisible();
		await expect(facts.getByText(failedTurn.runId)).toBeVisible();
		await expect(dialog.queryByRole("list", { name: "Shared messages" })).toBeNull();
		await expect(dialog.getByRole("switch", { name: "Include the messages" })).not.toBeChecked();
		await expect(dialog.getByRole("button", { name: "Share" })).toBeDisabled();
	},
};

/** With the messages, the preview shows the question and the answer exactly as the admin reads them. */
export const WithTheMessages: Story = {
	args: {
		preview: { status: "ready", preview: textPreview },
		includeText: true,
		recipientId: "7",
	},
	play: async ({ args, userEvent }) => {
		const dialog = within(await openedDialog());
		const messages = within(dialog.getByRole("list", { name: "Shared messages" }));
		await expect(messages.getByText("Why does my build fail on the second run?")).toBeVisible();
		await expect(messages.getByText("You")).toBeVisible();
		await userEvent.click(dialog.getByRole("button", { name: "Share" }));
		await expect(args.onShare).toHaveBeenCalledOnce();
	},
};

/** The admin is chosen from the instance admins; the choice reports their account. */
export const ChoosesTheAdmin: Story = {
	play: async ({ args, userEvent }) => {
		const dialog = within(await openedDialog());
		await userEvent.click(dialog.getByRole("combobox", { name: "Who reads it" }));
		await userEvent.click(await screen.findByRole("option", { name: "Grace Hopper" }));
		await expect(args.onRecipientChange).toHaveBeenCalledWith("9");
	},
};

export const LoadingThePreview: Story = {
	args: { preview: { status: "loading" } },
	play: async () => {
		const dialog = within(await openedDialog());
		await expect(dialog.getByRole("button", { name: "Share" })).toBeDisabled();
		await expect(dialog.queryByRole("combobox", { name: "Who reads it" })).toBeNull();
	},
};

export const PreviewFailed: Story = {
	args: { preview: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async () => {
		const dialog = within(await openedDialog());
		await expect(dialog.getByText("We could not show the preview")).toBeVisible();
		await expect(dialog.getByRole("button", { name: "Share" })).toBeDisabled();
	},
};

/** The messages changed after the preview: nothing was shared, and the reader looks again. */
export const MessagesChanged: Story = {
	args: {
		preview: { status: "ready", preview: textPreview },
		includeText: true,
		recipientId: "7",
		changed: true,
	},
	play: async () => {
		const dialog = within(await openedDialog());
		await expect(dialog.getByText("The messages changed")).toBeVisible();
		await expect(dialog.getByText(/Nothing was shared\./u)).toBeVisible();
	},
};

/** An instance with no admin has nobody to share with, so Share stays off. */
export const NoAdminToShareWith: Story = {
	args: { preview: { status: "ready", preview: { ...diagnosticsPreview, recipients: [] } } },
	play: async () => {
		const dialog = within(await openedDialog());
		await expect(dialog.getByText("This instance has no admin to share with.")).toBeVisible();
		await expect(dialog.getByRole("button", { name: "Share" })).toBeDisabled();
	},
};

export const ShareFailed: Story = {
	args: { recipientId: "7", shareError: { status: 500 } },
	play: async () => {
		const dialog = within(await openedDialog());
		await expect(dialog.getByText("We could not share this question")).toBeVisible();
	},
};

/** Shared: who reads it and until when, and where to revoke it. */
export const Shared: Story = {
	args: { shared: activeShare },
	play: async ({ args, userEvent }) => {
		const dialog = within(await openedDialog());
		await expect(dialog.getByText("Shared with Ada Lovelace")).toBeVisible();
		await expect(dialog.queryByRole("button", { name: "Share" })).toBeNull();
		await userEvent.click(dialog.getByRole("button", { name: "Done" }));
		await expect(args.onOpenChange).toHaveBeenCalledWith(false);
	},
};
