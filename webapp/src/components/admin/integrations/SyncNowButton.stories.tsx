import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { SyncJob } from "@/api/types.gen";

import { SyncNowButton } from "./SyncNowButton";

const runningJob: SyncJob = {
	id: 1,
	type: "RECONCILIATION",
	trigger: "MANUAL",
	status: "RUNNING",
	cancelRequested: false,
	createdAt: new Date("2026-07-14T10:00:00Z"),
};

const runningBackfill: SyncJob = { ...runningJob, id: 2, type: "BACKFILL" };

/**
 * The manual-sync trigger, and the only trigger on its card. Disabled while a job is already running
 * or a trigger is in flight, so a double-click can't enqueue a second run. It owns a visually-hidden
 * `aria-live` region because a plain button-label swap is not reliably announced — the region stays
 * empty until a run begins.
 *
 * `triggeringType` is a type rather than a flag because the split menu can start a backfill from this
 * same button: the announcement has to be able to name which operation started. Once the server has a
 * job, the announcement switches to naming *that* job — a backfill request that landed behind a
 * running reconciliation must not report the reconciliation as a backfill.
 */
const meta = {
	component: SyncNowButton,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { onClick: fn() },
	argTypes: {
		triggeringType: {
			control: "select",
			options: [null, "RECONCILIATION", "BACKFILL"],
			description: "The operation this button just started, or null when idle.",
		},
	},
} satisfies Meta<typeof SyncNowButton>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Idle — clickable, and the live region is silent until a run actually starts. */
export const Idle: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.queryByText(/starting/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByText(/in progress/iu)).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: /sync now/iu }));
		await expect(args.onClick).toHaveBeenCalledTimes(1);
	},
};

/** Trigger in flight — the button is disabled and the live region announces the start. */
export const Triggering: Story = {
	args: { triggeringType: "RECONCILIATION" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /starting/iu })).toBeDisabled();
		canvas.getByText("Starting reconciliation");
	},
};

/**
 * A backfill was started from the split menu. The button copy is the same "Starting…", but the
 * announcement names the operation the admin actually asked for — the reason this input is a type.
 */
export const TriggeringBackfill: Story = {
	args: { triggeringType: "BACKFILL" },
	play: async ({ canvas }) => {
		canvas.getByText("Starting backfill");
	},
};

/** A job is already running — disabled and labelled "Syncing…", with the job named to assistive tech. */
export const ActiveJob: Story = {
	args: { activeJob: runningJob },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /syncing/iu })).toBeDisabled();
		canvas.getByText("Reconciliation in progress");
	},
};

/**
 * The server is running a backfill. The announcement follows the *job*, not the trigger: whatever this
 * button was last pressed for, what is happening is a backfill and that is what gets said.
 */
export const ActiveBackfill: Story = {
	args: { activeJob: runningBackfill, triggeringType: "RECONCILIATION" },
	play: async ({ canvas }) => {
		canvas.getByText("Backfill in progress");
		await expect(canvas.queryByText(/starting/iu)).not.toBeInTheDocument();
	},
};
