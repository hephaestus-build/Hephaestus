import type { QueryClient, QueryKey } from "@tanstack/react-query";
import { act, cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type JsonBodyType } from "msw";
import { describe, expect, it, vi } from "vitest";
import { toast } from "@/lib/toast";

import {
	getGitLabRepositorySelectionQueryKey,
	getIntegrationCatalogQueryKey,
	getRepositoriesToMonitorQueryKey,
	getStoppedGitLabProjectsQueryKey,
	listConnectionSyncJobsQueryKey,
	listConnectionSyncResourcesQueryKey,
	listSlackChannelsQueryKey,
	getWorkspaceQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type {
	ConnectionSyncStatus,
	ConnectionDetail,
	GitLabRepositorySelection,
	GitLabStoppedProject,
	IntegrationCatalogEntry,
	SlackChannelCandidate,
	SlackMonitoredChannel,
	SyncJob,
	SyncResourceState,
	Workspace,
} from "@/api/types.gen";
import { zGitLabRepositorySelection } from "@/api/zod.gen";
import type { Wire } from "@/lib/dates";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { type Deferred, deferred } from "@/test/async";
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
		publicActivityEnabled: false,
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
	const detail: Wire<ConnectionDetail> = { id: 7, kind, state: "ACTIVE" };
	const status: ConnectionSyncStatus = {
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
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([entry])),
		http.get("*/workspaces/:workspaceSlug/connections/7", () => HttpResponse.json(detail)),
		http.get("*/workspaces/:workspaceSlug/connections/7/sync", () => HttpResponse.json(status)),
		http.get("*/workspaces/:workspaceSlug/connections/7/sync/resources", () =>
			HttpResponse.json([]),
		),
		http.get("*/workspaces/:workspaceSlug/connections/7/sync/jobs", () =>
			HttpResponse.json({ content: [], totalPages: 0 }),
		),
		http.get("*/workspaces/:workspaceSlug/repositories", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/repositories/selection", () =>
			HttpResponse.json({ repositorySelection: "ALL" } satisfies GitLabRepositorySelection),
		),
		http.get("*/workspaces/:workspaceSlug/repositories/stopped", () =>
			HttpResponse.json([] satisfies GitLabStoppedProject[]),
		),
	);
	return { entry, workspace, detail, status };
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
			expect(screen.queryByText("The stored token cannot be read")).toBeNull();
			await waitFor(() => expect(input).toHaveProperty("value", ""));
		},
	);

	it("shows a refused GitLab token and its calendar expiry, then clears the problem after replacement", async () => {
		const { entry, workspace, detail, status } = mockConnection("GITLAB");
		entry.credentialsUnreadableSince = undefined;
		detail.attentionProblem = "CREDENTIAL_REVOKED";
		detail.tokenExpiresAt = "2026-12-05";
		detail.tokenExpiryCheckedAt = "2026-09-30T03:00:00Z";
		status.health = "DEGRADED";
		server.use(
			http.patch("*/workspaces/acme/token", () => {
				detail.attentionProblem = undefined;
				detail.tokenExpiresAt = undefined;
				detail.tokenExpiryCheckedAt = undefined;
				status.health = "HEALTHY";
				return HttpResponse.json(workspace);
			}),
		);
		renderRouteAt("/w/acme/admin/integrations/scm");
		await screen.findByText("GitLab no longer accepts this token", undefined, ROUTE_RENDER_WAIT);
		screen.getByText("The token expires on 5 December 2026.");
		const user = userEvent.setup();
		await user.type(screen.getByLabelText("New personal access token"), "replacement-token");
		await user.click(screen.getByRole("button", { name: "Replace token" }));
		await waitFor(() =>
			expect(screen.queryByText("GitLab no longer accepts this token")).toBeNull(),
		);
		expect(screen.queryByText("The token expires on 5 December 2026.")).toBeNull();
		await screen.findByText("The token expiry is not available yet.");
	});

	it("says a readable GitHub token's expiry is unknown instead of implying it is valid", async () => {
		const { entry } = mockConnection("GITHUB");
		entry.credentialsUnreadableSince = undefined;
		renderRouteAt("/w/acme/admin/integrations/scm");

		await screen.findByRole("button", { name: "Sync now" }, ROUTE_RENDER_WAIT);
		screen.getByText(
			"The token expiry is not available for this connection. Check the token in GitHub and replace it below if it has expired or been revoked.",
		);
		expect(screen.queryByText("The token has no expiry.")).toBeNull();
		expect(screen.queryByText(/The token expires on/u)).toBeNull();
		const user = userEvent.setup();
		await user.type(screen.getByLabelText("New personal access token"), "replacement-token");
		expect(screen.getByRole("button", { name: "Replace token" })).toHaveProperty("disabled", false);
	});

	it("keeps GitLab token replacement available when its expiry cannot load", async () => {
		const { entry, workspace } = mockConnection("GITLAB");
		entry.credentialsUnreadableSince = undefined;
		const requests: unknown[] = [];
		server.use(
			http.get("*/workspaces/:workspaceSlug/connections/7", () =>
				HttpResponse.json(
					{ status: 503, title: "Service Unavailable", detail: "GitLab is unavailable." },
					{ status: 503 },
				),
			),
			http.patch("*/workspaces/acme/token", async ({ request }) => {
				requests.push(await request.json());
				return HttpResponse.json(workspace);
			}),
		);
		renderRouteAt("/w/acme/admin/integrations/scm");

		await screen.findByText("We could not load the token expiry.", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("The token has no expiry.")).toBeNull();
		expect(screen.queryByText(/The token expires on/u)).toBeNull();
		const user = userEvent.setup();
		const input = screen.getByLabelText("New personal access token");
		await user.type(input, "replacement-token");
		await user.click(screen.getByRole("button", { name: "Replace token" }));
		await waitFor(() =>
			expect(requests).toStrictEqual([{ personalAccessToken: "replacement-token" }]),
		);
		await waitFor(() => expect(input).toHaveProperty("value", ""));
	});

	it.each(["GITHUB", "GITLAB"] as const)(
		"keeps the %s draft and the unreadable state when replacement fails",
		async (kind) => {
			mockConnection(kind);
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
			screen.getByText("The stored token cannot be read");
			expect(screen.queryByRole("button", { name: "Sync now" })).toBeNull();
		},
	);

	it("isolates a pending token replacement when switching workspaces", async () => {
		const { workspace } = mockConnection("GITHUB");
		const response = deferred();
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
			),
			http.patch("*/workspaces/acme/token", async () => {
				await response.promise;
				return HttpResponse.json(workspace);
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		const input = await screen.findByLabelText(
			"New personal access token",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		const user = userEvent.setup();
		await user.type(input, "acme-private-token");
		await user.click(screen.getByRole("button", { name: "Replace token" }));
		await screen.findByRole("button", { name: "Replacing token…" });
		try {
			await act(async () =>
				router.navigate({
					to: "/w/$workspaceSlug/admin/integrations/scm",
					params: { workspaceSlug: "other" },
				}),
			);
			await waitFor(() => {
				expect(screen.getByLabelText("New personal access token")).toHaveProperty(
					"disabled",
					false,
				);
				expect(screen.getByLabelText("New personal access token")).toHaveProperty("value", "");
			});
		} finally {
			response.resolve();
		}
		await waitFor(() =>
			expect(
				queryClient.getQueryState(
					getIntegrationCatalogQueryKey({
						path: { workspaceSlug: "acme" },
					}),
				)?.isInvalidated,
			).toBe(true),
		);
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

/** The workspace's monitored paths as the server lists them, and every Stop and Add it receives. */
function serveMonitored(initial: string[]) {
	const monitored = [...initial];
	const commands: string[] = [];
	const command = (request: Request) => {
		const url = new URL(request.url);
		const name = url.searchParams.get("nameWithOwner") ?? "";
		commands.push(`${request.method} ${url.pathname} ${name}`);
		return name;
	};
	server.use(
		http.get("*/workspaces/:workspaceSlug/repositories", () => HttpResponse.json(monitored)),
		http.delete("*/workspaces/:workspaceSlug/repositories", ({ request }) => {
			monitored.splice(monitored.indexOf(command(request)), 1);
			return new HttpResponse(null, { status: 204 });
		}),
		http.post("*/workspaces/:workspaceSlug/repositories", ({ request }) => {
			monitored.push(command(request));
			return new HttpResponse(null, { status: 201 });
		}),
	);
	return { commands, command };
}

describe("monitored repositories", () => {
	it("stops a GitLab repository, still lists it as stopped after a reload, and resumes it only when added", async () => {
		const { entry } = mockConnection("GITLAB");
		entry.credentialsUnreadableSince = undefined;
		const { commands } = serveMonitored(["acme/platform/api"]);
		renderRouteAt("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole(
				"button",
				{ name: "Stop monitoring acme/platform/api" },
				ROUTE_RENDER_WAIT,
			),
		);
		const dialog = await screen.findByRole("alertdialog");
		const description = within(dialog).getByText(/project on GitLab is not affected/u);
		expect(description.textContent).toContain(
			"To sync it again, add it below with its current path.",
		);
		expect(description.textContent).toContain(
			"This workspace keeps the work Hephaestus already synced",
		);
		expect(description.textContent).not.toContain("erases");
		await user.click(within(dialog).getByRole("button", { name: "Stop monitoring" }));

		await screen.findByText("No repositories monitored yet");
		expect(commands).toStrictEqual(["DELETE /workspaces/acme/repositories acme/platform/api"]);

		cleanup();
		renderRouteAt("/w/acme/admin/integrations/scm");
		await screen.findByText("No repositories monitored yet", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("acme/platform/api")).toBeNull();

		await user.type(screen.getByLabelText("Add a repository"), "acme/platform/api");
		await user.click(screen.getByRole("button", { name: "Add repository" }));
		await screen.findByText("acme/platform/api");
		expect(commands).toStrictEqual([
			"DELETE /workspaces/acme/repositories acme/platform/api",
			"POST /workspaces/acme/repositories acme/platform/api",
		]);
	});

	it("keeps a GitLab repository path when adding it back fails, and adds it on retry", async () => {
		const { entry } = mockConnection("GITLAB");
		entry.credentialsUnreadableSince = undefined;
		const { commands, command } = serveMonitored([]);
		const failure = deferred();
		server.use(
			http.post(
				"*/workspaces/:workspaceSlug/repositories",
				async ({ request }) => {
					command(request);
					await failure.promise;
					return HttpResponse.json(
						{ status: 503, title: "Service Unavailable", detail: "GitLab is unavailable." },
						{ status: 503 },
					);
				},
				{ once: true },
			),
		);
		renderRouteAt("/w/acme/admin/integrations/scm");
		const input = await screen.findByLabelText("Add a repository", undefined, ROUTE_RENDER_WAIT);
		const user = userEvent.setup();
		await user.type(input, "acme/platform/api");
		try {
			await user.click(screen.getByRole("button", { name: "Add repository" }));
			await waitFor(() =>
				expect(commands).toStrictEqual(["POST /workspaces/acme/repositories acme/platform/api"]),
			);
			expect(input).toHaveProperty("value", "acme/platform/api");
		} finally {
			failure.resolve();
		}

		await screen.findByText("We could not add the repository");
		expect(input.getAttribute("aria-invalid")).toBe("true");
		expect(input).toHaveProperty("value", "acme/platform/api");
		screen.getByText("No repositories monitored yet");
		expect(screen.queryByText("acme/platform/api")).toBeNull();

		await user.click(screen.getByRole("button", { name: "Add repository" }));
		await screen.findByText("acme/platform/api");
		expect(commands).toStrictEqual([
			"POST /workspaces/acme/repositories acme/platform/api",
			"POST /workspaces/acme/repositories acme/platform/api",
		]);
		await waitFor(() => expect(input).toHaveProperty("value", ""));
	});

	it("explains a GitHub Stop with the shared outcome and no GitLab-only promise", async () => {
		const { entry } = mockConnection("GITHUB");
		entry.credentialsUnreadableSince = undefined;
		serveMonitored(["octocat/hello-world"]);
		renderRouteAt("/w/acme/admin/integrations/scm");
		await userEvent
			.setup()
			.click(
				await screen.findByRole(
					"button",
					{ name: "Stop monitoring octocat/hello-world" },
					ROUTE_RENDER_WAIT,
				),
			);

		const dialog = await screen.findByRole("alertdialog");
		const description = within(dialog).getByText(/repository on GitHub is not affected/u);
		expect(description.textContent).toContain("If no other workspace on this instance");
		expect(description.textContent).toContain("To sync it again, add it below.");
		expect(description.textContent).not.toContain("stays stopped");
		expect(description.textContent).not.toContain("permanently");
	});
});

const stopReceipt = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;

/**
 * An active GitLab workspace whose server moves a project between its monitored and stopped lists as
 * the backend does: Stop keeps the project's repository item under a new receipt, Add clears it, and a
 * removal must name the listed item and its current receipt. Every write is recorded.
 */
function serveStoppedProjects(monitored: string[], stopped: GitLabStoppedProject[] = []) {
	const { entry } = mockConnection("GITLAB");
	entry.credentialsUnreadableSince = undefined;
	const state = { monitored: [...monitored], stopped: [...stopped], receipts: 100 };
	const roots = new Map(
		stopped.map((project) => [project.nameWithOwner, project.repositoryItemId]),
	);
	const writes: string[] = [];
	const stoppedReads = { count: 0 };
	const stop = (nameWithOwner: string) => {
		state.receipts += 1;
		const repositoryItemId = roots.get(nameWithOwner) ?? String(9000 + roots.size + 1);
		roots.set(nameWithOwner, repositoryItemId);
		state.stopped = [
			...state.stopped.filter((project) => project.nameWithOwner !== nameWithOwner),
			{ repositoryItemId, nameWithOwner, stopReceipt: stopReceipt(state.receipts) },
		];
	};
	server.use(
		http.get("*/workspaces/acme/repositories", () => HttpResponse.json(state.monitored)),
		http.get("*/workspaces/acme/repositories/stopped", () => {
			stoppedReads.count += 1;
			return HttpResponse.json(state.stopped);
		}),
		http.delete("*/workspaces/acme/repositories", ({ request }) => {
			const name = new URL(request.url).searchParams.get("nameWithOwner") ?? "";
			writes.push(`DELETE /workspaces/acme/repositories ${name}`);
			state.monitored = state.monitored.filter((monitoredName) => monitoredName !== name);
			stop(name);
			return new HttpResponse(null, { status: 204 });
		}),
		http.post("*/workspaces/acme/repositories", ({ request }) => {
			const name = new URL(request.url).searchParams.get("nameWithOwner") ?? "";
			writes.push(`POST /workspaces/acme/repositories ${name}`);
			state.monitored.push(name);
			state.stopped = state.stopped.filter((project) => project.nameWithOwner !== name);
			return new HttpResponse(null, { status: 201 });
		}),
		http.delete(
			"*/workspaces/acme/repositories/stopped/:repositoryItemId",
			({ params, request }) => {
				const receipt = new URL(request.url).searchParams.get("stopReceipt") ?? "";
				const repositoryItemId = String(params.repositoryItemId);
				writes.push(`DELETE /workspaces/acme/repositories/stopped/${repositoryItemId} ${receipt}`);
				const listed = state.stopped.find(
					(project) => project.repositoryItemId === repositoryItemId,
				);
				if (listed?.stopReceipt !== receipt) {
					const status = listed === undefined ? 404 : 409;
					return HttpResponse.json(
						{
							status,
							title: "Removal refused",
							detail:
								"This project was added or stopped again after the list was loaded. Reload and try again.",
						},
						{ status },
					);
				}
				state.stopped = state.stopped.filter((project) => project !== listed);
				return new HttpResponse(null, { status: 204 });
			},
		),
	);
	return { state, writes, stoppedReads, stop };
}

async function openStoppedRemoval(user: ReturnType<typeof userEvent.setup>, nameWithOwner: string) {
	await user.click(
		await screen.findByRole(
			"button",
			{ name: `Remove synced work from ${nameWithOwner}` },
			ROUTE_RENDER_WAIT,
		),
	);
	return screen.findByRole("alertdialog", { name: `Remove the synced work of ${nameWithOwner}?` });
}

describe("stopped GitLab projects", () => {
	const stoppedKey = getStoppedGitLabProjectsQueryKey({ path: { workspaceSlug: "acme" } });

	it("lists a stopped project, sends nothing on Cancel, and removes exactly the listed Stop on confirmation", async () => {
		const { writes } = serveStoppedProjects(["acme/platform/api"]);
		renderRouteAt("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole(
				"button",
				{ name: "Stop monitoring acme/platform/api" },
				ROUTE_RENDER_WAIT,
			),
		);
		const stopDialog = await screen.findByRole("alertdialog");
		await user.click(within(stopDialog).getByRole("button", { name: "Stop monitoring" }));
		await screen.findByText("No repositories monitored yet");

		const dialog = await openStoppedRemoval(user, "acme/platform/api");
		expect(within(dialog).getByText(/project on GitLab is not affected/u).textContent).toContain(
			"Practice reviews, feedback and conversations about that work keep their usual retention.",
		);
		await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect(writes).toStrictEqual(["DELETE /workspaces/acme/repositories acme/platform/api"]);

		const confirm = await openStoppedRemoval(user, "acme/platform/api");
		await user.click(within(confirm).getByRole("button", { name: "Remove synced work" }));

		await screen.findByText("No stopped projects");
		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(writes).toStrictEqual([
			"DELETE /workspaces/acme/repositories acme/platform/api",
			`DELETE /workspaces/acme/repositories/stopped/9001 ${stopReceipt(101)}`,
		]);
	});

	it("does not send a refused removal again, and removes the newer Stop only after a new confirmation", async () => {
		const { writes, stoppedReads, stop } = serveStoppedProjects(
			[],
			[
				{
					repositoryItemId: "9001",
					nameWithOwner: "acme/platform/api",
					stopReceipt: stopReceipt(1),
				},
			],
		);
		renderRouteAt("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		const dialog = await openStoppedRemoval(user, "acme/platform/api");
		// Another admin adds the project and stops it again before this confirmation answers.
		stop("acme/platform/api");
		const reads = stoppedReads.count;

		await user.click(within(dialog).getByRole("button", { name: "Remove synced work" }));

		await screen.findByText("We could not remove the synced work");
		await waitFor(() => expect(stoppedReads.count).toBeGreaterThan(reads));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		screen.getByRole("button", { name: "Remove synced work from acme/platform/api" });
		expect(writes).toStrictEqual([
			`DELETE /workspaces/acme/repositories/stopped/9001 ${stopReceipt(1)}`,
		]);

		const fresh = await openStoppedRemoval(user, "acme/platform/api");
		await user.click(within(fresh).getByRole("button", { name: "Remove synced work" }));
		await screen.findByText("No stopped projects");
		expect(writes).toStrictEqual([
			`DELETE /workspaces/acme/repositories/stopped/9001 ${stopReceipt(1)}`,
			`DELETE /workspaces/acme/repositories/stopped/9001 ${stopReceipt(101)}`,
		]);
	});

	it("closes an open confirmation when a newer Stop of the project is read, without sending anything", async () => {
		const { writes, stop } = serveStoppedProjects(
			[],
			[
				{
					repositoryItemId: "9001",
					nameWithOwner: "acme/platform/api",
					stopReceipt: stopReceipt(1),
				},
			],
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		await openStoppedRemoval(user, "acme/platform/api");

		stop("acme/platform/api");
		await reread(queryClient, stoppedKey);

		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		screen.getByRole("button", { name: "Remove synced work from acme/platform/api" });
		expect(writes).toStrictEqual([]);
	});

	it("resumes a stopped project through Add with its current path, which leaves the stopped list", async () => {
		const { writes } = serveStoppedProjects(
			[],
			[
				{
					repositoryItemId: "9001",
					nameWithOwner: "acme/platform/api",
					stopReceipt: stopReceipt(1),
				},
			],
		);
		renderRouteAt("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		await screen.findByRole(
			"button",
			{ name: "Remove synced work from acme/platform/api" },
			ROUTE_RENDER_WAIT,
		);

		await user.type(screen.getByLabelText("Add a repository"), "acme/platform/api");
		await user.click(screen.getByRole("button", { name: "Add repository" }));

		await screen.findByText("No stopped projects");
		screen.getByRole("button", { name: "Stop monitoring acme/platform/api" });
		expect(writes).toStrictEqual(["POST /workspaces/acme/repositories acme/platform/api"]);
	});

	it("offers no stopped projects and no removal when their read is refused", async () => {
		serveStoppedProjects([]);
		serveScmRead("*/workspaces/acme/repositories/stopped", { status: 403 });
		renderRouteAt("/w/acme/admin/integrations/scm");

		await screen.findByText(
			"You do not have access to the stopped projects",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("button", { name: /^Remove synced work/u })).toBeNull();
		expect(screen.queryByText("No stopped projects")).toBeNull();
	});

	it.each([
		["a GitHub connection", "GITHUB", "ACTIVE"],
		["a paused GitLab connection", "GITLAB", "SUSPENDED"],
	] as const)(
		"does not read or offer stopped projects on %s",
		async (_case, kind, connectionState) => {
			const { entry } = mockConnection(kind);
			server.use(
				http.get("*/workspaces/:workspaceSlug/connections/catalog", () =>
					HttpResponse.json([{ ...entry, connectionState, credentialsUnreadableSince: undefined }]),
				),
			);
			const stoppedReads = serveScmRead("*/workspaces/:workspaceSlug/repositories/stopped", {
				status: 200,
				body: [
					{
						repositoryItemId: "9001",
						nameWithOwner: "acme/platform/api",
						stopReceipt: stopReceipt(1),
					},
				],
			});
			renderRouteAt("/w/acme/admin/integrations/scm");

			await screen.findByText("Repository sync state", undefined, ROUTE_RENDER_WAIT);
			expect(screen.queryByText("Stopped projects")).toBeNull();
			expect(screen.queryByRole("button", { name: /^Remove synced work/u })).toBeNull();
			expect(stoppedReads.reads.count).toBe(0);
		},
	);
});

type ProjectChoice = GitLabRepositorySelection["repositorySelection"];

function problem(status: number, detail: string) {
	return HttpResponse.json({ status, title: "Project choice failed", detail }, { status });
}

/**
 * The workspace's stored project choice as the server keeps it, every change the page sends, and
 * the next answers the test chose: a read failure, a refused change, or a change held until `hold`.
 */
function serveProjectChoice(initial: ProjectChoice) {
	const stored: GitLabRepositorySelection = { repositorySelection: initial };
	const changes: ProjectChoice[] = [];
	const reads = { count: 0 };
	const next: {
		read: 200 | 403 | 503;
		refusal: { status: 409 | 503; detail: string } | undefined;
		hold: Promise<void>;
	} = { read: 200, refusal: undefined, hold: Promise.resolve() };
	server.use(
		http.get("*/workspaces/acme/repositories/selection", () => {
			reads.count += 1;
			if (next.read === 403) {
				return problem(403, "You cannot manage this workspace's source control.");
			}
			if (next.read === 503) {
				return problem(503, "GitLab is unavailable.");
			}
			return HttpResponse.json(stored);
		}),
		http.patch("*/workspaces/acme/repositories/selection", async ({ request }) => {
			const { repositorySelection } = zGitLabRepositorySelection.parse(await request.json());
			changes.push(repositorySelection);
			await next.hold;
			const { refusal } = next;
			if (refusal !== undefined) {
				next.refusal = undefined;
				return problem(refusal.status, refusal.detail);
			}
			stored.repositorySelection = repositorySelection;
			return HttpResponse.json(stored);
		}),
	);
	return { changes, reads, next, stored };
}

function projectChoice(name: "All projects in the group" | "Only the projects listed below") {
	return screen.getByRole("radio", { name });
}

function isChosen(name: "All projects in the group" | "Only the projects listed below") {
	return projectChoice(name).getAttribute("aria-checked") === "true";
}

/** An active GitLab connection whose stored project choice the test answers. */
function serveGitLab(initial: ProjectChoice) {
	const { entry } = mockConnection("GITLAB");
	entry.credentialsUnreadableSince = undefined;
	return serveProjectChoice(initial);
}

describe("GitLab project choice", () => {
	it("keeps the stored choice while a narrowing is saved, then shows the server's answer", async () => {
		const choice = serveGitLab("ALL");
		const saved = deferred();
		choice.next.hold = saved.promise;
		renderRouteAt("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole(
				"radio",
				{ name: "Only the projects listed below" },
				ROUTE_RENDER_WAIT,
			),
		);
		const readsBefore = choice.reads.count;
		try {
			await screen.findByText("Saving the project choice…");
			expect(choice.changes).toStrictEqual(["SELECTED"]);
			expect(isChosen("All projects in the group")).toBe(true);
			expect(
				Object.hasOwn(projectChoice("Only the projects listed below").dataset, "disabled"),
			).toBe(true);
			// A second press while the first is saved sends nothing.
			await user.click(projectChoice("Only the projects listed below"));
			expect(choice.changes).toStrictEqual(["SELECTED"]);
			expect(screen.queryByRole("alertdialog")).toBeNull();
		} finally {
			saved.resolve();
		}

		await waitFor(() => expect(isChosen("Only the projects listed below")).toBe(true));
		await waitFor(() => expect(choice.reads.count).toBe(readsBefore + 1));
		expect(screen.queryByText("Saving the project choice…")).toBeNull();
		expect(choice.changes).toStrictEqual(["SELECTED"]);
	});

	it("does not ask again after the question closed because the choice widened elsewhere", async () => {
		const choice = serveGitLab("SELECTED");
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		const all = await screen.findByRole(
			"radio",
			{ name: "All projects in the group" },
			ROUTE_RENDER_WAIT,
		);
		// Taken while the page is not behind a modal; an open dialog makes it inert.
		const selected = projectChoice("Only the projects listed below");
		await user.click(all);
		await screen.findByRole("alertdialog", { name: "Add new projects from the group?" });

		// Another admin widens the choice while the question is open, and the page reads it again.
		choice.stored.repositorySelection = "ALL";
		await reread(
			queryClient,
			getGitLabRepositorySelectionQueryKey({ path: { workspaceSlug: "acme" } }),
		);
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect(all.getAttribute("aria-checked")).toBe("true");
		expect(selected.getAttribute("aria-checked")).toBe("false");
		expect(choice.changes).toStrictEqual([]);

		const readsBefore = choice.reads.count;
		await user.click(selected);
		await waitFor(() => expect(choice.changes).toStrictEqual(["SELECTED"]));
		await waitFor(() => expect(choice.reads.count).toBe(readsBefore + 1));
		await waitFor(() => expect(selected.getAttribute("aria-checked")).toBe("true"));

		// The admin's own narrowing asks nothing, and the closed question does not come back.
		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(choice.changes).toStrictEqual(["SELECTED"]);
	});

	it("asks before adding new projects: Cancel sends nothing, and confirming widens the choice", async () => {
		const choice = serveGitLab("SELECTED");
		renderRouteAt("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("radio", { name: "All projects in the group" }, ROUTE_RENDER_WAIT),
		);
		const question = await screen.findByRole("alertdialog", {
			name: "Add new projects from the group?",
		});
		expect(question.textContent).toContain(
			"Projects you stopped stay stopped until you add them again.",
		);
		await user.click(within(question).getByRole("button", { name: "Cancel" }));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect(choice.changes).toStrictEqual([]);
		expect(isChosen("Only the projects listed below")).toBe(true);

		await user.click(projectChoice("All projects in the group"));
		const again = await screen.findByRole("alertdialog", {
			name: "Add new projects from the group?",
		});
		await user.click(within(again).getByRole("button", { name: "Add new projects" }));

		await waitFor(() => expect(isChosen("All projects in the group")).toBe(true));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect(choice.changes).toStrictEqual(["ALL"]);
	});

	it("keeps the stored choice when a change is refused, changes it on a second try, and widens it back", async () => {
		const choice = serveGitLab("ALL");
		const conflict =
			"The GitLab connection, project selection or monitored projects changed while we checked them. Try again.";
		choice.next.refusal = { status: 409, detail: conflict };
		renderRouteAt("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole(
				"radio",
				{ name: "Only the projects listed below" },
				ROUTE_RENDER_WAIT,
			),
		);

		await screen.findByText("We could not change the project choice");
		screen.getByText(conflict);
		expect(isChosen("All projects in the group")).toBe(true);

		await user.click(projectChoice("Only the projects listed below"));
		await waitFor(() => expect(isChosen("Only the projects listed below")).toBe(true));
		await waitFor(() =>
			expect(screen.queryByText("We could not change the project choice")).toBeNull(),
		);

		await user.click(projectChoice("All projects in the group"));
		const question = await screen.findByRole("alertdialog", {
			name: "Add new projects from the group?",
		});
		await user.click(within(question).getByRole("button", { name: "Add new projects" }));
		await waitFor(() => expect(isChosen("All projects in the group")).toBe(true));
		expect(choice.changes).toStrictEqual(["SELECTED", "SELECTED", "ALL"]);
	});

	it("offers no choice while it cannot be read, and shows it once Retry reads it", async () => {
		const choice = serveGitLab("SELECTED");
		choice.next.read = 503;
		renderRouteAt("/w/acme/admin/integrations/scm");

		await screen.findByText("We could not load the project choice", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("radio")).toBeNull();
		choice.next.read = 200;
		await userEvent.setup().click(screen.getByRole("button", { name: "Retry" }));

		await waitFor(() => expect(isChosen("Only the projects listed below")).toBe(true));
		expect(screen.queryByText("We could not load the project choice")).toBeNull();
		expect(choice.changes).toStrictEqual([]);
	});

	it("offers no choice and no Retry when the read is refused", async () => {
		const choice = serveGitLab("ALL");
		choice.next.read = 403;
		renderRouteAt("/w/acme/admin/integrations/scm");

		await screen.findByText(
			"You do not have access to the project choice",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("radio")).toBeNull();
		screen.getByLabelText("Add a repository");
		expect(choice.changes).toStrictEqual([]);
	});

	it("does not ask for a project choice on a GitHub connection", async () => {
		const { entry } = mockConnection("GITHUB");
		entry.credentialsUnreadableSince = undefined;
		const choice = serveProjectChoice("ALL");
		renderRouteAt("/w/acme/admin/integrations/scm");

		await screen.findByLabelText("Add a repository", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Projects to follow")).toBeNull();
		expect(choice.reads.count).toBe(0);
	});

	it("does not ask for a project choice while the GitLab connection is paused", async () => {
		const { entry } = mockConnection("GITLAB");
		server.use(
			http.get("*/workspaces/:workspaceSlug/connections/catalog", () =>
				HttpResponse.json([
					{ ...entry, connectionState: "SUSPENDED", credentialsUnreadableSince: undefined },
				]),
			),
		);
		const choice = serveProjectChoice("ALL");
		renderRouteAt("/w/acme/admin/integrations/scm");

		await screen.findByText("Repository sync state", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Projects to follow")).toBeNull();
		expect(screen.queryByLabelText("Add a repository")).toBeNull();
		expect(choice.reads.count).toBe(0);
	});
});

/** The answer a source-control read gets until the test chooses another. */
type ScmRead<TBody> = { status: 200; body: TBody } | { status: 403 | 503 };

const SCM_READ_FAILURE = {
	403: "You cannot manage this workspace's source control.",
	503: "Source control is temporarily unavailable.",
} as const;

/** Serves one source-control GET with the answer the test last chose, counting each read. A held answer waits for `hold`. */
function serveScmRead<TBody extends JsonBodyType>(url: string, initial: ScmRead<TBody>) {
	const reads = { count: 0 };
	const current = { answer: initial, hold: Promise.resolve() };
	server.use(
		http.get(url, async () => {
			reads.count += 1;
			await current.hold;
			const { answer } = current;
			return answer.status === 200
				? HttpResponse.json(answer.body)
				: HttpResponse.json(
						{
							status: answer.status,
							title: "Source control read failed",
							detail: SCM_READ_FAILURE[answer.status],
						},
						{ status: answer.status },
					);
		}),
	);
	return {
		reads,
		answer: (next: ScmRead<TBody>, hold = Promise.resolve()) => {
			current.answer = next;
			current.hold = hold;
		},
	};
}

/** Records every write the page sends until `stop`, as `METHOD path`. */
function recordWrites() {
	const writes: string[] = [];
	const record = ({ request }: { request: Request }) => {
		if (request.method !== "GET") {
			writes.push(`${request.method} ${new URL(request.url).pathname}`);
		}
	};
	server.events.on("request:start", record);
	return { writes, stop: () => server.events.removeListener("request:start", record) };
}

/** Reads queries again through their own generated keys, as a poll or another tab's change does. */
async function reread(queryClient: QueryClient, ...queryKeys: QueryKey[]) {
	await act(async () => {
		await Promise.all(
			queryKeys.map(async (queryKey) => queryClient.invalidateQueries({ queryKey })),
		);
	});
}

/** An active GitLab connection whose catalog and monitored repositories the test answers. */
function serveGitLabReads(monitored: string[]) {
	const { entry } = mockConnection("GITLAB");
	entry.credentialsUnreadableSince = undefined;
	return {
		entry,
		catalog: serveScmRead("*/workspaces/acme/connections/catalog", {
			status: 200,
			body: [entry],
		}),
		repositories: serveScmRead("*/workspaces/acme/repositories", {
			status: 200,
			body: monitored,
		}),
	};
}

describe("source-control management reads", () => {
	const catalogKey = getIntegrationCatalogQueryKey({ path: { workspaceSlug: "acme" } });
	const repositoriesKey = getRepositoriesToMonitorQueryKey({ path: { workspaceSlug: "acme" } });

	it("withdraws a refused GitLab connection's open Stop and drafts, and an outage does not bring them back", async () => {
		const { entry, catalog, repositories } = serveGitLabReads(["acme/platform/api"]);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		const recorded = recordWrites();
		try {
			const user = userEvent.setup();
			await user.type(
				await screen.findByLabelText("Add a repository", undefined, ROUTE_RENDER_WAIT),
				"acme/platform/web",
			);
			await user.type(screen.getByLabelText("New personal access token"), "draft-token");
			await user.click(screen.getByRole("button", { name: "Stop monitoring acme/platform/api" }));
			await screen.findByRole("alertdialog", { name: "Stop monitoring acme/platform/api?" });
			const catalogReads = catalog.reads.count;
			const repositoryReads = repositories.reads.count;

			catalog.answer({ status: 403 });
			await reread(queryClient, catalogKey);
			await screen.findByText("You do not have access to the GitLab connection");
			expect(catalog.reads.count).toBe(catalogReads + 1);
			expect(screen.queryByRole("alertdialog")).toBeNull();
			expect(screen.queryByRole("button", { name: /^Stop monitoring/u })).toBeNull();
			expect(screen.queryByLabelText("Add a repository")).toBeNull();
			expect(screen.queryByLabelText("New personal access token")).toBeNull();
			expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();

			catalog.answer({ status: 503 });
			await reread(queryClient, catalogKey);
			await screen.findByText("We could not load the GitLab connection");
			expect(catalog.reads.count).toBe(catalogReads + 2);
			screen.getByRole("button", { name: "Retry" });
			expect(screen.queryByRole("alertdialog")).toBeNull();
			expect(screen.queryByText("acme/platform/api")).toBeNull();
			expect(screen.queryByLabelText("Add a repository")).toBeNull();
			expect(screen.queryByLabelText("New personal access token")).toBeNull();

			repositories.answer({ status: 200, body: ["acme/platform/web"] });
			catalog.answer({ status: 200, body: [entry] });
			await reread(queryClient, repositoriesKey, catalogKey);
			await screen.findByRole("button", { name: "Stop monitoring acme/platform/web" });
			expect(screen.queryByText("acme/platform/api")).toBeNull();
			expect(screen.queryByRole("alertdialog")).toBeNull();
			expect(screen.getByLabelText("Add a repository")).toHaveProperty("value", "");
			expect(screen.getByLabelText("New personal access token")).toHaveProperty("value", "");
			expect(screen.queryByText("We could not load the GitLab connection")).toBeNull();
			expect(catalog.reads.count).toBe(catalogReads + 3);
			expect(repositories.reads.count).toBe(repositoryReads + 1);
			expect(recorded.writes).toStrictEqual([]);
		} finally {
			recorded.stop();
		}
	});

	it("keeps a GitLab connection's open Stop and Add draft through a lookup outage", async () => {
		const { catalog } = serveGitLabReads(["acme/platform/api"]);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		const recorded = recordWrites();
		try {
			const user = userEvent.setup();
			const input = await screen.findByLabelText("Add a repository", undefined, ROUTE_RENDER_WAIT);
			await user.type(input, "acme/platform/web");
			await user.click(screen.getByRole("button", { name: "Stop monitoring acme/platform/api" }));
			await screen.findByRole("alertdialog", { name: "Stop monitoring acme/platform/api?" });
			const catalogReads = catalog.reads.count;

			catalog.answer({ status: 503 });
			await reread(queryClient, catalogKey);
			await screen.findByText("We could not load the GitLab connection");
			expect(catalog.reads.count).toBe(catalogReads + 1);
			const dialog = screen.getByRole("alertdialog", {
				name: "Stop monitoring acme/platform/api?",
			});
			expect(within(dialog).getByRole("button", { name: "Stop monitoring" })).toHaveProperty(
				"disabled",
				false,
			);
			expect(input).toHaveProperty("value", "acme/platform/web");
			expect(recorded.writes).toStrictEqual([]);
		} finally {
			recorded.stop();
		}
	});

	it("keeps a GitLab repository and its open Stop through a failed list refresh, then retries", async () => {
		const { repositories } = serveGitLabReads(["acme/platform/api"]);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		const recorded = recordWrites();
		try {
			const user = userEvent.setup();
			await user.click(
				await screen.findByRole(
					"button",
					{ name: "Stop monitoring acme/platform/api" },
					ROUTE_RENDER_WAIT,
				),
			);
			await screen.findByRole("alertdialog", { name: "Stop monitoring acme/platform/api?" });
			const repositoryReads = repositories.reads.count;

			repositories.answer({ status: 503 });
			await reread(queryClient, repositoriesKey);
			await screen.findByText(/the monitored repositories/iu);
			expect(repositories.reads.count).toBe(repositoryReads + 1);
			const dialog = screen.getByRole("alertdialog", {
				name: "Stop monitoring acme/platform/api?",
			});
			expect(within(dialog).getByRole("button", { name: "Stop monitoring" })).toHaveProperty(
				"disabled",
				false,
			);
			screen.getByText("The monitored repositories may be out of date");
			expect(screen.queryByText("We could not load the monitored repositories")).toBeNull();

			await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
			const response = deferred();
			repositories.answer({ status: 200, body: ["acme/platform/web"] }, response.promise);
			try {
				await user.click(screen.getByRole("button", { name: "Retry" }));
				await waitFor(() => expect(repositories.reads.count).toBe(repositoryReads + 2));
				expect(screen.getByRole("button", { name: "Retrying…" })).toHaveProperty("disabled", true);
				await user.click(screen.getByRole("button", { name: "Stop monitoring acme/platform/api" }));
				await screen.findByRole("alertdialog", { name: "Stop monitoring acme/platform/api?" });
			} finally {
				response.resolve();
			}

			await screen.findByRole("button", { name: "Stop monitoring acme/platform/web" });
			expect(screen.queryByRole("alertdialog")).toBeNull();
			expect(screen.queryByText("acme/platform/api")).toBeNull();
			expect(screen.queryByText("The monitored repositories may be out of date")).toBeNull();
			expect(recorded.writes).toStrictEqual([]);
		} finally {
			recorded.stop();
		}
	});

	it("offers no refused repositories and no Add until the list reads again, then adds the path typed before", async () => {
		const { repositories } = serveGitLabReads(["acme/platform/api"]);
		const added: string[] = [];
		server.use(
			http.post("*/workspaces/acme/repositories", ({ request }) => {
				const names = new URL(request.url).searchParams.getAll("nameWithOwner");
				added.push(...names);
				repositories.answer({ status: 200, body: ["acme/platform/web", ...names] });
				return new HttpResponse(null, { status: 201 });
			}),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		const recorded = recordWrites();
		try {
			const user = userEvent.setup();
			await user.type(
				await screen.findByLabelText("Add a repository", undefined, ROUTE_RENDER_WAIT),
				"acme/platform/docs",
			);
			screen.getByRole("button", { name: "Stop monitoring acme/platform/api" });
			const repositoryReads = repositories.reads.count;

			repositories.answer({ status: 403 });
			await reread(queryClient, repositoriesKey);
			await screen.findByText("You do not have access to the monitored repositories");
			expect(screen.queryByText("acme/platform/api")).toBeNull();
			expect(screen.queryByLabelText("Add a repository")).toBeNull();
			expect(screen.queryByRole("button", { name: "Add repository" })).toBeNull();
			expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();

			repositories.answer({ status: 503 });
			await reread(queryClient, repositoriesKey);
			await screen.findByText("We could not load the monitored repositories");
			expect(screen.queryByText("acme/platform/api")).toBeNull();
			expect(screen.queryByText("The monitored repositories may be out of date")).toBeNull();
			expect(screen.queryByLabelText("Add a repository")).toBeNull();
			expect(screen.queryByRole("button", { name: "Add repository" })).toBeNull();
			expect(added).toStrictEqual([]);

			repositories.answer({ status: 200, body: ["acme/platform/web"] });
			await user.click(screen.getByRole("button", { name: "Retry" }));
			await screen.findByRole("button", { name: "Stop monitoring acme/platform/web" });
			expect(screen.queryByText("acme/platform/api")).toBeNull();
			expect(repositories.reads.count).toBe(repositoryReads + 3);
			const input = screen.getByLabelText("Add a repository");
			expect(input).toHaveProperty("value", "acme/platform/docs");
			expect(added).toStrictEqual([]);

			await user.click(screen.getByRole("button", { name: "Add repository" }));
			await screen.findByRole("button", { name: "Stop monitoring acme/platform/docs" });
			await waitFor(() => expect(input).toHaveProperty("value", ""));
			expect(added).toStrictEqual(["acme/platform/docs"]);
			expect(recorded.writes).toStrictEqual(["POST /workspaces/acme/repositories"]);
			expect(repositories.reads.count).toBe(repositoryReads + 4);
			screen.getByRole("button", { name: "Stop monitoring acme/platform/web" });
		} finally {
			recorded.stop();
		}
	});
});

/** Serves the connection catalog, unavailable until the test makes it available, recording each answer. */
function serveConnectionLookup(entries: JsonBodyType, available: boolean) {
	const answers: number[] = [];
	const lookup = { available };
	server.use(
		http.get("*/workspaces/acme/connections/catalog", () => {
			if (!lookup.available) {
				answers.push(503);
				return HttpResponse.json(
					{
						status: 503,
						title: "Service Unavailable",
						detail: "The connections are temporarily unavailable.",
					},
					{ status: 503 },
				);
			}
			answers.push(200);
			return HttpResponse.json(entries);
		}),
	);
	return { answers, lookup };
}

/**
 * Records the connection id of every sync status read. Connection 7 is unavailable until the test
 * recovers it; an id that names no connection gets the server's answer for that, a 500.
 */
function serveStatusReads(status: ConnectionSyncStatus) {
	const connectionIds: string[] = [];
	const connection = { recovered: false };
	server.use(
		http.get("*/workspaces/acme/connections/:connectionId/sync", ({ params }) => {
			const connectionId = String(params.connectionId);
			connectionIds.push(connectionId);
			if (connectionId !== "7") {
				return HttpResponse.json(
					{
						status: 500,
						title: "Internal server error",
						detail:
							"We could not finish that. Try again. If it keeps failing, contact your instance operator.",
					},
					{ status: 500 },
				);
			}
			if (!connection.recovered) {
				return HttpResponse.json(
					{ status: 503, title: "Service Unavailable", detail: "Sync status is unavailable." },
					{ status: 503 },
				);
			}
			return HttpResponse.json(status);
		}),
	);
	return { connectionIds, connection };
}

describe("source-control connection Retry", () => {
	it("shows no GitLab connection when a retried lookup finds none, without reading a sync status", async () => {
		const { status } = mockConnection("GITLAB");
		const lookups = serveConnectionLookup([], false);
		const statusReads = serveStatusReads(status);
		renderRouteAt("/w/acme/admin/integrations/scm");
		await screen.findByText(
			"We could not load the GitLab connection",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(lookups.answers).toStrictEqual([503]);

		lookups.lookup.available = true;
		await userEvent.setup().click(screen.getByRole("button", { name: "Retry" }));
		await waitFor(() => expect(lookups.answers).toStrictEqual([503, 200]));
		const header = await screen.findByText(
			/^(?:No GitLab connection found for this workspace\.|We could not load GitLab sync status)$/u,
		);
		expect({ header: header.textContent, statusReads: statusReads.connectionIds }).toStrictEqual({
			header: "No GitLab connection found for this workspace.",
			statusReads: [],
		});
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
	});

	it("reads a GitLab connection's status again on Retry and shows it once it loads", async () => {
		const { entry, status } = mockConnection("GITLAB");
		entry.credentialsUnreadableSince = undefined;
		const lookups = serveConnectionLookup([entry], true);
		const statusReads = serveStatusReads(status);
		renderRouteAt("/w/acme/admin/integrations/scm");
		await screen.findByText("We could not load GitLab sync status", undefined, ROUTE_RENDER_WAIT);
		expect(statusReads.connectionIds).toStrictEqual(["7"]);
		const lookupsBefore = lookups.answers.length;

		statusReads.connection.recovered = true;
		await userEvent.setup().click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("button", { name: "Sync now" });
		expect(screen.queryByText("We could not load GitLab sync status")).toBeNull();
		expect(statusReads.connectionIds).toStrictEqual(["7", "7"]);
		expect(lookups.answers).toHaveLength(lookupsBefore + 1);
	});
});

function mockSlackConnection(credentialsUnreadableSince?: string) {
	const { entry, workspace } = mockConnection("GITHUB");
	server.use(
		http.get("*/workspaces/:workspaceSlug", () =>
			HttpResponse.json({
				...workspace,
				hasSlackToken: true,
				slackConnectionId: 7,
			}),
		),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () =>
			HttpResponse.json([
				{ ...entry, kind: "SLACK", displayName: "Slack", credentialsUnreadableSince },
			]),
		),
		http.get("*/workspaces/:workspaceSlug/slack/channels", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/slack/channels/candidates", () => HttpResponse.json([])),
	);
}

describe("Slack credential recovery", () => {
	it("shows an unreadable bot token as unavailable, not as connected", async () => {
		mockSlackConnection("2026-09-20T08:00:00Z");
		renderRouteAt("/w/acme/admin/integrations/slack");

		await screen.findByText("Token unreadable", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Connected")).toBeNull();
		expect(screen.queryByText(/can post in Slack/u)).toBeNull();
		screen.getByText(/reconnect slack to replace it/iu);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: /disconnect slack/iu }).disabled,
		).toBe(false);
	});

	it("reconnects through Slack OAuth for this workspace without disconnecting", async () => {
		mockSlackConnection("2026-09-20T08:00:00Z");
		const vendorUrl = new URL("#slack-authorize", window.location.href).href;
		const response = deferred();
		const initiations: unknown[] = [];
		const requests: Request[] = [];
		const record = ({ request }: { request: Request }) => {
			requests.push(request);
		};
		server.use(
			http.post("*/workspaces/:workspaceSlug/connections", async ({ request, params }) => {
				initiations.push({ workspaceSlug: params.workspaceSlug, body: await request.json() });
				await response.promise;
				return HttpResponse.json({ type: "REDIRECT", vendorUrl });
			}),
		);
		server.events.on("request:start", record);
		try {
			renderRouteAt("/w/acme/admin/integrations/slack");
			const user = userEvent.setup();
			await user.click(
				await screen.findByRole("button", { name: "Reconnect Slack" }, ROUTE_RENDER_WAIT),
			);

			const pending = await screen.findByRole<HTMLButtonElement>("button", {
				name: "Redirecting to Slack…",
			});
			expect(pending.disabled).toBe(true);
			response.resolve();

			await waitFor(() => expect(window.location.href).toBe(vendorUrl));
			expect(initiations).toStrictEqual([
				{ workspaceSlug: "acme", body: { kind: "SLACK", userInput: {} } },
			]);
			expect(
				requests
					.filter((request) => request.method !== "GET")
					.map((request) => `${request.method} ${new URL(request.url).pathname}`),
			).toStrictEqual(["POST /workspaces/acme/connections"]);
		} finally {
			server.events.removeListener("request:start", record);
			window.location.hash = "";
		}
	});

	it("keeps the unreadable connection and says why when Slack OAuth cannot start", async () => {
		mockSlackConnection("2026-09-20T08:00:00Z");
		server.use(
			http.post("*/workspaces/:workspaceSlug/connections", () =>
				HttpResponse.json(
					{ status: 503, title: "Service Unavailable", detail: "Slack is not configured." },
					{ status: 503 },
				),
			),
		);
		renderRouteAt("/w/acme/admin/integrations/slack");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "Reconnect Slack" }, ROUTE_RENDER_WAIT),
		);

		await screen.findByText("Slack is not configured.");
		await waitFor(() =>
			expect(
				screen.getByRole<HTMLButtonElement>("button", { name: "Reconnect Slack" }).disabled,
			).toBe(false),
		);
		screen.getByText("Token unreadable");
	});

	it("shows the bot token as connected again once it reads", async () => {
		mockSlackConnection();
		renderRouteAt("/w/acme/admin/integrations/slack");

		await screen.findByText("Connected", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Token unreadable")).toBeNull();
		screen.getByText(/can post in Slack/iu);
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

		await act(async () =>
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

describe("integration job history", () => {
	it.each([
		{ integration: "scm", kind: "GITHUB" },
		{ integration: "slack", kind: "SLACK" },
		{ integration: "outline", kind: "OUTLINE" },
	] as const)(
		"resets $integration pagination without showing the previous workspace's jobs",
		async ({ integration, kind }) => {
			const { entry } = mockConnection("GITHUB");
			const response = deferred();
			const requestedPages: (string | null)[] = [];
			const job = {
				id: 1,
				createdAt: "2026-09-07T12:00:00Z",
				status: "SUCCEEDED",
				type: "RECONCILIATION",
				trigger: "MANUAL",
				cancelRequested: false,
				itemsProcessed: 123_456,
			} satisfies Wire<SyncJob>;
			server.use(
				http.get("*/workspaces", () =>
					HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
				),
				http.get("*/workspaces/:workspaceSlug/connections/catalog", () =>
					HttpResponse.json([{ ...entry, kind }]),
				),
				http.get("*/workspaces/:workspaceSlug/connections", () =>
					HttpResponse.json([{ id: 7, kind: "OUTLINE", state: "SUSPENDED" }]),
				),
				http.get("*/workspaces/other/connections/7/sync/jobs", async ({ request }) => {
					requestedPages.push(new URL(request.url).searchParams.get("page"));
					await response.promise;
					return HttpResponse.json({ content: [], totalPages: 0 });
				}),
				http.get("*/workspaces/acme/connections/7/sync/jobs", () =>
					HttpResponse.json({ content: [job], totalPages: 2 }),
				),
			);
			const { router } = renderRouteAtWithRouter(`/w/acme/admin/integrations/${integration}`);
			await screen.findByText("123,456", undefined, ROUTE_RENDER_WAIT);
			await userEvent.setup().click(screen.getByRole("button", { name: "Go to next page" }));
			try {
				await act(async () =>
					router.navigate({ to: `/w/other/admin/integrations/${integration}` }),
				);
				await waitFor(() => expect(requestedPages).toStrictEqual(["0"]));
				expect(screen.queryByText("123,456")).toBeNull();
			} finally {
				response.resolve();
			}
			await screen.findByText("No sync jobs yet");
		},
	);
});

describe("canceled sync recovery on the integration overview", () => {
	const stopping: Wire<SyncJob> = {
		id: 91,
		type: "RECONCILIATION",
		status: "RUNNING",
		trigger: "MANUAL",
		cancelRequested: true,
		createdAt: "2026-09-01T00:00:00Z",
	};

	it.each([
		{ returned: stopping, httpStatus: 200, message: "GitLab: Still stopping; no new sync started" },
		{
			returned: { ...stopping, id: 92, status: "PENDING", cancelRequested: false },
			httpStatus: 202,
			message: "GitLab: Sync queued",
		},
		{
			returned: { ...stopping, id: 93, cancelRequested: false },
			httpStatus: 200,
			message: "GitLab: Sync in progress",
		},
	] satisfies { returned: Wire<SyncJob>; httpStatus: number; message: string }[])(
		"checks the server and describes its actual response: $message",
		async ({ returned, message, httpStatus }) => {
			const { entry } = mockConnection("GITLAB");
			entry.credentialsUnreadableSince = undefined;
			const requests: unknown[] = [];
			const response = deferred();
			const announced = vi.spyOn(toast, "message");
			server.use(
				http.get("*/workspaces/acme/connections/7/sync", () =>
					HttpResponse.json({
						connectionId: 7,
						connectionState: "ACTIVE",
						kind: "GITLAB",
						health: "HEALTHY",
						resourceCounts: { total: 1, errored: 0, pending: 0, stale: 0 },
						backfillSupported: true,
						activeJob: stopping,
					} satisfies Wire<ConnectionSyncStatus>),
				),
				http.post("*/workspaces/acme/connections/7/sync/jobs", async ({ request }) => {
					requests.push(await request.json());
					await response.promise;
					return HttpResponse.json(returned, { status: httpStatus });
				}),
			);
			try {
				renderRouteAt("/w/acme/admin/integrations");
				const user = userEvent.setup();
				await user.click(
					await screen.findByRole("button", { name: "Check and retry sync" }, ROUTE_RENDER_WAIT),
				);
				await waitFor(() => expect(requests).toStrictEqual([{ type: "RECONCILIATION" }]));
				expect(screen.getByRole("button", { name: "Checking…" })).toHaveProperty("disabled", true);
				response.resolve();
				await waitFor(() => expect(announced).toHaveBeenCalledWith(message));
			} finally {
				response.resolve();
				announced.mockRestore();
			}
		},
	);

	it.each(["BACKFILL", "INITIAL"] as const)(
		"does not replay a stopping %s as a reconciliation",
		async (type) => {
			const { entry } = mockConnection("GITLAB");
			entry.credentialsUnreadableSince = undefined;
			server.use(
				http.get("*/workspaces/acme/connections/7/sync", () =>
					HttpResponse.json({
						connectionId: 7,
						connectionState: "ACTIVE",
						kind: "GITLAB",
						health: "HEALTHY",
						resourceCounts: { total: 1, errored: 0, pending: 0, stale: 0 },
						backfillSupported: true,
						activeJob: { ...stopping, type },
					} satisfies Wire<ConnectionSyncStatus>),
				),
			);
			renderRouteAt("/w/acme/admin/integrations");
			const stoppingButton = await screen.findByRole(
				"button",
				{ name: "Stopping…" },
				ROUTE_RENDER_WAIT,
			);
			expect(stoppingButton).toHaveProperty("disabled", true);
			expect(screen.queryByRole("button", { name: /check and retry/iu })).toBeNull();
		},
	);
});

/** The answer every sync resource and job read gets until the test chooses another. */
type SyncReads =
	| { status: 200; resources: Wire<SyncResourceState>[]; jobs: Wire<SyncJob>[] }
	| { status: 403 | 503 };

const SYNC_READ_FAILURE = {
	403: "You are not allowed to read this connection.",
	503: "The sync state is temporarily unavailable.",
} as const;

const failed = (status: 403 | 503) =>
	HttpResponse.json(
		{ status, title: "Sync read failed", detail: SYNC_READ_FAILURE[status] },
		{ status },
	);

/** Serves connection 7's resources and jobs, counting each read. A held answer waits for `hold`. */
function serveSyncReads(initial: SyncReads) {
	const requests = { resources: 0, jobs: 0 };
	const current = { reads: initial, hold: Promise.resolve() };
	server.use(
		http.get("*/workspaces/acme/connections/7/sync/resources", async () => {
			requests.resources += 1;
			await current.hold;
			const { reads } = current;
			return reads.status === 200 ? HttpResponse.json(reads.resources) : failed(reads.status);
		}),
		http.get("*/workspaces/acme/connections/7/sync/jobs", async () => {
			requests.jobs += 1;
			await current.hold;
			const { reads } = current;
			return reads.status === 200
				? HttpResponse.json({ content: reads.jobs, totalPages: 1 })
				: failed(reads.status);
		}),
	);
	return {
		requests,
		answer: (reads: SyncReads, hold = Promise.resolve()) => {
			current.reads = reads;
			current.hold = hold;
		},
	};
}

/** Reads the connection's resources and jobs again through their own keys, as the page's poll does. */
async function refreshSyncReads(queryClient: QueryClient) {
	const path = { workspaceSlug: "acme", connectionId: 7 };
	await act(async () => {
		await Promise.all([
			queryClient.invalidateQueries({ queryKey: listConnectionSyncResourcesQueryKey({ path }) }),
			queryClient.invalidateQueries({ queryKey: listConnectionSyncJobsQueryKey({ path }) }),
		]);
	});
}

async function pressEveryRetry(user: ReturnType<typeof userEvent.setup>) {
	for (const retry of screen.getAllByRole("button", { name: "Retry" })) {
		await user.click(retry);
	}
}

function syncedRepository(id: number, name: string, issues: number): Wire<SyncResourceState> {
	const syncedAt = "2026-10-15T08:00:00Z";
	return {
		id,
		externalId: name,
		name,
		type: "REPOSITORY",
		counts: [{ key: "issues", label: "Issues", count: issues, lastSyncedAt: syncedAt }],
		state: "SYNCED",
		lastSyncedAt: syncedAt,
		itemCount: issues,
	};
}

describe("sync evidence through a failed refresh", () => {
	const ledger = syncedRepository(1, "acme/platform/ledger", 3141);
	const billing = syncedRepository(2, "acme/platform/billing", 2718);
	const warned: Wire<SyncJob> = {
		id: 41,
		type: "RECONCILIATION",
		trigger: "SCHEDULED",
		status: "SUCCEEDED_WITH_WARNINGS",
		cancelRequested: false,
		createdAt: "2026-10-15T02:00:00Z",
		startedAt: "2026-10-15T02:00:00Z",
		finishedAt: "2026-10-15T02:03:20Z",
		itemsProcessed: 40,
		itemsTotal: 52,
		errorSummary: "12 issues skipped: their sub-issues did not sync",
		progress: {
			phase: "issues",
			currentStep: "Sub-issue sync stopped after 40 of 52 issues.",
			unitsCompleted: 40,
			unitsTotal: 52,
		},
	};
	const later: Wire<SyncJob> = {
		id: 42,
		type: "RECONCILIATION",
		trigger: "MANUAL",
		status: "SUCCEEDED",
		cancelRequested: false,
		createdAt: "2026-10-15T09:00:00Z",
		itemsProcessed: 77_777,
	};

	it("keeps the loaded repositories and an open job detail through a failed refresh, then retries", async () => {
		const { entry } = mockConnection("GITLAB");
		entry.credentialsUnreadableSince = undefined;
		const reads = serveSyncReads({ status: 200, resources: [ledger, billing], jobs: [warned] });
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		const search = await screen.findByRole(
			"searchbox",
			{ name: "Search repositories" },
			ROUTE_RENDER_WAIT,
		);
		await user.type(search, "ledger");
		await user.click(await screen.findByRole("button", { name: "Show details for job 41" }));
		screen.getByText("Sub-issue sync stopped after 40 of 52 issues.");
		const alertsBefore = screen.queryAllByRole("alert").length;
		const requestsBefore = { ...reads.requests };

		reads.answer({ status: 503 });
		await refreshSyncReads(queryClient);
		await waitFor(() => expect(screen.queryAllByRole("alert")).toHaveLength(alertsBefore + 2));

		expect(reads.requests).toStrictEqual({
			resources: requestsBefore.resources + 1,
			jobs: requestsBefore.jobs + 1,
		});
		screen.getByText("acme/platform/ledger");
		expect(screen.queryByText("acme/platform/billing")).toBeNull();
		expect(screen.getByRole("searchbox", { name: "Search repositories" })).toHaveProperty(
			"value",
			"ledger",
		);
		screen.getByText("1 of 2 repositories");
		screen.getByText("Sub-issue sync stopped after 40 of 52 issues.");
		screen.getByRole("button", { name: "Show error for job 41" });
		screen.getByText("The repository sync state may be out of date");
		screen.getByText("The job history may be out of date");
		expect(screen.queryByText("We could not load the repository sync state")).toBeNull();
		expect(screen.queryByText("We could not load the job history")).toBeNull();

		const response = deferred();
		reads.answer(
			{
				status: 200,
				resources: [syncedRepository(1, "acme/platform/ledger", 3200), billing],
				jobs: [later, warned],
			},
			response.promise,
		);
		try {
			await pressEveryRetry(user);
			await waitFor(() =>
				expect(reads.requests).toStrictEqual({
					resources: requestsBefore.resources + 2,
					jobs: requestsBefore.jobs + 2,
				}),
			);
			const retrying = screen.getAllByRole("button", { name: "Retrying…" });
			expect(retrying.map((button) => button.hasAttribute("disabled"))).toStrictEqual([true, true]);
			screen.getByText("acme/platform/ledger");
			screen.getByText("Sub-issue sync stopped after 40 of 52 issues.");
		} finally {
			response.resolve();
		}

		await screen.findByText("77,777");
		screen.getByText("3,200");
		screen.getByText("Sub-issue sync stopped after 40 of 52 issues.");
		expect(screen.queryByText("The repository sync state may be out of date")).toBeNull();
		expect(screen.queryByText("The job history may be out of date")).toBeNull();
	});

	it("does not bring back refused sync evidence when a later read fails", async () => {
		const { entry } = mockConnection("GITLAB");
		entry.credentialsUnreadableSince = undefined;
		const reads = serveSyncReads({ status: 200, resources: [ledger], jobs: [warned] });
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		await screen.findByText("acme/platform/ledger", undefined, ROUTE_RENDER_WAIT);
		await screen.findByText("40/52");

		reads.answer({ status: 403 });
		await refreshSyncReads(queryClient);
		await screen.findByText("You do not have access to the repository sync state");
		screen.getByText("You do not have access to the job history");
		expect(screen.queryByText("acme/platform/ledger")).toBeNull();
		expect(screen.queryByText("40/52")).toBeNull();

		reads.answer({ status: 503 });
		await refreshSyncReads(queryClient);
		await screen.findByText("We could not load the repository sync state");
		screen.getByText("We could not load the job history");
		expect(screen.queryByText("acme/platform/ledger")).toBeNull();
		expect(screen.queryByText("40/52")).toBeNull();
		expect(screen.queryByText("The repository sync state may be out of date")).toBeNull();
		expect(screen.queryByText("The job history may be out of date")).toBeNull();

		reads.answer({
			status: 200,
			resources: [syncedRepository(3, "acme/platform/console", 1618)],
			jobs: [later],
		});
		await pressEveryRetry(userEvent.setup());
		await screen.findByText("acme/platform/console");
		await screen.findByText("77,777");
		expect(screen.queryByText("acme/platform/ledger")).toBeNull();
		expect(screen.queryByText("40/52")).toBeNull();
	});
});

/** The answer a Slack read gets until the test chooses another. */
type SlackRead<TBody> = { status: 200; body: TBody } | { status: 403 | 503 };

const SLACK_READ_FAILURE = {
	403: "You cannot manage this workspace's Slack channels.",
	503: "Slack is temporarily unavailable.",
} as const;

/** Serves one Slack GET with the answer the test last chose, counting each read. */
function serveSlackRead<TBody extends JsonBodyType>(url: string, initial: SlackRead<TBody>) {
	const reads = { count: 0 };
	const current = { answer: initial };
	server.use(
		http.get(url, () => {
			reads.count += 1;
			const { answer } = current;
			return answer.status === 200
				? HttpResponse.json(answer.body)
				: HttpResponse.json(
						{
							status: answer.status,
							title: "Slack read failed",
							detail: SLACK_READ_FAILURE[answer.status],
						},
						{ status: answer.status },
					);
		}),
	);
	return {
		reads,
		answer: (next: SlackRead<TBody>) => {
			current.answer = next;
		},
	};
}

/** A connected Slack workspace whose channel reads the test controls, recording every consent change. */
function serveSlackChannels(
	channels: SlackRead<Wire<SlackMonitoredChannel>[]>,
	candidates: SlackRead<SlackChannelCandidate[]>,
) {
	mockSlackConnection();
	const changes: unknown[] = [];
	server.use(
		http.patch("*/workspaces/acme/slack/channels/:slackChannelId", async ({ request, params }) => {
			changes.push({ slackChannelId: params.slackChannelId, body: await request.json() });
			return HttpResponse.json(standupChannel);
		}),
	);
	return {
		channels: serveSlackRead("*/workspaces/acme/slack/channels", channels),
		candidates: serveSlackRead("*/workspaces/acme/slack/channels/candidates", candidates),
		changes,
	};
}

/** Holds each Add on the next answer until the test settles it, recording its body. */
function holdSlackAdds(answers: Deferred<201 | 503>[]) {
	const bodies: unknown[] = [];
	server.use(
		http.post("*/workspaces/acme/slack/channels", async ({ request }) => {
			const answer = answers[bodies.length];
			bodies.push(await request.json());
			const status = (await answer?.promise) ?? 503;
			return status === 201
				? HttpResponse.json(
						{ ...standupChannel, id: 9, slackChannelId: "C0974LJBPBK", consentState: "PENDING" },
						{ status },
					)
				: HttpResponse.json(
						{ status, title: "Slack write failed", detail: SLACK_READ_FAILURE[status] },
						{ status },
					);
		}),
	);
	return { bodies };
}

/** Reads the monitored channels again through their own key, as the page's poll does. */
async function rereadSlackChannels(queryClient: QueryClient) {
	await act(async () => {
		await queryClient.invalidateQueries({
			queryKey: listSlackChannelsQueryKey({ path: { workspaceSlug: "acme" } }),
		});
	});
}

/** Holds each consent change on the next answer until the test settles it; later ones answer at once. */
function holdSlackChanges(answers: Deferred<void>[]) {
	const changes: unknown[] = [];
	server.use(
		http.patch("*/workspaces/acme/slack/channels/:slackChannelId", async ({ request, params }) => {
			const answer = answers[changes.length];
			changes.push({ slackChannelId: params.slackChannelId, body: await request.json() });
			await answer?.promise;
			return HttpResponse.json(standupChannel);
		}),
	);
	return { changes };
}

async function openSlackRemove(user: ReturnType<typeof userEvent.setup>, channelName: string) {
	await user.click(await screen.findByRole("button", { name: `Actions for ${channelName}` }));
	await user.click(await screen.findByRole("menuitem", { name: "Remove and erase…" }));
	return screen.findByRole("alertdialog", { name: `Remove #${channelName} and erase its data?` });
}

const standupChannel: Wire<SlackMonitoredChannel> = {
	id: "00000000-0000-4000-8000-000000000002",
	slackChannelId: "C02ACTIVE002",
	slackTeamId: "T0000000000",
	channelName: "team-standup",
	consentState: "ACTIVE",
	optedOutMemberCount: 0,
	consentAnnouncedAt: "2026-10-01T09:00:00Z",
	createdAt: "2026-09-30T09:00:00Z",
};

describe("Slack channel management reads", () => {
	const designChannel: Wire<SlackMonitoredChannel> = {
		...standupChannel,
		id: "00000000-0000-4000-8000-000000000003",
		slackChannelId: "C03DESIGN03",
		channelName: "team-design",
	};
	const introChannel: Wire<SlackMonitoredChannel> = {
		...standupChannel,
		id: "00000000-0000-4000-8000-000000000001",
		slackChannelId: "C01PENDING01",
		channelName: "team-intro",
		consentState: "PENDING",
		consentAnnouncedAt: null,
	};
	const general: SlackChannelCandidate = {
		slackChannelId: "C05GENERAL5",
		channelName: "general",
		privateChannel: false,
		member: true,
		archived: false,
	};

	it("keeps the monitored channels manageable when Slack cannot list channels to add", async () => {
		const slack = serveSlackChannels({ status: 200, body: [standupChannel] }, { status: 503 });
		renderRouteAt("/w/acme/admin/integrations/slack");
		await screen.findByText(
			/^(?:#team-standup|We could not load the monitored channels)$/u,
			undefined,
			ROUTE_RENDER_WAIT,
		);

		screen.getByText("#team-standup");
		const user = userEvent.setup();
		await user.click(screen.getByRole("button", { name: "Actions for team-standup" }));
		await user.click(await screen.findByRole("menuitem", { name: "Pause" }));
		await waitFor(() =>
			expect(slack.changes).toStrictEqual([
				{ slackChannelId: "C02ACTIVE002", body: { consentState: "PAUSED" } },
			]),
		);

		await user.click(screen.getByRole("button", { name: "Add channel" }));
		const dialog = await screen.findByRole("dialog");
		within(dialog).getByText("We could not load the channels from Slack");
		within(dialog).getByLabelText("Paste a channel link or ID");
		expect(within(dialog).queryByRole("combobox", { name: "Channel" })).toBeNull();

		slack.candidates.answer({ status: 200, body: [general] });
		await user.click(within(dialog).getByRole("button", { name: "Retry" }));
		await within(dialog).findByRole("combobox", { name: "Channel" });
		expect(within(dialog).queryByText("We could not load the channels from Slack")).toBeNull();
		screen.getByText("#team-standup");
	});

	it("closes a refused channel's Remove dialog and does not reopen it when the channels read again", async () => {
		const slack = serveSlackChannels(
			{ status: 200, body: [standupChannel] },
			{ status: 200, body: [] },
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/slack");
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "Actions for team-standup" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("menuitem", { name: "Remove and erase…" }));
		const removal = await screen.findByRole("alertdialog", {
			name: "Remove #team-standup and erase its data?",
		});
		await user.type(within(removal).getByLabelText(/to confirm/u), "C02ACTIVE002");
		const readsBefore = slack.channels.reads.count;

		slack.channels.answer({ status: 403 });
		await rereadSlackChannels(queryClient);
		await screen.findByText(/the monitored channels$/u);
		expect(slack.channels.reads.count).toBe(readsBefore + 1);
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		screen.getByText("You do not have access to the monitored channels");
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
		expect(screen.queryByText("#team-standup")).toBeNull();

		slack.channels.answer({ status: 503 });
		await rereadSlackChannels(queryClient);
		await screen.findByText("We could not load the monitored channels");
		expect(screen.queryByText("#team-standup")).toBeNull();
		expect(screen.queryByText("The monitored channels may be out of date")).toBeNull();
		expect(screen.queryByRole("alertdialog")).toBeNull();

		slack.channels.answer({ status: 200, body: [standupChannel, designChannel] });
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByText("#team-design");
		screen.getByText("#team-standup");
		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(slack.channels.reads.count).toBe(readsBefore + 3);

		await user.click(screen.getByRole("button", { name: "Actions for team-standup" }));
		await user.click(await screen.findByRole("menuitem", { name: "Remove and erase…" }));
		const reopened = await screen.findByRole("alertdialog", {
			name: "Remove #team-standup and erase its data?",
		});
		expect(within(reopened).getByLabelText(/to confirm/u)).toHaveProperty("value", "");
		expect(slack.changes).toStrictEqual([]);
	});

	it("keeps a pending Add open with its draft until that request settles", async () => {
		serveSlackChannels({ status: 200, body: [standupChannel] }, { status: 200, body: [] });
		const first = deferred<201 | 503>();
		const second = deferred<201 | 503>();
		const adds = holdSlackAdds([first, second]);
		renderRouteAt("/w/acme/admin/integrations/slack");
		const user = userEvent.setup();
		try {
			await user.click(
				await screen.findByRole("button", { name: "Add channel" }, ROUTE_RENDER_WAIT),
			);
			const dialog = await screen.findByRole("dialog");
			await user.type(within(dialog).getByLabelText("Paste a channel link or ID"), "C0974LJBPBK");
			await user.click(within(dialog).getByRole("button", { name: "Add channel" }));
			await waitFor(() => expect(adds.bodies).toStrictEqual([{ slackChannelId: "C0974LJBPBK" }]));

			await user.keyboard("{Escape}");
			const stillOpen = screen.getByRole("dialog");
			expect(within(stillOpen).getByLabelText("Paste a channel link or ID")).toHaveProperty(
				"value",
				"C0974LJBPBK",
			);
			expect(within(stillOpen).getByRole("button", { name: "Adding…" })).toHaveProperty(
				"disabled",
				true,
			);
			expect(within(stillOpen).queryByRole("button", { name: "Close" })).toBeNull();

			first.resolve(503);
			const retry = await within(stillOpen).findByRole("button", { name: "Add channel" });
			expect(retry).toHaveProperty("disabled", false);
			expect(within(stillOpen).getByLabelText("Paste a channel link or ID")).toHaveProperty(
				"value",
				"C0974LJBPBK",
			);
			within(stillOpen).getByRole("button", { name: "Close" });

			await user.click(retry);
			await waitFor(() => expect(adds.bodies).toHaveLength(2));
			screen.getByRole("dialog");
			second.resolve(201);
			await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		} finally {
			first.resolve(503);
			second.resolve(503);
		}
	});

	it.each([
		{ reopened: "team-standup", channelId: "C02ACTIVE002", finish: "Cancel", changes: 1 },
		{ reopened: "team-design", channelId: "C03DESIGN03", finish: "Remove and erase", changes: 2 },
	])(
		"leaves a Remove of $reopened opened after a refusal to its own answer when the refused Remove completes",
		async ({ reopened, channelId, finish, changes }) => {
			const slack = serveSlackChannels(
				{ status: 200, body: [standupChannel, designChannel] },
				{ status: 200, body: [] },
			);
			const refusedRemoval = deferred();
			const removals = holdSlackChanges([refusedRemoval]);
			const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/slack");
			const user = userEvent.setup();
			try {
				await screen.findByText("#team-standup", undefined, ROUTE_RENDER_WAIT);
				const refused = await openSlackRemove(user, "team-standup");
				await user.type(within(refused).getByLabelText(/to confirm/u), "C02ACTIVE002");
				await user.click(within(refused).getByRole("button", { name: "Remove and erase" }));
				await waitFor(() => expect(removals.changes).toHaveLength(1));
				await user.keyboard("{Escape}");
				const pending = screen.getByRole("alertdialog", {
					name: "Remove #team-standup and erase its data?",
				});
				expect(within(pending).getByLabelText(/to confirm/u)).toHaveProperty(
					"value",
					"C02ACTIVE002",
				);
				expect(within(pending).getByRole("button", { name: "Removing…" })).toHaveProperty(
					"disabled",
					true,
				);
				const readsBefore = slack.channels.reads.count;

				slack.channels.answer({ status: 403 });
				await rereadSlackChannels(queryClient);
				await screen.findByText("You do not have access to the monitored channels");
				await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

				slack.channels.answer({ status: 200, body: [standupChannel, designChannel] });
				await rereadSlackChannels(queryClient);
				const latest = await openSlackRemove(user, reopened);
				await user.type(within(latest).getByLabelText(/to confirm/u), channelId);

				slack.channels.answer({
					status: 200,
					body: [{ ...standupChannel, consentState: "REVOKED" }, designChannel],
				});
				refusedRemoval.resolve();
				await screen.findByText("Revoked");
				expect(slack.channels.reads.count).toBe(readsBefore + 3);
				const stillOpen = screen.getByRole("alertdialog", {
					name: `Remove #${reopened} and erase its data?`,
				});
				expect(within(stillOpen).getByLabelText(/to confirm/u)).toHaveProperty("value", channelId);

				await user.click(within(stillOpen).getByRole("button", { name: finish }));
				await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
				expect(removals.changes).toHaveLength(changes);
			} finally {
				refusedRemoval.resolve();
			}
		},
	);

	it("keeps a pending Activate open through Escape until its own answer closes it", async () => {
		serveSlackChannels({ status: 200, body: [introChannel] }, { status: 200, body: [] });
		const activation = deferred();
		const activations = holdSlackChanges([activation]);
		renderRouteAt("/w/acme/admin/integrations/slack");
		const user = userEvent.setup();
		try {
			await user.click(
				await screen.findByRole("button", { name: "Actions for team-intro" }, ROUTE_RENDER_WAIT),
			);
			await user.click(await screen.findByRole("menuitem", { name: "Activate monitoring…" }));
			const dialog = await screen.findByRole("dialog", {
				name: "Activate monitoring for #team-intro?",
			});
			await user.click(within(dialog).getByRole("button", { name: "Activate monitoring" }));
			await waitFor(() =>
				expect(activations.changes).toStrictEqual([
					{ slackChannelId: "C01PENDING01", body: { consentState: "ACTIVE" } },
				]),
			);

			await user.keyboard("{Escape}");
			const pending = screen.getByRole("dialog", { name: "Activate monitoring for #team-intro?" });
			expect(within(pending).getByRole("button", { name: "Activating…" })).toHaveProperty(
				"disabled",
				true,
			);
			expect(within(pending).queryByRole("button", { name: "Close" })).toBeNull();

			activation.resolve();
			await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
			expect(activations.changes).toHaveLength(1);
		} finally {
			activation.resolve();
		}
	});
});

const slackEntry: Wire<IntegrationCatalogEntry> = {
	kind: "SLACK",
	displayName: "Slack",
	connected: true,
	connectionId: 7,
	connectionState: "ACTIVE",
};

/** A Slack connection whose workspace and catalog reads the test controls, monitoring #team-standup. */
function serveSlackConnection(catalog: SlackRead<Wire<IntegrationCatalogEntry>[]>) {
	const { workspace } = mockConnection("GITHUB");
	const slackWorkspace = { ...workspace, hasSlackToken: true, slackConnectionId: 7 };
	const slack = serveSlackChannels(
		{ status: 200, body: [standupChannel] },
		{ status: 200, body: [] },
	);
	return {
		...slack,
		slackWorkspace,
		workspace: serveSlackRead("*/workspaces/acme", { status: 200, body: slackWorkspace }),
		catalog: serveSlackRead("*/workspaces/acme/connections/catalog", catalog),
	};
}

describe("Slack connection reads", () => {
	const workspaceKey = getWorkspaceQueryKey({ path: { workspaceSlug: "acme" } });
	const catalogKey = getIntegrationCatalogQueryKey({ path: { workspaceSlug: "acme" } });

	it("keeps a pending Add and its draft through a connection lookup outage, then retries the lookup", async () => {
		const slack = serveSlackConnection({ status: 200, body: [slackEntry] });
		const added = deferred<201 | 503>();
		const adds = holdSlackAdds([added]);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/slack");
		const recorded = recordWrites();
		const user = userEvent.setup();
		try {
			await screen.findByText("#team-standup", undefined, ROUTE_RENDER_WAIT);
			await user.click(screen.getByRole("button", { name: "Add channel" }));
			const dialog = await screen.findByRole("dialog");
			const reference = within(dialog).getByLabelText("Paste a channel link or ID");
			await user.type(reference, "C0974LJBPBK");
			await user.click(within(dialog).getByRole("button", { name: "Add channel" }));
			await waitFor(() => expect(adds.bodies).toStrictEqual([{ slackChannelId: "C0974LJBPBK" }]));
			const catalogReads = slack.catalog.reads.count;
			const workspaceReads = slack.workspace.reads.count;

			slack.catalog.answer({ status: 503 });
			await reread(queryClient, catalogKey);
			await screen.findByText("We could not load the Slack connection");
			expect(slack.catalog.reads.count).toBe(catalogReads + 1);
			expect(screen.getByRole("dialog")).toBe(dialog);
			expect(reference).toHaveProperty("value", "C0974LJBPBK");
			expect(within(dialog).getByRole("button", { name: "Adding…" })).toHaveProperty(
				"disabled",
				true,
			);

			slack.workspace.answer({ status: 503 });
			await reread(queryClient, workspaceKey);
			expect(slack.workspace.reads.count).toBe(workspaceReads + 1);
			expect(queryClient.getQueryState(workspaceKey)?.status).toBe("error");
			screen.getByText("We could not load the Slack connection");
			expect(screen.getByRole("dialog")).toBe(dialog);
			expect(reference).toHaveProperty("value", "C0974LJBPBK");
			expect(within(dialog).getByRole("button", { name: "Adding…" })).toHaveProperty(
				"disabled",
				true,
			);
			expect(recorded.writes).toStrictEqual(["POST /workspaces/acme/slack/channels"]);

			added.resolve(503);
			const retryAdd = await within(dialog).findByRole("button", { name: "Add channel" });
			expect(retryAdd).toHaveProperty("disabled", false);
			expect(reference).toHaveProperty("value", "C0974LJBPBK");
			expect(screen.getByRole("dialog")).toBe(dialog);
			expect(adds.bodies).toHaveLength(1);

			await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
			await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
			slack.workspace.answer({ status: 200, body: slack.slackWorkspace });
			slack.catalog.answer({ status: 200, body: [slackEntry] });
			await user.click(screen.getByRole("button", { name: "Retry" }));
			await waitFor(() =>
				expect(screen.queryByText("We could not load the Slack connection")).toBeNull(),
			);
			expect(slack.catalog.reads.count).toBe(catalogReads + 2);
			expect(slack.workspace.reads.count).toBe(workspaceReads + 2);
			screen.getByText("Connected");
			screen.getByText("#team-standup");
			expect(screen.queryByRole("dialog")).toBeNull();
			expect(recorded.writes).toStrictEqual(["POST /workspaces/acme/slack/channels"]);
		} finally {
			added.resolve(503);
			recorded.stop();
		}
	});

	it.each(["catalog", "workspace"] as const)(
		"withdraws the open Remove and sync evidence when the %s read is refused, and an outage does not bring them back",
		async (owner) => {
			const syncedAt = "2026-10-15T08:00:00Z";
			const slack = serveSlackConnection({ status: 503 });
			const owning = {
				catalog: { read: slack.catalog, key: catalogKey },
				workspace: { read: slack.workspace, key: workspaceKey },
			}[owner];
			serveSyncReads({
				status: 200,
				resources: [
					{
						id: 5,
						externalId: "C06ARCHIVE6",
						name: "team-archive",
						type: "CHANNEL",
						counts: [{ key: "messages", label: "Messages", count: 4242, lastSyncedAt: syncedAt }],
						state: "SYNCED",
						lastSyncedAt: syncedAt,
						itemCount: 4242,
					},
				],
				jobs: [
					{
						id: 51,
						type: "RECONCILIATION",
						trigger: "MANUAL",
						status: "SUCCEEDED",
						cancelRequested: false,
						createdAt: syncedAt,
						itemsProcessed: 31_337,
					},
				],
			});
			const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/slack");
			const recorded = recordWrites();
			const user = userEvent.setup();
			try {
				await screen.findByText(
					"We could not load the Slack connection",
					undefined,
					ROUTE_RENDER_WAIT,
				);
				expect(screen.queryByText("Slack channel monitoring")).toBeNull();
				expect(screen.queryByText("team-archive")).toBeNull();

				slack.catalog.answer({ status: 200, body: [slackEntry] });
				await user.click(screen.getByRole("button", { name: "Retry" }));
				await screen.findByText("team-archive");
				await screen.findByText("31,337");
				const removal = await openSlackRemove(user, "team-standup");
				await user.type(within(removal).getByLabelText(/to confirm/u), "C02ACTIVE002");
				const owningReads = owning.read.reads.count;

				owning.read.answer({ status: 403 });
				await reread(queryClient, owning.key);
				await screen.findByText("You do not have access to the Slack connection");
				expect(owning.read.reads.count).toBe(owningReads + 1);
				expect(screen.queryByText("team-archive")).toBeNull();
				expect(screen.queryByText("31,337")).toBeNull();
				expect(screen.queryByRole("alertdialog")).toBeNull();
				expect(screen.queryByText("#team-standup")).toBeNull();
				expect(screen.queryByRole("button", { name: "Add channel" })).toBeNull();
				expect(
					screen.queryByRole("button", { name: /^(?:Reconnect|Disconnect) Slack/u }),
				).toBeNull();
				expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();

				owning.read.answer({ status: 503 });
				await reread(queryClient, owning.key);
				await screen.findByText("We could not load the Slack connection");
				expect(owning.read.reads.count).toBe(owningReads + 2);
				expect(screen.queryByText("team-archive")).toBeNull();
				expect(screen.queryByText("31,337")).toBeNull();
				expect(screen.queryByRole("alertdialog")).toBeNull();
				expect(screen.queryByText("#team-standup")).toBeNull();
				expect(screen.queryByRole("button", { name: "Add channel" })).toBeNull();
				expect(
					screen.queryByRole("button", { name: /^(?:Reconnect|Disconnect) Slack/u }),
				).toBeNull();

				slack.workspace.answer({ status: 200, body: slack.slackWorkspace });
				slack.catalog.answer({
					status: 200,
					body: [{ ...slackEntry, credentialsUnreadableSince: syncedAt }],
				});
				await user.click(screen.getByRole("button", { name: "Retry" }));
				await screen.findByText("Token unreadable");
				screen.getByText("#team-standup");
				await screen.findByText("team-archive");
				expect(screen.queryByText("We could not load the Slack connection")).toBeNull();
				expect(screen.queryByRole("alertdialog")).toBeNull();
				expect(owning.read.reads.count).toBe(owningReads + 3);
				expect(slack.changes).toStrictEqual([]);
				expect(recorded.writes).toStrictEqual([]);
			} finally {
				recorded.stop();
			}
		},
	);
});

describe("source-control owning workspace reads", () => {
	const workspaceKey = getWorkspaceQueryKey({ path: { workspaceSlug: "acme" } });

	it.each(["GITHUB", "GITLAB"] as const)(
		"withdraws %s management when its workspace read is refused and keeps it withdrawn through an outage",
		async (kind) => {
			const { workspace } = mockConnection(kind);
			const owning = serveScmRead("*/workspaces/acme", { status: 200, body: workspace });
			serveScmRead("*/workspaces/acme/repositories", {
				status: 200,
				body: ["acme/platform/api"],
			});
			const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
			const user = userEvent.setup();
			const recorded = recordWrites();
			try {
				await user.type(
					await screen.findByLabelText("Add a repository", undefined, ROUTE_RENDER_WAIT),
					"acme/platform/web",
				);
				await user.type(screen.getByLabelText("New personal access token"), "draft-token");
				await user.click(screen.getByRole("button", { name: "Stop monitoring acme/platform/api" }));
				await screen.findByRole("alertdialog", { name: "Stop monitoring acme/platform/api?" });

				owning.answer({ status: 403 });
				await reread(queryClient, workspaceKey);
				await screen.findByText(/You do not have access to the .+ connection/u);
				await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
				expect(screen.queryByLabelText("New personal access token")).toBeNull();
				expect(screen.queryByLabelText("Add a repository")).toBeNull();
				expect(screen.queryByText("Repository sync state")).toBeNull();
				expect(screen.queryByRole("button", { name: /^Stop monitoring/u })).toBeNull();

				owning.answer({ status: 503 });
				await reread(queryClient, workspaceKey);
				await screen.findByText(/We could not load the .+ connection/u);
				expect(screen.queryByRole("alertdialog")).toBeNull();
				expect(screen.queryByLabelText("New personal access token")).toBeNull();
				expect(screen.queryByLabelText("Add a repository")).toBeNull();
				expect(screen.queryByText("acme/platform/api")).toBeNull();
				expect(screen.queryByText("Repository sync state")).toBeNull();

				owning.answer({ status: 200, body: workspace });
				await reread(queryClient, workspaceKey);
				await screen.findByRole("button", { name: "Stop monitoring acme/platform/api" });
				expect(screen.queryByRole("alertdialog")).toBeNull();
				expect(screen.getByLabelText("Add a repository")).toHaveProperty("value", "");
				expect(screen.getByLabelText("New personal access token")).toHaveProperty("value", "");
				expect(recorded.writes).toStrictEqual([]);
			} finally {
				recorded.stop();
			}
		},
	);

	it("keeps a GitLab token draft and open Stop through a recoverable workspace refresh", async () => {
		const { workspace } = mockConnection("GITLAB");
		const owning = serveScmRead("*/workspaces/acme", { status: 200, body: workspace });
		serveScmRead("*/workspaces/acme/repositories", { status: 200, body: ["acme/platform/api"] });
		const { queryClient } = renderRouteAtWithRouter("/w/acme/admin/integrations/scm");
		const user = userEvent.setup();
		await user.type(
			await screen.findByLabelText("Add a repository", undefined, ROUTE_RENDER_WAIT),
			"acme/platform/web",
		);
		await user.type(screen.getByLabelText("New personal access token"), "draft-token");
		await user.click(screen.getByRole("button", { name: "Stop monitoring acme/platform/api" }));
		const dialog = await screen.findByRole("alertdialog");

		owning.answer({ status: 503 });
		await reread(queryClient, workspaceKey);
		await screen.findByText("We could not load the GitLab connection");
		expect(screen.getByRole("alertdialog")).toBe(dialog);
		expect(within(dialog).getByRole("button", { name: "Stop monitoring" })).toHaveProperty(
			"disabled",
			false,
		);
		expect(screen.getByLabelText("Add a repository")).toHaveProperty("value", "acme/platform/web");
		expect(screen.getByLabelText("New personal access token")).toHaveProperty(
			"value",
			"draft-token",
		);
	});

	it.each([
		{ status: 403, message: "You do not have access to the Source control connection" },
		{ status: 503, message: "We could not load the Source control connection" },
	] as const)(
		"does not infer a source-control provider from a cached catalog when the first workspace read is $status",
		async ({ status, message }) => {
			mockConnection("GITLAB");
			serveScmRead("*/workspaces/acme", { status });
			renderRouteAt("/w/acme/admin/integrations/scm");
			await screen.findByText(message, undefined, ROUTE_RENDER_WAIT);
			expect(screen.queryByLabelText("New personal access token")).toBeNull();
			expect(screen.queryByLabelText("Add a repository")).toBeNull();
			expect(screen.queryByText("Repository sync state")).toBeNull();
		},
	);
});
