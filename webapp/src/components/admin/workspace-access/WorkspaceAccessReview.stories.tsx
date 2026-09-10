import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { daysAfter, daysBefore } from "@/components/common/story-clock";

import { WorkspaceAccessReview } from "./WorkspaceAccessReview";

const meta = {
	component: WorkspaceAccessReview,
	tags: ["autodocs"],
	args: {
		request: {
			id: 42,
			accountId: 1,
			displayName: "Alex Developer",
			submittedAt: daysBefore(1),
			status: "SUBMITTED",
			policyVersion: 2,
			version: 0,
			comments: "I am joining the platform team.",
			requestedDetails: { maintainerAccountId: 2, teamIds: [3], expiresAt: daysAfter(30) },
		},
		options: {
			maintainers: [{ accountId: 2, displayName: "Sam Maintainer" }],
			requestableTeams: [{ id: 3, name: "Platform" }],
			maximumDurationDays: 90,
		},
		canReview: true,
		pending: false,
		onReview: fn(),
	},
} satisfies Meta<typeof WorkspaceAccessReview>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Approve access" })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: "Decline access" })).toBeDisabled();
	},
};
export const OwnRequest: Story = {
	args: { canReview: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Approve access" })).not.toBeInTheDocument();
		await expect(canvas.getByText("Another administrator must review your request.")).toBeVisible();
	},
};
export const Saving: Story = { args: { pending: true } };
export const Error: Story = {
	args: { error: "The policy changed after submission; ask the applicant to review it again." },
};
export const NoEligibleMaintainers: Story = {
	args: { options: { ...meta.args.options, maintainers: [] } },
};
