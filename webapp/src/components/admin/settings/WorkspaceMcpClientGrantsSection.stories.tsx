import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { daysAfter, daysBefore, hoursBefore } from "@/stories/story-clock";

import { WorkspaceMcpClientGrantsSection } from "./WorkspaceMcpClientGrantsSection";

const grant = {
	id: "8a1f6c3e-2b47-4d55-9f0e-1c2d3e4f5a6b",
	clientHost: "claude.ai",
	clientName: "Claude Code",
	approvedBy: "Octo Cat",
	scopes: ["practices:read"],
	approvedAt: daysBefore(3),
	passkeyBacked: false,
	lastUsedAt: hoursBefore(2),
	expiresAt: daysAfter(4),
};

const meta = {
	component: WorkspaceMcpClientGrantsSection,
	tags: ["autodocs"],
	args: { grants: [grant], onRetry: fn(), onRevoke: fn() },
} satisfies Meta<typeof WorkspaceMcpClientGrantsSection>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Connected: Story = {
	play: async ({ canvas, args, userEvent }) => {
		await expect(canvas.getByText("Reads the practice configuration")).toBeVisible();
		await expect(canvas.getByText(/Last used .*Lapses if unused until/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Disconnect claude.ai" }));
		await expect(args.onRevoke).toHaveBeenCalledWith(grant.id);
	},
};

/** A client that may change the configuration says so, so the admin knows what disconnecting it stops. */
export const MayChange: Story = {
	args: { grants: [{ ...grant, scopes: ["practices:read", "practices:write"] }] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reads and changes the practice configuration")).toBeVisible();
	},
};

/** In a workspace that requires an admin passkey, the list says that a passkey confirmed the approval. */
export const ConfirmedWithPasskey: Story = {
	args: {
		grants: [
			{
				...grant,
				scopes: ["practices:read", "practices:write"],
				approvedAt: daysBefore(40),
				passkeyBacked: true,
				lastUsedAt: hoursBefore(1),
				expiresAt: daysAfter(89),
			},
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Confirmed with a passkey/u)).toBeVisible();
	},
};

/** Approved, but the client has not signed in with its code yet. */
export const NotUsedYet: Story = {
	args: { grants: [{ ...grant, lastUsedAt: undefined }] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Not used yet/u)).toBeVisible();
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
	args: { grants: undefined, loadError: { status: 503 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the connected clients")).toBeVisible();
	},
};

/** A failed disconnection keeps the list and says so. */
export const DisconnectFailed: Story = {
	args: { revokeError: { status: 503 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not disconnect the client")).toBeVisible();
		await expect(canvas.getByText("claude.ai")).toBeVisible();
	},
};

export const Disconnecting: Story = { args: { revokingId: grant.id } };
