import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { AdminWorkspaceView } from "@/api/types.gen";

import { AdminWorkspacesTable } from "./AdminWorkspacesTable";

const workspaceWithOwner: AdminWorkspaceView = {
	id: 1,
	workspaceSlug: "aet",
	displayName: "AET",
	status: "ACTIVE",
	accountLogin: "aet-org",
	providerType: "GITHUB",
	ownerDisplayName: "octocat",
	ownerAccountId: 101,
	memberCount: 42,
	createdAt: new Date("2026-01-15T00:00:00Z"),
};

const workspaceWithoutOwner: AdminWorkspaceView = {
	id: 2,
	workspaceSlug: "intro-course",
	displayName: "Intro Course",
	status: "SUSPENDED",
	accountLogin: "ase/ios",
	providerType: "GITLAB",
	memberCount: 0,
	createdAt: new Date("2026-03-01T00:00:00Z"),
};

const workspaces = [workspaceWithOwner, workspaceWithoutOwner];

const meta = {
	component: AdminWorkspacesTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaces,
		isLoading: false,
		isError: false,
		hasSearch: false,
		onImpersonateOwner: fn(),
	},
} satisfies Meta<typeof AdminWorkspacesTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Metadata-only rows: provider, owner, member count, status. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		canvas.getByText("AET");
		canvas.getByText("SUSPENDED");
		// Owner falls back to an em dash when there is no OWNER member.
		canvas.getByText("octocat");
		const [ownedAction, ownerlessAction] = canvas.getAllByRole("button", {
			name: "View as owner",
		});
		if (!ownedAction || !ownerlessAction) {
			throw new Error("Every workspace row renders an owner action");
		}
		await userEvent.click(ownedAction);
		await expect(args.onImpersonateOwner).toHaveBeenCalledWith(workspaceWithOwner);
		await expect(ownerlessAction).toBeDisabled();
	},
};

/** Empty state under an active search filter. */
export const EmptyWithSearch: Story = {
	args: { workspaces: [], hasSearch: true },
	play: async ({ canvas }) => {
		canvas.getByText("No matching workspaces.");
	},
};
