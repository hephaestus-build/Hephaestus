import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { expectNoPageOverflow } from "@/test/reflow";

import { LoginPage } from "./LoginPage";

const meta = {
	component: LoginPage,
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
	parameters: { layout: "fullscreen" },
} satisfies Meta<typeof LoginPage>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args }) => {
		await userEvent.click(await screen.findByRole("button", { name: "Continue with GitHub" }));
		await expect(args.onSignIn).toHaveBeenCalledWith("github");
		await expect(screen.getByRole("link", { name: /privacy notice/i })).toHaveAttribute(
			"href",
			"/privacy",
		);
		await expect(screen.getByRole("link", { name: /imprint/i })).toHaveAttribute(
			"href",
			"/imprint",
		);
		await expect(screen.getByRole("link", { name: /privacy notice/i })).toHaveAttribute(
			"target",
			"_blank",
		);
	},
};
export const Cancelled: Story = { args: { error: "access_denied" } };
export const ProviderUnavailable: Story = { args: { error: "idp_unavailable" } };
export const UnknownError: Story = { args: { error: "unknown" } };
export const Loading: Story = { args: { options: { status: "loading" } } };
export const Empty: Story = { args: { options: { status: "ready", providers: [] } } };
export const DiscoveryFailed: Story = { args: { options: { status: "error", onRetry: fn() } } };
export const DevelopmentSignIn: Story = {
	args: {
		options: {
			status: "ready",
			providers: [{ registrationId: "dev", displayName: "Development", providerType: "DEV" }],
		},
	},
};

/** The aside is dropped below `lg`, so the form has to stand on its own down to 320px. */
export const Narrow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
	args: {
		error: "idp_unavailable",
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
};

export const Dark: Story = { globals: { theme: "dark" } };
