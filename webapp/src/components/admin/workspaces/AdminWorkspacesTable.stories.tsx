import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { AdminWorkspaceView } from "@/api/types.gen";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";

import { AdminWorkspacesTable } from "./AdminWorkspacesTable";

const activeWorkspace: AdminWorkspaceView = {
	id: 1,
	workspaceSlug: "aet",
	displayName: "AET",
	status: "ACTIVE",
	accountLogin: "aet-org",
	providerType: "GITHUB",
	ownerLogin: "octocat",
	memberCount: 42,
	createdAt: new Date("2026-01-15T00:00:00Z"),
};

const suspendedWorkspace: AdminWorkspaceView = {
	id: 2,
	workspaceSlug: "intro-course",
	displayName: "Intro Course",
	status: "SUSPENDED",
	accountLogin: "ase/ios",
	providerType: "GITLAB",
	memberCount: 0,
	createdAt: new Date("2026-03-01T00:00:00Z"),
};

const workspaces = [activeWorkspace, suspendedWorkspace];

const meta = {
	component: AdminWorkspacesTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaces,
		isLoading: false,
		isError: false,
		hasSearch: false,
		onViewUsers: fn(),
	},
} satisfies Meta<typeof AdminWorkspacesTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "View users of AET" }));
		await expect(args.onViewUsers).toHaveBeenCalledWith(activeWorkspace);
		await expectGenuinelyDisabled(
			canvas.getByRole("button", { name: "View users of Intro Course" }),
		);
		canvas.getByText("This workspace is suspended, so its users cannot be viewed.");
	},
};

export const Loading: Story = {
	args: { isLoading: true },
};

export const EmptyWithSearch: Story = {
	args: { workspaces: [], hasSearch: true },
	play: async ({ canvas }) => {
		canvas.getByText("No matching workspaces.");
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
