import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectTargetSize } from "@/stories/reflow";

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

/**
 * Whether the line's icon wears a colour of its own. A line that only reports keeps the icon in
 * the line's muted words colour; a line that needs the developer to act does not.
 */
function iconStandsOut(canvasElement: HTMLElement): boolean {
	const line = canvasElement.querySelector<HTMLElement>("[data-slot=marker-icon]")?.parentElement;
	const icon = line?.querySelector("svg");
	if (!line || !icon) {
		throw new Error("Expected a status line with an icon");
	}
	return getComputedStyle(icon).color !== getComputedStyle(line).color;
}

/** A follow-up waits while Heph answers the question before it, and can be stopped before it runs. */
export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Queued. Heph answers this next.")).toBeVisible();
		const stop = canvas.getByRole("button", { name: "Stop queued message (Ada Lovelace, 10:42)" });
		await expect(stop).toBeVisible();
		// On a line of its own, so the inline-text exception to WCAG 2.5.8 does not apply.
		await expectTargetSize(stop);
		await expect(iconStandsOut(canvasElement)).toBe(false);
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
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Stopped")).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(iconStandsOut(canvasElement)).toBe(false);
	},
};

/**
 * Heph's answer to a question asked in Slack did not fully reach Slack: Slack may show part of it or
 * none. The full answer is here, and the line says so without claiming which.
 */
export const NotFullyDeliveredToSlack: Story = {
	args: { state: { kind: "notFullyInSlack" } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Not fully delivered to Slack. The full answer is here."),
		).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

const onTryAgain = fn();

/**
 * Heph could not answer. Unlike Queued and Stopped, which only report, this line needs the
 * developer, so its icon takes the `destructive` tone and Try again is a full button.
 */
export const Failed: Story = {
	args: {
		state: {
			kind: "failed",
			reason: "Heph reached its time limit and stopped this reply. Try again.",
			onTryAgain,
		},
	},
	play: async ({ canvas, canvasElement, userEvent }) => {
		await expect(
			canvas.getByText("Heph reached its time limit and stopped this reply. Try again."),
		).toBeVisible();
		await expect(iconStandsOut(canvasElement)).toBe(true);
		const tryAgain = canvas.getByRole("button", { name: "Try again (Ada Lovelace, 10:42)" });
		await expectTargetSize(tryAgain);
		await userEvent.click(tryAgain);
		await expect(onTryAgain).toHaveBeenCalledOnce();
	},
};

/** The same line in the dark theme. */
export const FailedDark: Story = {
	args: {
		state: {
			kind: "failed",
			reason: "Heph reached its time limit and stopped this reply. Try again.",
			onTryAgain: fn(),
		},
	},
	globals: { theme: "dark" },
};

const onRetry = fn();

/** The server never stored it. Retry sends it again under the same request id, so it is never asked twice. */
export const NotSent: Story = {
	args: {
		state: { kind: "notSent", reason: FOLLOW_UPS_FULL, onRetry },
	},
	play: async ({ canvas, canvasElement, userEvent }) => {
		await expect(canvas.getByText(`Not sent. ${FOLLOW_UPS_FULL}`)).toBeVisible();
		await expect(iconStandsOut(canvasElement)).toBe(true);
		const retry = canvas.getByRole("button", { name: "Retry (Ada Lovelace, 10:42)" });
		await expectTargetSize(retry);
		await userEvent.click(retry);
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};
