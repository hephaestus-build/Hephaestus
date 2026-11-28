import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { SignInButtons } from "./SignInButtons";

const meta = {
	component: SignInButtons,
	args: {
		onSignIn: fn(),
		onDevSignIn: fn(async () => undefined),
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
		await expect(screen.queryByRole("button", { name: /slack|outline/iu })).toBeNull();
	},
};

/** The server advertises the `dev` provider outside production only; the form sits beside the others. */
export const WithDevSignIn: Story = {
	args: {
		options: {
			status: "ready",
			providers: [
				{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
				{ registrationId: "dev", displayName: "Dev sign-in", providerType: "DEV" },
			],
		},
	},
	play: async () => {
		await expect(await screen.findByRole("button", { name: "Continue with GitHub" })).toBeEnabled();
		await expect(screen.getByRole("button", { name: "Continue as dev admin" })).toBeEnabled();
	},
};

export const Loading: Story = { args: { options: { status: "loading" } } };

/** An instance with no configured provider must not offer a button that cannot sign anyone in. */
export const Empty: Story = {
	args: { options: { status: "ready", providers: [] } },
	play: async () => {
		await screen.findByText(/No sign-in options are configured/u);
		await expect(screen.queryByRole("button")).toBeNull();
	},
};

export const DiscoveryFailed: Story = {
	args: { options: { status: "error", onRetry: fn() } },
	play: async ({ args }) => {
		if (args.options.status !== "error") {
			throw new Error("story misconfigured");
		}
		await userEvent.click(await screen.findByRole("button", { name: "Retry" }));
		await expect(args.options.onRetry).toHaveBeenCalled();
		await expect(screen.queryByRole("button", { name: /continue with/iu })).toBeNull();
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

/**
 * An operator names an OpenID Connect provider, so the button reads "Continue with" that name. A
 * person who already signs in another way is told to connect it in Settings: a new sign-in through
 * it creates a new account, and accounts are never joined by email.
 */
export const SingleSignOn: Story = {
	args: {
		options: {
			status: "ready",
			providers: [
				{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
				{
					registrationId: "school",
					displayName: "School Login",
					providerType: "OIDC",
					baseUrl: "https://login.school.test/realms/students",
				},
			],
		},
	},
	play: async () => {
		await expect(
			await screen.findByRole("button", { name: "Continue with School Login" }),
		).toBeEnabled();
		await expect(
			screen.getByText(
				"Already use Hephaestus? Sign in as before, then connect School Login in Settings.",
			),
		).toBeVisible();
	},
};
