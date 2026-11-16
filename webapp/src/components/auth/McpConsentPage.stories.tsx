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

export const PasskeyRequired: Story = {
	args: {
		state: {
			status: "ready",
			request: { ...request, refusal: "PASSKEY_REQUIRED" },
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
