import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { SignInButtons } from "./SignInButtons";

const meta = {
	component: SignInButtons,
	args: {
		onSignIn: fn(),
		options: {
			status: "ready",
			providers: [
				{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
				{ registrationId: "gitlab", displayName: "GitLab", providerType: "GITLAB" },
			],
		},
	},
	parameters: { layout: "centered" },
} satisfies Meta<typeof SignInButtons>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	// What happens on a press is the route's contract, asserted in `-login-route.test.tsx`.
	play: async () => {
		await expect(await screen.findByRole("button", { name: "Continue with GitHub" })).toBeEnabled();
		await expect(screen.getByRole("button", { name: "Continue with GitLab" })).toBeEnabled();
	},
};

/**
 * Discovery also advertises providers that can only be linked to a session that already exists. An
 * OAuth button for one of them leads to a path that cannot authenticate anybody.
 */
export const LinkOnlyProvidersHidden: Story = {
	args: {
		options: {
			status: "ready",
			providers: [
				{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
				{ registrationId: "slack", displayName: "Slack", providerType: "SLACK" },
				{ registrationId: "outline", displayName: "Outline", providerType: "OUTLINE" },
			],
		},
	},
	play: async () => {
		await screen.findByRole("button", { name: "Continue with GitHub" });
		await expect(screen.queryByRole("button", { name: /slack|outline/i })).toBeNull();
	},
};

export const Loading: Story = { args: { options: { status: "loading" } } };

/** An instance with no configured provider must not offer a button that cannot sign anyone in. */
export const Empty: Story = {
	args: { options: { status: "ready", providers: [] } },
	play: async () => {
		await screen.findByText(/No sign-in options are configured/);
		await expect(screen.queryByRole("button")).toBeNull();
	},
};

export const DiscoveryFailed: Story = {
	args: { options: { status: "error", onRetry: fn() } },
	play: async ({ args }) => {
		if (args.options.status !== "error") throw new Error("story misconfigured");
		await userEvent.click(await screen.findByRole("button", { name: "Try again" }));
		await expect(args.options.onRetry).toHaveBeenCalled();
		await expect(screen.queryByRole("button", { name: /continue with/i })).toBeNull();
	},
};

/** A provider name long enough to wrap; the button grows rather than clipping it. */
export const LongProviderName: Story = {
	args: {
		options: {
			status: "ready",
			providers: [
				{
					registrationId: "university-gitlab",
					displayName: "Technical University of Munich — Research GitLab",
					providerType: "GITLAB",
				},
			],
		},
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
