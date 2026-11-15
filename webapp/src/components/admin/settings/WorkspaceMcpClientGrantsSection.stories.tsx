import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { daysBefore } from "@/stories/story-clock";

import { WorkspaceMcpClientGrantsSection } from "./WorkspaceMcpClientGrantsSection";

const grant = {
	id: "8a1f6c3e-2b47-4d55-9f0e-1c2d3e4f5a6b",
	clientHost: "claude.ai",
	clientName: "Claude Code",
	approvedBy: "Octo Cat",
	scopes: ["practices:read"],
	approvedAt: daysBefore(3),
};

const meta = {
	component: WorkspaceMcpClientGrantsSection,
	tags: ["autodocs"],
	args: { grants: [grant], onRetry: fn(), onRevoke: fn() },
} satisfies Meta<typeof WorkspaceMcpClientGrantsSection>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Connected: Story = {
	play: async ({ canvas, args }) => {
		await canvas.getByRole("button", { name: "Disconnect claude.ai" }).click();
		await expect(args.onRevoke).toHaveBeenCalledWith(grant.id);
	},
};

export const Empty: Story = {
	args: { grants: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No client is connected.")).toBeVisible();
	},
};

export const Loading: Story = { args: { grants: undefined, loading: true } };

export const Error: Story = {
	args: { grants: undefined, error: "We could not load the connected clients." },
};

export const Disconnecting: Story = { args: { revokingId: grant.id } };
