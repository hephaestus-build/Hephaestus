import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { LoginProviderView } from "@/api/types.gen";

import { LoginProviderFormDialog } from "./LoginProviderFormDialog";

const slackProvider: LoginProviderView = {
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

const outlineProvider: LoginProviderView = {
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

describe("LoginProviderFormDialog", () => {
	it("edits Slack identity providers without requiring a base URL", () => {
		const onUpdate = vi.fn();
		render(
			<LoginProviderFormDialog
				open
				onOpenChange={vi.fn()}
				editing={slackProvider}
				isSubmitting={false}
				onCreate={vi.fn()}
				onUpdate={onUpdate}
			/>,
		);

		expect(screen.queryByLabelText("Instance base URL")).toBeNull();
		screen.getByText(/Use the same Slack app client ID and secret/);
		fireEvent.change(screen.getByLabelText("Client ID"), { target: { value: "slack-client" } });
		fireEvent.change(screen.getByLabelText("Client secret"), {
			target: { value: "slack-secret" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		expect(onUpdate).toHaveBeenCalledWith("slack", {
			displayName: "Slack",
			baseUrl: undefined,
			clientId: "slack-client",
			clientSecret: "slack-secret",
			scopes: "openid profile email",
		});
	});

	it("offers OUTLINE as a creatable provider type — the server supports it, so the admin must be able to pick it", () => {
		render(
			<LoginProviderFormDialog
				open
				onOpenChange={vi.fn()}
				editing={null}
				isSubmitting={false}
				onCreate={vi.fn()}
				onUpdate={vi.fn()}
			/>,
		);

		// The type select must expose OUTLINE; without it the Outline login provider is uncreatable.
		fireEvent.click(screen.getByRole("combobox", { name: "Provider type" }));
		screen.getByRole("option", { name: /Outline/i });
	});

	it("treats Outline like GitLab: it carries an instance base URL, and submits it", () => {
		const onUpdate = vi.fn();
		render(
			<LoginProviderFormDialog
				open
				onOpenChange={vi.fn()}
				editing={outlineProvider}
				isSubmitting={false}
				onCreate={vi.fn()}
				onUpdate={onUpdate}
			/>,
		);

		// Outline is self-hosted per instance → the base URL field is present and pre-filled.
		const baseUrl = screen.getByLabelText<HTMLInputElement>("Instance base URL");
		expect(baseUrl.value).toBe("https://outline.acme.test");

		fireEvent.change(screen.getByLabelText("Client ID"), { target: { value: "outline-client" } });
		fireEvent.change(screen.getByLabelText("Client secret"), {
			target: { value: "outline-secret" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		expect(onUpdate).toHaveBeenCalledWith("outline-acme", {
			displayName: "ACME Outline",
			baseUrl: "https://outline.acme.test",
			clientId: "outline-client",
			clientSecret: "outline-secret",
			scopes: "read",
		});
	});

	it("tells the admin Outline is link-only and which redirect URI to register in Outline", () => {
		render(
			<LoginProviderFormDialog
				open
				onOpenChange={vi.fn()}
				editing={outlineProvider}
				isSubmitting={false}
				onCreate={vi.fn()}
				onUpdate={vi.fn()}
			/>,
		);

		screen.getByText(/nobody signs in to Hephaestus with it/i);
		screen.getByText(/Settings → Applications/);
		screen.getByText(outlineProvider.redirectUri);
	});
});
