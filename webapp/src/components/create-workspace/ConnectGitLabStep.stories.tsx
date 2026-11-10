import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import { ConnectGitLabStep } from "./ConnectGitLabStep";
import { withWizardState } from "./stories-utils";

const serverUrl = "https://gitlab.example.com";

/**
 * Connection step in the GitLab workspace creation wizard.
 * Shows the GitLab instance the server reads from and collects an access token for it,
 * asks the route to check it, and shows the answer.
 */
const meta = {
	component: ConnectGitLabStep,
	args: { onValidate: fn() },
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"First step of the GitLab wizard. Checks the access token through the route's preflight request.",
			},
		},
	},
	decorators: [
		withWizardState({ step: 1, serverUrl }),
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

/** Empty initial state — no token entered yet. */
export const Default: Story = {};

/** User has entered a token but hasn't validated yet. */
export const FilledNotValidated: Story = {
	decorators: [
		withWizardState({
			step: 1,
			serverUrl,
			personalAccessToken: "glpat-abc123def456",
		}),
	],
};

/** Token validated successfully — shows green success alert with username. */
export const TokenValid: Story = {
	decorators: [
		withWizardState({
			step: 1,
			serverUrl,
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
			serverUrl,
			personalAccessToken: "glpat-bad-token",
			preflightResult: {
				valid: false,
				error: "Token lacks required “api” scope.",
			},
		}),
	],
};

/** Token validation failed with no specific error message. */
export const TokenInvalidGenericError: Story = {
	decorators: [
		withWizardState({
			step: 1,
			serverUrl,
			personalAccessToken: "glpat-expired",
			preflightResult: { valid: false },
		}),
	],
};

/** The check is under way: the button shows a spinner and sends no second check. */
export const Validating: Story = {
	args: { validating: true },
	decorators: [
		withWizardState({
			step: 1,
			serverUrl,
			personalAccessToken: "glpat-abc123def456",
		}),
	],
};

/** The GitLab instance did not answer the last check of this token. */
export const Unreachable: Story = {
	args: { unreachable: true },
	decorators: [
		withWizardState({
			step: 1,
			serverUrl,
			personalAccessToken: "glpat-abc123def456",
		}),
	],
};
