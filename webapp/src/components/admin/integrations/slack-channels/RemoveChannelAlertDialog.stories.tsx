import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { SlackMonitoredChannel } from "@/api/types.gen";

import { RemoveChannelAlertDialog } from "./RemoveChannelAlertDialog";

const active: SlackMonitoredChannel = {
	id: 1,
	slackTeamId: "T0000000000",
	slackChannelId: "C02ACTIVE002",
	channelName: "team-standup",
	consentState: "ACTIVE",
	optedOutMemberCount: 0,
	consentAnnouncedAt: new Date("2026-02-01T00:00:00Z"),
	createdAt: new Date("2026-01-01T00:00:00Z"),
};

/**
 * Terminal erase. A channel that never got past PENDING has nothing collected (no announcement
 * was ever posted), so it gets accurate copy and no type-to-confirm gate. Everything else does
 * — and the gate validates on submit with a stated reason rather than a dead button.
 *
 * The alert dialog is portalled, so the plays query the document rather than the story canvas.
 */
const meta = {
	component: RemoveChannelAlertDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		channel: active,
		onOpenChange: fn(),
		onConfirm: fn(),
	},
} satisfies Meta<typeof RemoveChannelAlertDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Data was collected — the erase is gated by typing the stable channel ID. */
export const TypeToConfirm: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		dialog.getByText(/all messages collected/iu);

		const confirm = dialog.getByRole("button", { name: /remove & erase/iu });
		await userEvent.click(confirm);

		await expect(args.onConfirm).not.toHaveBeenCalled();
		dialog.getByText(/that does not match/iu);

		await userEvent.type(dialog.getByLabelText(/to confirm/iu), active.slackChannelId);
		await userEvent.type(dialog.getByLabelText(/reason/iu), "Course finished");
		await userEvent.click(confirm);

		await expect(args.onConfirm).toHaveBeenCalledWith({
			slackChannelId: active.slackChannelId,
			reason: "Course finished",
		});
	},
};

/** A wrong ID marks the field invalid and says so — it does not silently do nothing. */
export const ConfirmMismatch: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		const input = dialog.getByLabelText(/to confirm/iu);
		await userEvent.type(input, "C0-WRONG");
		await userEvent.click(dialog.getByRole("button", { name: /remove & erase/iu }));

		await expect(input).toHaveAttribute("aria-invalid", "true");
		dialog.getByText(/that does not match/iu);
		await expect(args.onConfirm).not.toHaveBeenCalled();

		// Correcting the value clears the invalid state — the error is not sticky.
		await userEvent.clear(input);
		await userEvent.type(input, active.slackChannelId);
		await expect(input).toHaveAttribute("aria-invalid", "false");
		await expect(dialog.queryByText(/that does not match/iu)).not.toBeInTheDocument();
	},
};

/** PENDING — nothing was ever collected, so the copy says so and there is no gate. */
export const NothingCollected: Story = {
	args: { channel: { ...active, consentState: "PENDING", consentAnnouncedAt: undefined } },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		dialog.getByText(/nothing has been collected/iu);
		await expect(dialog.queryByLabelText(/to confirm/iu)).not.toBeInTheDocument();

		await userEvent.click(dialog.getByRole("button", { name: /^remove$/iu }));
		await expect(args.onConfirm).toHaveBeenCalledWith({
			slackChannelId: active.slackChannelId,
			reason: undefined,
		});
	},
};

/** Closed — nothing is rendered until a channel is selected for removal. */
export const Closed: Story = {
	args: { channel: null },
	play: async () => {
		await expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
	},
};
