import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { WorkspaceProviders } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

// Mounting the real route pulls in the whole authenticated tree, and the last case mounts two
// routes; the timeout is a deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 20_000 });

function serveProviders(providers: WorkspaceProviders) {
	server.use(http.get("*/workspaces/providers", () => HttpResponse.json(providers)));
}

describe("workspace provider availability", () => {
	it("shows the empty state without a GitHub setup link when no provider is configured", async () => {
		serveProviders({ creationPolicy: "SELF_SERVICE" });
		renderRouteAt("/workspaces/new");

		await screen.findByText(
			"No providers are currently available. Contact your administrator.",
			{},
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("link", { name: "Set up workspace with GitHub" })).toBeNull();
	});

	it("hides GitHub setup when the installation URL arrives blank", async () => {
		serveProviders({ creationPolicy: "SELF_SERVICE", github: { appInstallationUrl: "" } });
		renderRouteAt("/workspaces/new");

		await screen.findByText(
			"No providers are currently available. Contact your administrator.",
			{},
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("link", { name: "Set up workspace with GitHub" })).toBeNull();
	});

	it("keeps GitLab available when GitHub is not configured", async () => {
		serveProviders({
			creationPolicy: "SELF_SERVICE",
			gitlab: { defaultServerUrl: "https://gitlab.com" },
		});
		renderRouteAt("/workspaces/new");

		const gitlab = await screen.findByRole(
			"link",
			{ name: "Set up workspace with GitLab" },
			ROUTE_RENDER_WAIT,
		);
		expect(gitlab.getAttribute("href")).toBe("/workspaces/new/gitlab");
		expect(screen.queryByRole("link", { name: "Set up workspace with GitHub" })).toBeNull();
	});

	it("explains unavailable GitHub setup when visiting its route directly", async () => {
		serveProviders({ creationPolicy: "SELF_SERVICE" });
		renderRouteAt("/workspaces/new/github");

		await screen.findByText("GitHub App not configured", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("link", { name: /Install GitHub App/u })).toBeNull();
	});

	it("opens setup with the configured GitHub App installation link", async () => {
		const user = userEvent.setup();
		const installationUrl = "https://github.com/apps/hephaestus/installations/new";
		serveProviders({
			creationPolicy: "SELF_SERVICE",
			github: { appInstallationUrl: installationUrl },
		});
		renderRouteAt("/workspaces/new");

		await user.click(
			await screen.findByRole("link", { name: "Set up workspace with GitHub" }, ROUTE_RENDER_WAIT),
		);
		const install = await screen.findByRole(
			"link",
			{ name: /Install GitHub App/u },
			ROUTE_RENDER_WAIT,
		);
		expect(install.getAttribute("href")).toBe(installationUrl);
	});
});
