import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";

import { WorkspaceDangerZoneSettings } from "./WorkspaceDangerZoneSettings";

const noSignInAsked = {
	open: false,
	onOpenChange: fn(),
	providers: [{ registrationId: "github", providerType: "GITHUB", displayName: "GitHub" }],
	loading: false,
	error: false,
	onSignIn: fn(),
	onRetry: fn(),
};

const meta = {
	component: WorkspaceDangerZoneSettings,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "ase",
		ownerCheck: { status: "known", owner: true },
		deleting: false,
		onDelete: fn(async () => undefined),
		confirmation: noSignInAsked,
	},
} satisfies Meta<typeof WorkspaceDangerZoneSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The owner can delete the workspace after typing its slug in the confirmation. */
export const Owner: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^delete workspace$/iu }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.type(dialog.getByLabelText(/to confirm/iu), "ase");
		await userEvent.click(dialog.getByRole("button", { name: /^delete workspace$/iu }));
		await expect(args.onDelete).toHaveBeenCalledOnce();
	},
};

/** An admin who does not own the workspace reads why there is no button. */
export const NotOwner: Story = {
	args: { ownerCheck: { status: "known", owner: false } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/only the workspace owner/iu)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /^delete workspace$/iu })).toBeNull();
	},
};

/** The role is still being read: the section does not guess either way. */
export const CheckingPermissions: Story = {
	args: { ownerCheck: { status: "pending" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(/checking your permissions/iu);
		await expect(canvas.queryByText(/only the workspace owner/iu)).toBeNull();
	},
};

const retryOwnerCheck = fn();

/** The role could not be read: Retry reads it again. */
export const PermissionCheckFailed: Story = {
	args: { ownerCheck: { status: "failed", retrying: false, onRetry: retryOwnerCheck } },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(/could not verify/iu);
		await userEvent.click(canvas.getByRole("button", { name: /^retry$/iu }));
		await expect(retryOwnerCheck).toHaveBeenCalled();
	},
};

/** A second read of the role is under way. */
export const RetryingPermissionCheck: Story = {
	args: { ownerCheck: { status: "failed", retrying: true, onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /retrying/iu })).toBeDisabled();
	},
};

/** The server asked for a recent sign-in before it deletes the workspace. */
export const AsksForARecentSignIn: Story = {
	args: { confirmation: { ...noSignInAsked, open: true, maxAgeSeconds: 300 } },
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Confirm access" });
		await expect(within(dialog).getByRole("button", { name: "Continue with GitHub" })).toBeVisible();
	},
};
