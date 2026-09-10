import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { LoginProviderView } from "@/api/types.gen";
import { expectSettledVisible } from "@/test/overlay";

import { LoginProviderFormDialog } from "./LoginProviderFormDialog";

const editing: LoginProviderView = {
	registrationId: "gitlab-acme",
	type: "GITLAB",
	displayName: "ACME GitLab",
	baseUrl: "https://gitlab.acme.test",
	scopes: "read_user",
	enabled: true,
	seededFromEnv: false,
	redirectUri: "https://hephaestus.example.com/api/login/oauth2/code/gitlab-acme",
	createdAt: new Date("2026-05-02T00:00:00Z"),
	updatedAt: new Date("2026-05-02T00:00:00Z"),
};

const editingSlack: LoginProviderView = {
	registrationId: "slack",
	type: "SLACK",
	displayName: "Slack",
	baseUrl: "https://slack.com",
	scopes: "openid profile email",
	enabled: true,
	seededFromEnv: true,
	redirectUri: "https://hephaestus.example.com/api/login/oauth2/code/slack",
	createdAt: new Date("2026-05-02T00:00:00Z"),
	updatedAt: new Date("2026-05-02T00:00:00Z"),
};

const editingOutline: LoginProviderView = {
	registrationId: "outline-acme",
	type: "OUTLINE",
	displayName: "ACME Outline",
	baseUrl: "https://outline.acme.test",
	scopes: "read",
	enabled: true,
	seededFromEnv: false,
	redirectUri: "https://hephaestus.example.com/api/login/oauth2/code/outline-acme",
	createdAt: new Date("2026-07-02T00:00:00Z"),
	updatedAt: new Date("2026-07-02T00:00:00Z"),
};

const meta = {
	component: LoginProviderFormDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		editing: null,
		isSubmitting: false,
		onCreate: fn(),
		onUpdate: fn(),
	},
} satisfies Meta<typeof LoginProviderFormDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Create: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByText("Add login provider"));
		await expect(screen.getByLabelText("Registration ID")).toBeEnabled();
		screen.getByLabelText("Instance base URL");
	},
};

export const CreateOutline: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("combobox", { name: "Provider type" }));
		await userEvent.click(await screen.findByRole("option", { name: /Outline/i }));

		screen.getByLabelText("Instance base URL");
		screen.getByText(/nobody signs in to Hephaestus with it/i);
		screen.getByText(/Settings → Applications/);
	},
};

export const EditSlack: Story = {
	args: { editing: editingSlack },
	play: async () => {
		await expectSettledVisible(await screen.findByText("Edit login provider"));
		await expect(screen.queryByLabelText("Instance base URL")).not.toBeInTheDocument();
		screen.getByText(/Use the same Slack app client ID and secret/);
	},
};

export const EditOutline: Story = {
	args: { editing: editingOutline },
	play: async () => {
		await expectSettledVisible(await screen.findByText("Edit login provider"));
		await expect(screen.getByLabelText("Registration ID")).toBeDisabled();
		await expect(screen.getByLabelText("Instance base URL")).toHaveValue(
			"https://outline.acme.test",
		);
		screen.getByText(editingOutline.redirectUri);
	},
};

export const Edit: Story = {
	args: { editing },
	play: async () => {
		await expectSettledVisible(await screen.findByText("Edit login provider"));
		await expect(screen.getByLabelText("Registration ID")).toBeDisabled();
	},
};

export const CreateOrganization: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("combobox", { name: "Provider type" }));
		await userEvent.click(
			await screen.findByRole("option", { name: "Organization / OpenID Connect" }),
		);
		screen.getByLabelText("Issuer URL");
		screen.getByText(/including its realm path/);
		await expect(screen.getByLabelText("Scopes")).toHaveAttribute(
			"placeholder",
			"openid profile email",
		);
	},
};

export const EditOrganization: Story = {
	args: {
		editing: {
			...editing,
			registrationId: "organization",
			type: "OIDC",
			displayName: "Organization account",
			baseUrl: "https://identity.example.com/realms/team/",
			scopes: "openid profile email",
			redirectUri: "https://hephaestus.example.com/api/login/oauth2/code/organization",
		},
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("Edit login provider"));
		await expect(screen.getByLabelText("Issuer URL")).toHaveValue(
			"https://identity.example.com/realms/team/",
		);
		await expect(screen.getByLabelText("Issuer URL")).toHaveAttribute("readonly");
	},
};

export const SavingOrganization: Story = {
	args: { ...EditOrganization.args, isSubmitting: true },
};
