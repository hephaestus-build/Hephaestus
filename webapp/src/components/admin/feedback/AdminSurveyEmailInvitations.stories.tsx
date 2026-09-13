import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { AdminSurveyEmailInvitations } from "./AdminSurveyEmailInvitations";

const ready = {
	status: "ready",
	summary: { eligible: 1420, alreadyRequested: 1000, accepted: 120, queued: 0, remaining: 420 },
	isPending: false,
	onQueue: fn(),
	onRefresh: fn(),
} as const;

const meta = {
	component: AdminSurveyEmailInvitations,
	tags: ["autodocs"],
	args: { state: ready },
} satisfies Meta<typeof AdminSurveyEmailInvitations>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Empty: Story = {
	args: {
		state: {
			...ready,
			summary: { eligible: 0, alreadyRequested: 0, accepted: 0, queued: 0, remaining: 0 },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Queue email invitations…" })).toBeDisabled();
	},
};
export const Queuing: Story = { args: { state: { ...ready, isPending: true } } };
export const Loading: Story = { args: { state: { status: "loading" } } };
export const LoadError: Story = {
	args: { state: { status: "error", error: new Error("Unavailable"), onRetry: fn() } },
};
export const NarrowDark: Story = { globals: { viewport: { value: "reflow" }, theme: "dark" } };
