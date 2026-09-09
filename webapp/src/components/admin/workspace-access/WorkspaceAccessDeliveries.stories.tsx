import type { Meta, StoryObj } from "@storybook/react-vite";
import { fn } from "storybook/test";

import { daysBefore } from "@/components/common/story-clock";

import { WorkspaceAccessDeliveries } from "./WorkspaceAccessDeliveries";

const meta = {
	component: WorkspaceAccessDeliveries,
	tags: ["autodocs"],
	args: { notifications: [], onRetry: fn() },
} satisfies Meta<typeof WorkspaceAccessDeliveries>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {};
export const Sent: Story = {
	args: {
		notifications: [
			{
				id: 1,
				kind: "SUBMITTED",
				state: "SENT",
				attempts: 1,
				nextAttemptAt: daysBefore(1),
				sentAt: daysBefore(1),
			},
		],
	},
};
export const Pending: Story = {
	args: {
		notifications: [
			{ id: 1, kind: "DECIDED", state: "PENDING", attempts: 0, nextAttemptAt: daysBefore(1) },
		],
	},
};
export const Failed: Story = {
	args: {
		notifications: [
			{
				id: 1,
				kind: "REMINDER",
				state: "FAILED",
				reason: "SILENT_MODE",
				attempts: 0,
				nextAttemptAt: daysBefore(1),
			},
		],
	},
};
export const Retrying: Story = { args: { ...Failed.args, retryingId: 1 } };
