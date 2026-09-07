import type { Meta, StoryObj } from "@storybook/react-vite";
import { fn } from "storybook/test";

import { LoginCard } from "./LoginCard";

const meta = {
	component: LoginCard,
	args: {
		title: "Welcome to Hephaestus",
		description: "Your AI mentor for growing as a software engineer.",
		onSignIn: fn(),
		options: {
			status: "ready",
			providers: [{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" }],
		},
	},
	parameters: { layout: "fullscreen" },
} satisfies Meta<typeof LoginCard>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
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
