import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { ConnectionSyncStatus, IntegrationCatalogEntry, Workspace } from "@/api/types.gen";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

// This route test owns credential HTTP requests; the SSE transport has its own hook suite.
vi.mock("@/hooks/use-sync-events", () => ({ useSyncEvents: () => false }));

function mockConnection(kind: "GITHUB" | "GITLAB", installationId?: number) {
	const listed = workspaceListItem("acme", { providerType: kind });
	const workspace = {
		...listed,
		kind,
		installationId,
		gitlabWebhookRegistered: false,
		hasPersonalAccessToken: installationId == null,
		hasSlackToken: false,
		isPubliclyViewable: false,
		practiceReviewAutoTriggerEnabled: false,
		practiceReviewManualTriggerEnabled: false,
		updatedAt: listed.createdAt,
	} satisfies Workspace;
	const entry = {
		kind,
		displayName: kind === "GITLAB" ? "GitLab" : "GitHub",
		connected: true,
		connectionId: 7,
		connectionState: "ACTIVE",
		credentialsUnreadableSince: installationId == null ? listed.createdAt : undefined,
	} satisfies IntegrationCatalogEntry;
	const status = {
		connectionId: 7,
		connectionState: "ACTIVE",
		kind,
		health: "HEALTHY",
		resourceCounts: { total: 0, errored: 0, pending: 0, stale: 0 },
		backfillSupported: true,
	} satisfies ConnectionSyncStatus;
	server.use(
		http.get("*/workspaces", () => HttpResponse.json([listed])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspace)),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([entry])),
		http.get("*/workspaces/:workspaceSlug/connections/7/sync", () => HttpResponse.json(status)),
		http.get("*/workspaces/:workspaceSlug/connections/7/sync/resources", () =>
			HttpResponse.json([]),
		),
		http.get("*/workspaces/:workspaceSlug/connections/7/sync/jobs", () =>
			HttpResponse.json({ content: [], totalPages: 0 }),
		),
		http.get("*/workspaces/:workspaceSlug/repositories", () => HttpResponse.json([])),
	);
	return { entry, workspace };
}

describe("source-control credential recovery", () => {
	it.each(["GITHUB", "GITLAB"] as const)(
		"replaces an unreadable %s token and restores sync controls",
		async (kind) => {
			const { entry, workspace } = mockConnection(kind);
			const requests: unknown[] = [];
			server.use(
				http.patch("*/workspaces/acme/token", async ({ request }) => {
					requests.push(await request.json());
					entry.credentialsUnreadableSince = undefined;
					return HttpResponse.json(workspace);
				}),
			);
			renderRouteAt("/w/acme/admin/integrations/scm");
			const input = await screen.findByLabelText(
				"New personal access token",
				undefined,
				ROUTE_RENDER_WAIT,
			);
			expect(screen.queryByRole("button", { name: "Sync now" })).toBeNull();

			const user = userEvent.setup();
			await user.type(input, "  replacement-token  ");
			await user.click(screen.getByRole("button", { name: "Replace token" }));

			await screen.findByRole("button", { name: "Sync now" }, ROUTE_RENDER_WAIT);
			expect(requests).toStrictEqual([{ personalAccessToken: "replacement-token" }]);
			expect(screen.queryByText("The stored token can't be read")).toBeNull();
			await waitFor(() => expect(input).toHaveProperty("value", ""));
		},
	);

	it("keeps the draft and the unreadable state when replacement fails", async () => {
		mockConnection("GITLAB");
		server.use(
			http.patch("*/workspaces/acme/token", () =>
				HttpResponse.json(
					{
						status: 409,
						title: "Conflict",
						detail: "This connection changed. Reload before replacing its token.",
					},
					{ status: 409 },
				),
			),
		);
		renderRouteAt("/w/acme/admin/integrations/scm");
		const input = await screen.findByLabelText(
			"New personal access token",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		const user = userEvent.setup();
		await user.type(input, "replacement-token");
		await user.click(screen.getByRole("button", { name: "Replace token" }));

		await screen.findByText("This connection changed. Reload before replacing its token.");
		expect(input).toHaveProperty("value", "replacement-token");
		screen.getByText("The stored token can't be read");
		expect(screen.queryByRole("button", { name: "Sync now" })).toBeNull();
	});

	it("keeps GitHub App connections on the installation recovery path", async () => {
		mockConnection("GITHUB", 123);
		renderRouteAt("/w/acme/admin/integrations/scm");

		const installationLink = await screen.findByRole(
			"link",
			{ name: "Manage installation on GitHub" },
			ROUTE_RENDER_WAIT,
		);
		expect(installationLink.getAttribute("href")).toBe("https://github.com/settings/installations");
		expect(screen.queryByLabelText("New personal access token")).toBeNull();
	});
});

describe("Outline connection drafts", () => {
	it("does not carry a server URL or token into another workspace", async () => {
		mockConnection("GITHUB");
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
			),
			http.get("*/workspaces/:workspaceSlug/connections", () => HttpResponse.json([])),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/admin/integrations/outline");
		const input = await screen.findByLabelText("API token", undefined, ROUTE_RENDER_WAIT);
		const user = userEvent.setup();
		await user.type(screen.getByLabelText("Server URL"), "https://outline.acme.test");
		await user.type(input, "private-workspace-token");

		await act(() =>
			router.navigate({
				to: "/w/$workspaceSlug/admin/integrations/outline",
				params: { workspaceSlug: "other" },
			}),
		);

		await waitFor(() => {
			expect(screen.getByLabelText("API token")).toHaveProperty("value", "");
			expect(screen.getByLabelText("Server URL")).toHaveProperty("value", "");
		});
	});
});
