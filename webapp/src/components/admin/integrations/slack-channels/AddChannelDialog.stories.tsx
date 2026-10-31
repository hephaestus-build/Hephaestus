import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { SlackChannelCandidate } from "@/api/types.gen";
import { pending } from "@/test/async";

import { AddChannelDialog } from "./AddChannelDialog";

const candidates: SlackChannelCandidate[] = [
	{
		slackChannelId: "C05GENERAL5",
		channelName: "general",
		privateChannel: false,
		member: true,
		archived: false,
	},
	{
		slackChannelId: "C06STANDUP6",
		channelName: "team-standup",
		privateChannel: true,
		member: true,
		archived: false,
	},
	{
		slackChannelId: "C07LISTED07",
		channelName: "team-listed",
		privateChannel: false,
		member: true,
		archived: false,
		consentState: "ACTIVE",
	},
	{
		slackChannelId: "C08OLDIES08",
		channelName: "team-archive",
		privateChannel: false,
		member: true,
		archived: true,
	},
];

/**
 * Allow-listing a channel: a combobox over the channels Slack reports, with a paste escape hatch
 * for channels Slack did not list. The stored value is the stable channel id.
 *
 * The dialog is portalled, so the plays query the document rather than the story canvas.
 */
const meta = {
	component: AddChannelDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		onSubmit: fn(),
		candidates,
	},
} satisfies Meta<typeof AddChannelDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Slack listed channels — pick one; disabled options keep a visible reason. */
export const WithCandidates: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("combobox", { name: /^channel$/iu }));

		await expect(await screen.findByRole("option", { name: /#team-archive/iu })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(await screen.findByRole("option", { name: /#team-listed/iu })).toHaveAttribute(
			"aria-disabled",
			"true",
		);

		await userEvent.click(screen.getByRole("option", { name: /#general/iu }));
		await userEvent.click(dialog.getByRole("button", { name: /^add channel$/iu }));

		await expect(args.onSubmit).toHaveBeenCalledWith({
			slackChannelId: "C05GENERAL5",
			channelName: "general",
		});
	},
};

/** Nothing chosen — submit stays enabled and says what is missing instead of going dead. */
export const NothingChosen: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		const submit = dialog.getByRole("button", { name: /^add channel$/iu });
		await expect(submit).toBeEnabled();

		await userEvent.click(submit);

		await expect(args.onSubmit).not.toHaveBeenCalled();
		dialog.getByText(/select a channel from the list/iu);
	},
};

/** Slack listed nothing (no channels yet, or the bot was never invited) — paste is the path. */
export const NoCandidates: Story = {
	args: { candidates: [] },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(
			dialog.getByLabelText(/paste a channel link or id/iu),
			"https://acme.slack.com/archives/C0974LJBPBK",
		);
		await userEvent.click(dialog.getByRole("button", { name: /^add channel$/iu }));

		await expect(args.onSubmit).toHaveBeenCalledWith({
			slackChannelId: "C0974LJBPBK",
			channelName: undefined,
		});
	},
};

/** A pasted value that is not a Slack channel reference is rejected in place, before submit. */
export const InvalidPaste: Story = {
	args: { candidates: [] },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(dialog.getByLabelText(/paste a channel link or id/iu), "not-a-channel");
		dialog.getByText(/paste a slack channel link, mention, or id/iu);
	},
};

/** Slack's channel list is still loading: its slot holds a placeholder, and paste stays one click away. */
export const CandidatesLoading: Story = {
	args: { candidates: [], candidatesLoading: true },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.queryByRole("combobox", { name: /^channel$/iu })).not.toBeInTheDocument();
		await expect(dialog.queryByLabelText(/paste a channel link or id/iu)).not.toBeInTheDocument();
		dialog.getByRole("button", { name: /paste a channel link or id instead/iu });
	},
};

/** Slack's channel list could not be read: the picker says so and offers Retry; paste still works. */
export const CandidatesFailed: Story = {
	args: {
		candidates: [],
		candidatesFailure: { error: { status: 503 }, onRetry: fn(), pending: false },
	},
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		dialog.getByText("We could not load the channels from Slack");
		await expect(dialog.getByRole("button", { name: "Retry" })).toBeEnabled();
		dialog.getByLabelText(/paste a channel link or id/iu);
	},
};

/** Slack's channel list was refused: no Retry that would be refused again, and paste still works. */
export const CandidatesRefused: Story = {
	args: {
		candidates: [],
		candidatesFailure: { error: { status: 403 }, onRetry: fn(), pending: false },
	},
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		dialog.getByText("You do not have access to the channels from Slack");
		await expect(dialog.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
		dialog.getByLabelText(/paste a channel link or id/iu);
	},
};

/** While an Add is pending, the dialog cannot be dismissed, so its draft and answer stay together. */
export const SubmitPending: Story = {
	args: { candidates: [], onSubmit: fn(async (): Promise<void> => pending()) },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(dialog.getByLabelText(/paste a channel link or id/iu), "C0974LJBPBK");
		await userEvent.click(dialog.getByRole("button", { name: /^add channel$/iu }));

		await expect(dialog.getByRole("button", { name: "Adding…" })).toBeDisabled();
		await expect(dialog.queryByRole("button", { name: "Close" })).not.toBeInTheDocument();
		await userEvent.keyboard("{Escape}");
		// The story holds the dialog open, so what stays open is the dialog's own choice: it asks no one
		// to close it. Its open animation is no part of that.
		await expect(args.onOpenChange).not.toHaveBeenCalled();
		await expect(dialog.getByLabelText(/paste a channel link or id/iu)).toHaveValue("C0974LJBPBK");
	},
};

const rejectedSubmit = fn(async () => {
	throw new Error("slack rejected the channel");
});

/** A rejected registration keeps the dialog open so the admin can fix and retry. */
export const SubmitRejected: Story = {
	args: { candidates: [], onSubmit: rejectedSubmit },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(dialog.getByLabelText(/paste a channel link or id/iu), "C0974LJBPBK");
		await userEvent.click(dialog.getByRole("button", { name: /^add channel$/iu }));

		await waitFor(async () => {
			await expect(rejectedSubmit.mock.settledResults).toStrictEqual([
				expect.objectContaining({ type: "rejected" }),
			]);
		});
		// Once the rejection lands, Add is offered again, the draft is still there to fix, and the
		// dialog asked no one to close it.
		await expect(await dialog.findByRole("button", { name: /^add channel$/iu })).toBeEnabled();
		await expect(args.onOpenChange).not.toHaveBeenCalled();
		await expect(dialog.getByLabelText(/paste a channel link or id/iu)).toHaveValue("C0974LJBPBK");
	},
};
