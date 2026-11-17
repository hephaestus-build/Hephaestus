import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { McpConsentPage } from "./McpConsentPage";

const request = {
	clientHost: "claude.ai",
	clientName: "Claude Code",
	redirectHost: "127.0.0.1",
	loopbackRedirect: true,
	workspaceName: "Practice Lab",
	scopes: ["practices:read"],
	passkeyRequired: false,
};

const passkeyRequest = {
	...request,
	scopes: ["practices:read", "practices:write"],
	passkeyRequired: true,
};

const meta = {
	component: McpConsentPage,
	tags: ["autodocs"],
	args: { state: { status: "ready", request, onApprove: fn(), onRefuse: fn() } },
} satisfies Meta<typeof McpConsentPage>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Ready: Story = {
	play: async ({ canvas, args, userEvent }) => {
		await expect(canvas.getByText("claude.ai")).toBeVisible();
		await expect(canvas.getByText(/It can never read source code/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Allow" }));
		if (args.state.status === "ready") {
			await expect(args.state.onApprove).toHaveBeenCalledOnce();
		}
	},
};

export const ReadAndWrite: Story = {
	args: {
		state: {
			status: "ready",
			request: {
				...request,
				loopbackRedirect: false,
				redirectHost: "client.example",
				scopes: ["practices:read", "practices:write"],
			},
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
};

export const NotAdmin: Story = {
	args: {
		state: {
			status: "ready",
			request: { ...request, refusal: "NOT_ADMIN" },
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Allow" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Deny" })).toBeEnabled();
	},
};

/** Passkey recovery is pending, so the account cannot lend its passkey to a client. */
export const RecoveryPending: Story = {
	args: {
		state: {
			status: "ready",
			request: { ...request, refusal: "PASSKEY_RECOVERY_PENDING" },
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/recovery is not finished/u)).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Allow" })).toBeDisabled();
	},
};

/** The instance requires a passkey from its instance admins, and that proof is never lent to a client. */
export const InstanceAdmin: Story = {
	args: {
		state: {
			status: "ready",
			request: { ...request, refusal: "INSTANCE_ADMIN_PASSKEY_REQUIRED" },
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/never lent to a client/u)).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Allow" })).toBeDisabled();
	},
};

/** The workspace requires an admin passkey: Allow runs the passkey confirmation first. */
export const NeedsPasskey: Story = {
	args: {
		state: {
			status: "ready",
			request: passkeyRequest,
			passkey: { status: "confirm" },
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
	play: async ({ canvas, args, userEvent }) => {
		await expect(canvas.getByText("A passkey is required here")).toBeVisible();
		await expect(canvas.getByText(/ends one hour after you confirm/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Confirm with passkey and allow" }));
		if (args.state.status === "ready") {
			await expect(args.state.onApprove).toHaveBeenCalledOnce();
		}
	},
};

/** The browser's passkey prompt is open. */
export const PasskeyInProgress: Story = {
	args: {
		state: {
			status: "ready",
			request: passkeyRequest,
			passkey: { status: "confirm" },
			pending: "passkey",
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Waiting for your passkey…")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Deny" })).toBeDisabled();
	},
};

/** The prompt was closed or the server refused the passkey; Allow tries again. */
export const PasskeyFailed: Story = {
	args: {
		state: {
			status: "ready",
			request: passkeyRequest,
			passkey: {
				status: "confirm",
				error: "The passkey check did not finish. Try again when you’re ready.",
			},
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Your passkey did not confirm")).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Confirm with passkey and allow" }),
		).toBeEnabled();
	},
};

/** The account has no passkey yet, so it adds one before it can allow. */
export const NeedsAPasskeyAdded: Story = {
	args: {
		state: {
			status: "ready",
			request: passkeyRequest,
			passkey: { status: "add", onAdd: fn() },
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
	play: async ({ canvas, args, userEvent }) => {
		await expect(
			canvas.getByRole("button", { name: "Confirm with passkey and allow" }),
		).toBeDisabled();
		await userEvent.click(canvas.getByRole("button", { name: "Add a passkey" }));
		if (args.state.status === "ready" && args.state.passkey?.status === "add") {
			await expect(args.state.passkey.onAdd).toHaveBeenCalledOnce();
		}
	},
};

export const PasskeyUnsupported: Story = {
	args: {
		state: {
			status: "ready",
			request: passkeyRequest,
			passkey: { status: "unsupported" },
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This browser cannot use passkeys")).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Confirm with passkey and allow" }),
		).toBeDisabled();
	},
};

/** The passkey confirmed and the server allowed the client; the browser leaves for it. */
export const AllowedWithPasskey: Story = {
	args: {
		state: {
			status: "ready",
			request: passkeyRequest,
			passkey: { status: "confirm" },
			pending: "approve",
			onApprove: fn(),
			onRefuse: fn(),
		},
	},
};

export const Approving: Story = {
	args: {
		state: { status: "ready", request, pending: "approve", onApprove: fn(), onRefuse: fn() },
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

export const Error: Story = {
	args: {
		state: { status: "error", message: "Check your connection, then try again.", onRetry: fn() },
	},
};

export const Expired: Story = { args: { state: { status: "gone" } } };

export const InvalidRedirect: Story = {
	args: { state: { status: "invalid", reason: "INVALID_REDIRECT_URI" } },
};

/** The sign-in is older than the server allows for approving, so it asks to confirm access first. */
export const AsksForARecentSignIn: Story = {
	args: {
		confirmAccess: {
			open: true,
			onOpenChange: fn(),
			providers: [{ registrationId: "github", providerType: "GITHUB", displayName: "GitHub" }],
			loading: false,
			error: false,
			maxAgeSeconds: 300,
			onSignIn: fn(),
			onRetry: fn(),
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Confirm access" });
		await expect(
			within(dialog).getByRole("button", { name: "Continue with GitHub" }),
		).toBeVisible();
	},
};
