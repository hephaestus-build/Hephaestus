import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";

import type {
	CreateWorkspaceRequest,
	GitLabPreflightRequest,
	GitLabPreflightResponse,
} from "@/api/types.gen";
import { identityProviders } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { renderRouteAt, renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

// The GitLab instance the shared fixtures configure a login for and link the current user on.
const lrz = "https://gitlab.lrz.de";

/**
 * The server creates GitLab workspaces on `instance`. Every token validates; the first validation
 * answers only once `firstPreflightHeld` settles.
 */
function serveGitLab({
	instance = lrz,
	firstPreflightHeld,
}: { instance?: string; firstPreflightHeld?: Promise<void> } = {}) {
	const requests = {
		preflight: [] as GitLabPreflightRequest[],
		groups: [] as GitLabPreflightRequest[],
		create: [] as CreateWorkspaceRequest[],
	};
	server.use(
		http.get("*/workspaces/providers", () =>
			HttpResponse.json({
				creationPolicy: "SELF_SERVICE",
				gitlab: { defaultServerUrl: instance },
			}),
		),
		http.post<PathParams, GitLabPreflightRequest>(
			"*/workspaces/gitlab/preflight",
			async ({ request }) => {
				requests.preflight.push(await request.json());
				if (requests.preflight.length === 1) {
					await firstPreflightHeld;
				}
				return HttpResponse.json({
					valid: true,
					username: "heph",
				} satisfies GitLabPreflightResponse);
			},
		),
		http.post<PathParams, GitLabPreflightRequest>(
			"*/workspaces/gitlab/groups",
			async ({ request }) => {
				requests.groups.push(await request.json());
				return HttpResponse.json([{ id: 1, name: "Hephaestus", fullPath: "ls1intum/hephaestus" }]);
			},
		),
		http.post<PathParams, CreateWorkspaceRequest>("*/workspaces", async ({ request }) => {
			const body = await request.json();
			requests.create.push(body);
			return HttpResponse.json(
				{
					id: 1,
					workspaceSlug: body.workspaceSlug,
					displayName: body.displayName,
					accountLogin: body.accountLogin,
					createdAt: "2026-09-26T10:00:00Z",
				},
				{ status: 201 },
			);
		}),
	);
	return requests;
}

describe("GitLab workspace wizard", () => {
	it("creates the workspace on the server's GitLab instance", async () => {
		const user = userEvent.setup();
		const requests = serveGitLab();
		const { router } = renderRouteAtWithRouter("/workspaces/new/gitlab");

		await screen.findByDisplayValue(lrz, {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("combobox")).toBeNull();

		await user.type(screen.getByLabelText("Access Token"), "glpat-secret");
		await user.click(screen.getByRole("button", { name: "Validate Token" }));
		await screen.findByText("Token valid");
		await user.click(screen.getByRole("button", { name: "Next" }));
		await user.click(await screen.findByRole("radio", { name: /Hephaestus/u }));
		await user.click(screen.getByRole("button", { name: "Next" }));
		await user.click(await screen.findByRole("button", { name: "Create Workspace" }));

		await screen.findByText('Workspace "Hephaestus" created');
		await waitFor(() => {
			expect(router.state.location.pathname).toBe("/w/hephaestus");
		});
		expect(
			[...requests.preflight, ...requests.groups, ...requests.create].map((r) => r.serverUrl),
		).toStrictEqual([lrz, lrz, lrz]);
	});

	it("ignores a validation that answers after the token was edited", async () => {
		const user = userEvent.setup();
		const firstAnswer = deferred();
		const requests = serveGitLab({ firstPreflightHeld: firstAnswer.promise });
		renderRouteAt("/workspaces/new/gitlab");

		const token = await screen.findByLabelText("Access Token", {}, ROUTE_RENDER_WAIT);
		const validate = screen.getByRole<HTMLButtonElement>("button", { name: "Validate Token" });
		await user.type(token, "glpat-old");
		await user.click(validate);
		await user.clear(token);
		await user.type(token, "glpat-new");
		firstAnswer.resolve();

		await waitFor(() => {
			expect(validate.disabled).toBe(false);
		});
		expect(screen.queryByText("Token valid")).toBeNull();
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Next" }).disabled).toBe(true);

		await user.click(validate);
		await screen.findByText("Token valid");
		await user.click(screen.getByRole("button", { name: "Next" }));

		await waitFor(() => {
			expect(requests.groups.map((r) => r.personalAccessToken)).toStrictEqual(["glpat-new"]);
		});
	});

	it("asks to link the server's instance when the account is linked only on another", async () => {
		const example = "https://gitlab.example.com";
		serveGitLab({ instance: example });
		server.use(
			http.get("*/identity-providers", () =>
				HttpResponse.json([
					...identityProviders,
					{
						registrationId: "gitlab-example",
						displayName: "Example GitLab",
						providerType: "GITLAB",
						baseUrl: example,
					},
				]),
			),
		);
		renderRouteAt("/workspaces/new/gitlab");

		await screen.findByText(
			/link your GitLab account on https:\/\/gitlab\.example\.com/u,
			{},
			ROUTE_RENDER_WAIT,
		);
		screen.getByRole("button", { name: "Link GitLab account" });
		expect(screen.queryByLabelText("Access Token")).toBeNull();
	});

	it("explains that the server's instance has no GitLab sign-in", async () => {
		serveGitLab({ instance: "https://gitlab.example.com" });
		renderRouteAt("/workspaces/new/gitlab");

		await screen.findByRole(
			"heading",
			{ name: "GitLab sign-in isn’t configured" },
			ROUTE_RENDER_WAIT,
		);
		screen.getByRole("link", { name: "Manage login providers" });
		expect(screen.queryByLabelText("Access Token")).toBeNull();
	});

	it("keeps the token step on a failed group load and recovers on retry", async () => {
		const user = userEvent.setup();
		serveGitLab();
		const detail = "GitLab refused this token. Check it and validate it again.";
		server.use(
			http.post(
				"*/workspaces/gitlab/groups",
				() =>
					HttpResponse.json(
						{ title: "Unprocessable Content", status: 422, detail },
						{ status: 422, headers: { "Content-Type": "application/problem+json" } },
					),
				{ once: true },
			),
		);
		renderRouteAt("/workspaces/new/gitlab");

		await user.type(
			await screen.findByLabelText("Access Token", {}, ROUTE_RENDER_WAIT),
			"glpat-secret",
		);
		await user.click(screen.getByRole("button", { name: "Validate Token" }));
		await screen.findByText("Token valid");
		await user.click(screen.getByRole("button", { name: "Next" }));

		await screen.findByText("Failed to load groups");
		screen.getByText(detail);
		expect(screen.queryByRole("radiogroup")).toBeNull();

		await user.click(screen.getByRole("button", { name: "Next" }));
		await screen.findByRole("radio", { name: /Hephaestus/u });
	});

	it("shows why the server refused to create the workspace", async () => {
		const user = userEvent.setup();
		serveGitLab();
		const detail =
			"Link your GitLab account on https://gitlab.lrz.de before creating a workspace there. Go to Settings → Linked Accounts.";
		server.use(
			http.post("*/workspaces", () =>
				HttpResponse.json(
					{ type: "about:blank", title: "Conflict", status: 409, detail, instance: "/workspaces" },
					{ status: 409, headers: { "Content-Type": "application/problem+json" } },
				),
			),
		);
		renderRouteAt("/workspaces/new/gitlab");

		await user.type(
			await screen.findByLabelText("Access Token", {}, ROUTE_RENDER_WAIT),
			"glpat-secret",
		);
		await user.click(screen.getByRole("button", { name: "Validate Token" }));
		await screen.findByText("Token valid");
		await user.click(screen.getByRole("button", { name: "Next" }));
		await user.click(await screen.findByRole("radio", { name: /Hephaestus/u }));
		await user.click(screen.getByRole("button", { name: "Next" }));
		await user.click(await screen.findByRole("button", { name: "Create Workspace" }));

		await screen.findByText(detail);
	});
});
