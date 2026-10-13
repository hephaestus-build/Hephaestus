import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { FOLLOW_UPS_FULL } from "./fixtures";
import { MessageStatus } from "./MessageStatus";

const meta = {
	component: MessageStatus,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { state: { kind: "queued", next: true, onStop: fn() }, subject: "Ada Lovelace, 10:42" },
} satisfies Meta<typeof MessageStatus>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A follow-up waits while Heph answers the question before it, and can be stopped before it runs. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Queued. Heph answers this next.")).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Stop queued message (Ada Lovelace, 10:42)" }),
		).toBeVisible();
	},
};

/** A second follow-up waits behind the first, so it does not claim to be answered next. */
export const QueuedBehindAnother: Story = {
	args: { state: { kind: "queued", next: false, onStop: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Queued.")).toBeVisible();
		await expect(canvas.queryByText(/answers this next/u)).toBeNull();
	},
};

/** The developer stopped the turn. What Heph wrote before stays above. */
export const Stopped: Story = {
	args: { state: { kind: "stopped" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Stopped")).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

const onTryAgain = fn();

export const Failed: Story = {
	args: {
		state: { kind: "failed", reason: "Heph took too long to answer.", onTryAgain },
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByText("Heph took too long to answer.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Try again (Ada Lovelace, 10:42)" }));
		await expect(onTryAgain).toHaveBeenCalledOnce();
	},
};

const onRetry = fn();

/** The server never stored it. Retry sends it again under the same request id, so it is never asked twice. */
export const NotSent: Story = {
	args: {
		state: { kind: "notSent", reason: FOLLOW_UPS_FULL, onRetry },
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByText(`Not sent. ${FOLLOW_UPS_FULL}`)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry (Ada Lovelace, 10:42)" }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};
