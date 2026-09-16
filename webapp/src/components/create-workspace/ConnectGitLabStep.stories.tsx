import type { Meta, StoryObj } from "@storybook/react";

import { ConnectGitLabStep } from "./ConnectGitLabStep";
import { withWizardState } from "./stories-utils";

/**
 * Connection step in the GitLab workspace creation wizard.
 * Collects GitLab instance URL and personal access token,
 * validates via preflight API call, and shows feedback.
 */
const meta = {
	component: ConnectGitLabStep,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"First step of the GitLab wizard. Validates server URL and PAT via preflight endpoint.",
			},
		},
	},
	decorators: [
		withWizardState({ step: 1 }),
		(Story) => (
			<div className="w-96">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof ConnectGitLabStep>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Empty initial state — no URL or token entered yet. */
export const Default: Story = {};

/** User has entered a self-hosted URL and token but hasn't validated yet. */
export const FilledNotValidated: Story = {
	decorators: [
		withWizardState({
			step: 1,
			serverUrl: "https://gitlab.example.com",
			personalAccessToken: "glpat-abc123def456",
		}),
	],
};

/** Token validated successfully — shows green success alert with username. */
export const TokenValid: Story = {
	decorators: [
		withWizardState({
			step: 1,
			serverUrl: "https://gitlab.example.com",
			personalAccessToken: "glpat-abc123def456",
			preflightResult: { valid: true, username: "admin" },
		}),
	],
};

/** Token validation failed — shows destructive alert with error message. */
export const TokenInvalid: Story = {
	decorators: [
		withWizardState({
			step: 1,
			serverUrl: "https://gitlab.example.com",
			personalAccessToken: "glpat-bad-token",
			preflightResult: {
				valid: false,
				error: "Token lacks required 'api' scope.",
			},
		}),
	],
};

/** Token validation failed with no specific error message. */
export const TokenInvalidGenericError: Story = {
	decorators: [
		withWizardState({
			step: 1,
			personalAccessToken: "glpat-expired",
			preflightResult: { valid: false },
		}),
	],
};
