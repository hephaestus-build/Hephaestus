import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import type { WorkspaceAccessRequest } from "@/api/types.gen";
import { daysAfter, daysBefore } from "@/components/common/story-clock";

import { WorkspaceAccessHistory } from "./WorkspaceAccessHistory";

const request = {
	id: 42,
	accountId: 1,
	displayName: "Alex",
	status: "SUBMITTED",
	submittedAt: daysBefore(1),
	requestedDetails: { maintainerAccountId: 2, teamIds: [3], expiresAt: daysAfter(30) },
} satisfies WorkspaceAccessRequest;

const meta = {
	component: WorkspaceAccessHistory,
	tags: ["autodocs"],
	args: {
		requests: [request],
		onWithdraw: fn(),
	},
} satisfies Meta<typeof WorkspaceAccessHistory>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Pending: Story = {};
export const Empty: Story = { args: { requests: [] } };
export const Withdrawing: Story = { args: { withdrawingId: 42 } };
export const ChangesRequested: Story = {
	args: {
		requests: [
			{
				...request,
				status: "CHANGES_REQUESTED",
				decisionComment: "Please select the team you will be working with.",
			},
		],
	},
};
export const Expired: Story = {
	args: {
		requests: [{ ...request, status: "APPROVED", effectiveExpiresAt: daysBefore(1) }],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Access expired/)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /Withdraw/ })).not.toBeInTheDocument();
	},
};

export const SuspendedBeforeDeadline: Story = {
	args: {
		requests: [
			{ ...request, status: "APPROVED", accessActive: false, effectiveExpiresAt: daysAfter(7) },
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Access is inactive/)).toBeVisible();
		await expect(canvas.queryByText(/Access ends/)).not.toBeInTheDocument();
	},
};
