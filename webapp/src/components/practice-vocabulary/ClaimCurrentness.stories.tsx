import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { daysBefore } from "@/stories/story-clock";

import { ClaimCurrentnessBadge } from "./ClaimCurrentness";

const CAPTURED_AT = daysBefore(3);

const meta = {
	component: ClaimCurrentnessBadge,
	args: {
		freshness: { currentness: "STALE", unverifiableReason: null, capturedAt: CAPTURED_AT },
	},
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof ClaimCurrentnessBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Stale: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Historical observation")).toBeVisible();
	},
};

/** The record of what the review read is missing or comes from an earlier review version: no time is known. */
export const RecordMissing: Story = {
	args: {
		freshness: {
			currentness: "UNVERIFIABLE",
			unverifiableReason: "RECORD_MISSING",
			capturedAt: null,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Unverified observation")).toBeVisible();
	},
};

/** The record is there, but the current work could not be read on this server now. */
export const WorkUnreadable: Story = {
	args: {
		freshness: {
			currentness: "UNVERIFIABLE",
			unverifiableReason: "WORK_UNREADABLE",
			capturedAt: CAPTURED_AT,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Cannot verify now")).toBeVisible();
	},
};

/** A current observation needs no badge: only an observation that no longer holds says so. */
export const Current: Story = {
	args: {
		freshness: { currentness: "CURRENT", unverifiableReason: null, capturedAt: CAPTURED_AT },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByText(/^(?:Historical|Unverified) observation$|^Cannot verify now$/u),
		).toBeNull();
	},
};
