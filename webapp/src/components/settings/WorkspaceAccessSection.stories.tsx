import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/test/reflow";

import { WorkspaceAccessSection } from "./WorkspaceAccessSection";

const offer = {
	workspaceId: 23,
	slug: "team",
	displayName: "Engineering",
	joined: false,
	suspended: false,
};
const meta = {
	component: WorkspaceAccessSection,
	decorators: [withStandardPage],
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { state: { status: "ready", offers: [offer] }, onJoin: fn() },
} satisfies Meta<typeof WorkspaceAccessSection>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Eligible: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Join Engineering" })).toBeEnabled();
		await expect(canvas.queryByRole("link", { name: "Open workspace" })).not.toBeInTheDocument();
	},
};
export const Empty: Story = { args: { state: { status: "ready", offers: [] } } };
export const Loading: Story = { args: { state: { status: "loading" } } };
export const LoadError: Story = {
	args: { state: { status: "error", error: new Error("Unavailable"), onRetry: fn() } },
};
export const Joined: Story = {
	args: { state: { status: "ready", offers: [{ ...offer, joined: true }] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Open workspace" })).toHaveAttribute(
			"href",
			"/w/team",
		);
		await expect(
			canvas.queryByRole("button", { name: "Join Engineering" }),
		).not.toBeInTheDocument();
	},
};
export const Suspended: Story = {
	args: { state: { status: "ready", offers: [{ ...offer, suspended: true }] } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button")).not.toBeInTheDocument();
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};
export const Joining: Story = { args: { joiningWorkspaceId: 23 } };
export const Narrow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	args: {
		state: {
			status: "ready",
			offers: [{ ...offer, displayName: "Organizational Engineering and Infrastructure" }],
		},
	},
	play: expectNoPageOverflow,
};
