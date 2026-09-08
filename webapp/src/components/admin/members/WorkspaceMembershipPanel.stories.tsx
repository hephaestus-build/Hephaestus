import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { WorkspaceMembershipPanel } from "./WorkspaceMembershipPanel";

const meta = {
	component: WorkspaceMembershipPanel,
	args: {
		members: [
			{ accountId: 1, displayName: "Alex Chen", role: "OWNER", source: "MANUAL", suspended: false },
			{ accountId: 2, displayName: "Sam Rivera", role: "MEMBER", source: "SCM", suspended: false },
		],
		isOwner: true,
		isLoading: false,
		isSaving: false,
		onRetry: fn(),
		onAssign: fn().mockResolvedValue(undefined),
		onSuspend: fn(),
	},
	tags: ["autodocs"],
} satisfies Meta<typeof WorkspaceMembershipPanel>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Empty: Story = { args: { members: [] } };
export const Loading: Story = { args: { members: [], isLoading: true } };
export const LoadError: Story = {
	args: { error: new Error("The membership service is unavailable") },
};
export const Administrator: Story = {
	args: { isOwner: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("button", { name: "Edit access for Alex Chen" }),
		).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "Edit access for Sam Rivera" })).toBeEnabled();
	},
};
export const Suspended: Story = {
	args: {
		members: [
			{
				accountId: 2,
				displayName: "Sam Rivera",
				role: "MEMBER",
				source: "MANUAL",
				suspended: true,
			},
		],
	},
	play: async ({ canvas }) => {
		canvas.getByText("Suspended");
		await expect(
			canvas.getByRole("button", { name: "Edit access for Sam Rivera" }),
		).toHaveTextContent("Restore access");
		await expect(
			canvas.queryByRole("button", { name: "Suspend access for Sam Rivera" }),
		).not.toBeInTheDocument();
	},
};
export const AddMember: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Add member" }));
	},
};
export const Saving: Story = { args: { isSaving: true } };

export const AwaitingFirstOwner: Story = {
	args: { members: [], isOwner: false, canInitializeOwner: true },
	play: async ({ canvas }) => {
		canvas.getByText(/assign its first owner/);
		await expect(canvas.getByRole("button", { name: "Add member" })).toBeEnabled();
	},
};
