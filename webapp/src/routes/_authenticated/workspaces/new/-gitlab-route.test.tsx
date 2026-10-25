import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";

import type {
	CreateWorkspaceRequest,
	GitLabPreflightRequest,
	GitLabPreflightResponse,
	GitLabProject,
	GitLabProjectPage,
} from "@/api/types.gen";
import { identityProviders } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { renderRouteAt, renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

// The GitLab instance the shared fixtures configure a login for and link the current user on.
const lrz = "https://gitlab.lrz.de";

/** The workspace the server answers a creation with; a new one does not review practices yet. */
function created(body: CreateWorkspaceRequest) {
	return {
		id: 1,
		workspaceSlug: body.workspaceSlug,
		displayName: body.displayName,
		accountLogin: body.accountLogin,
		createdAt: "2026-09-26T10:00:00Z",
		practicesEnabled: false,
	};
}

const project = (id: number, name: string, group: string): GitLabProject => ({
	id,
	name,
	fullPath: `${group}/${name.toLowerCase()}`,
});

/** The projects each group's token can read, in path order. */
const PROJECTS: Record<string, GitLabProject[]> = {
	"ls1intum/hephaestus": [
		project(11, "API", "ls1intum/hephaestus"),
		project(12, "Docs", "ls1intum/hephaestus"),
		project(13, "Web", "ls1intum/hephaestus"),
	],
	"ls1intum/artemis": [project(21, "Server", "ls1intum/artemis")],
};

/** Small pages, so a test can step through them. */
const PROJECT_PAGE_SIZE = 2;

/** The server's rule for a GitLab request's project choice: IDs exactly with SELECTED, each positive. */
function consistentSelection({ repositorySelection, selectedProjectIds }: CreateWorkspaceRequest) {
	if (repositorySelection !== "SELECTED") {
		return selectedProjectIds === undefined;
	}
	return (
		selectedProjectIds !== undefined &&
		selectedProjectIds.length > 0 &&
		selectedProjectIds.every((id) => id > 0)
	);
}

/**
 * The server creates GitLab workspaces on `instance`. Every token validates; the first validation
 * answers only once `firstPreflightHeld` settles, and the first project list once
 * `firstProjectsHeld` does.
 */
function serveGitLab({
	instance = lrz,
	firstPreflightHeld,
	firstProjectsHeld,
}: {
	instance?: string;
	firstPreflightHeld?: Promise<void>;
	firstProjectsHeld?: Promise<void>;
} = {}) {
	const requests = {
		preflight: [] as GitLabPreflightRequest[],
		groups: [] as GitLabPreflightRequest[],
		projects: [] as { body: GitLabPreflightRequest; url: URL }[],
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
				return HttpResponse.json([
					{ id: 1, name: "Hephaestus", fullPath: "ls1intum/hephaestus" },
					{ id: 2, name: "Artemis", fullPath: "ls1intum/artemis" },
				]);
			},
		),
		http.post<PathParams, GitLabPreflightRequest>(
			"*/workspaces/gitlab/projects",
			async ({ request }) => {
				const body = await request.json();
				const url = new URL(request.url);
				requests.projects.push({ body, url });
				if (requests.projects.length === 1) {
					await firstProjectsHeld;
				}
				const page = Number(url.searchParams.get("page") ?? "1");
				const search = (url.searchParams.get("search") ?? "").toLowerCase();
				const matching = (PROJECTS[body.groupFullPath ?? ""] ?? []).filter((candidate) =>
					candidate.name.toLowerCase().includes(search),
				);
				const end = page * PROJECT_PAGE_SIZE;
				return HttpResponse.json({
					projects: matching.slice(end - PROJECT_PAGE_SIZE, end),
					...(matching.length > end ? { nextPage: page + 1 } : {}),
				} satisfies GitLabProjectPage);
			},
		),
		http.post<PathParams, CreateWorkspaceRequest>("*/workspaces", async ({ request }) => {
			const body = await request.json();
			requests.create.push(body);
			if (!consistentSelection(body)) {
				return HttpResponse.json(
					{ title: "Bad Request", status: 400, detail: "Inconsistent project choice." },
					{ status: 400, headers: { "Content-Type": "application/problem+json" } },
				);
			}
			return HttpResponse.json(created(body), { status: 201 });
		}),
		// The list the wizard refreshes once the workspace exists.
		http.get("*/workspaces", () => HttpResponse.json(requests.create.map(created))),
	);
	return requests;
}

/** A provider cursor may name a page other than the next integer. */
function serveNoncontiguousProjectPages() {
	const pages: (string | null)[] = [];
	server.use(
		http.post("*/workspaces/gitlab/projects", ({ request }) => {
			const page = new URL(request.url).searchParams.get("page");
			pages.push(page);
			return HttpResponse.json(
				page === "1"
					? {
							projects: [project(11, "API", "ls1intum/hephaestus")],
							nextPage: 3,
						}
					: { projects: [project(13, "Web", "ls1intum/hephaestus")] },
			);
		}),
	);
	return pages;
}

describe("GitLab workspace wizard", () => {
	it("creates the workspace on the server's GitLab instance", async () => {
		const user = userEvent.setup();
		const requests = serveGitLab();
		const { router } = renderRouteAtWithRouter("/workspaces/new/gitlab");

		await screen.findByDisplayValue(lrz, {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("combobox")).toBeNull();

		await user.type(screen.getByLabelText("Access token"), "glpat-secret");
		await user.click(screen.getByRole("button", { name: "Validate token" }));
		await screen.findByText("Token valid");
		await user.click(screen.getByRole("button", { name: "Next" }));
		await user.click(await screen.findByRole("radio", { name: /Hephaestus/u }));
		await user.click(screen.getByRole("button", { name: "Next" }));
		await user.click(
			await within(screen.getByRole("main")).findByRole("button", { name: "Create workspace" }),
		);

		await screen.findByText("Workspace “Hephaestus” created");
		await waitFor(() => {
			expect(router.state.location.pathname).toBe("/w/hephaestus/activity");
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

		const token = await screen.findByLabelText("Access token", {}, ROUTE_RENDER_WAIT);
		const validate = screen.getByRole<HTMLButtonElement>("button", { name: "Validate token" });
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
			/connect your GitLab account on https:\/\/gitlab\.example\.com/u,
			{},
			ROUTE_RENDER_WAIT,
		);
		screen.getByRole("button", { name: "Connect GitLab account" });
		expect(screen.queryByLabelText("Access token")).toBeNull();
	});

	it("explains that the server's instance has no GitLab sign-in", async () => {
		serveGitLab({ instance: "https://gitlab.example.com" });
		renderRouteAt("/workspaces/new/gitlab");

		await screen.findByRole(
			"heading",
			{ name: "GitLab sign-in is not configured" },
			ROUTE_RENDER_WAIT,
		);
		screen.getByRole("link", { name: "Manage login providers" });
		expect(screen.queryByLabelText("Access token")).toBeNull();
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
			await screen.findByLabelText("Access token", {}, ROUTE_RENDER_WAIT),
			"glpat-secret",
		);
		await user.click(screen.getByRole("button", { name: "Validate token" }));
		await screen.findByText("Token valid");
		await user.click(screen.getByRole("button", { name: "Next" }));

		await screen.findByText("We could not load your groups");
		screen.getByText(detail);
		expect(screen.queryByRole("radiogroup")).toBeNull();

		await user.click(screen.getByRole("button", { name: "Next" }));
		await screen.findByRole("radio", { name: /Hephaestus/u });
	});

	it("shows why the server refused to create the workspace", async () => {
		const user = userEvent.setup();
		serveGitLab();
		const detail =
			"Connect your GitLab account on https://gitlab.lrz.de before you create a workspace there. Do this in User settings, under Connected accounts.";
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
			await screen.findByLabelText("Access token", {}, ROUTE_RENDER_WAIT),
			"glpat-secret",
		);
		await user.click(screen.getByRole("button", { name: "Validate token" }));
		await screen.findByText("Token valid");
		await user.click(screen.getByRole("button", { name: "Next" }));
		await user.click(await screen.findByRole("radio", { name: /Hephaestus/u }));
		await user.click(screen.getByRole("button", { name: "Next" }));
		await user.click(
			await within(screen.getByRole("main")).findByRole("button", { name: "Create workspace" }),
		);

		await screen.findByText(detail);
	});
});

describe("GitLab workspace wizard: workspace address", () => {
	const shortSlug = "Enter a URL slug of 3 to 51 characters: a to z, 0 to 9, or hyphens";

	it.each([
		["QA", "qa"],
		["Команда", ""],
	])(
		"says how to fix the slug made from %s once Create workspace is pressed",
		async (name, slug) => {
			const user = userEvent.setup();
			const requests = serveGitLab();
			renderRouteAt("/workspaces/new/gitlab");
			await configure(user);

			const nameField = screen.getByRole("textbox", { name: "Display name" });
			await user.clear(nameField);
			await user.type(nameField, name);
			const slugField = screen.getByRole<HTMLInputElement>("textbox", { name: "URL slug" });
			expect(slugField.value).toBe(slug);
			// Nothing interrupts the reader while they type.
			expect(screen.queryByRole("alert")).toBeNull();

			await user.click(createButton());
			expect(
				screen.getByRole("textbox", { name: "URL slug", description: new RegExp(shortSlug, "u") }),
			).toBe(slugField);
			expect(document.activeElement).toBe(slugField);
			expect(requests.create).toStrictEqual([]);

			await user.clear(slugField);
			await user.type(slugField, "qa-team");
			expect(slugField.getAttribute("aria-invalid")).toBe("false");
			await user.click(createButton());
			await screen.findByText(`Workspace “${name}” created`);
			expect(requests.create.map((body) => body.workspaceSlug)).toStrictEqual(["qa-team"]);
		},
	);
});

/** Validates a token, chooses `group` and arrives at the configure step. */
async function configure(user: ReturnType<typeof userEvent.setup>, group = /Hephaestus/u) {
	await user.type(
		await screen.findByLabelText("Access token", {}, ROUTE_RENDER_WAIT),
		"glpat-secret",
	);
	await user.click(screen.getByRole("button", { name: "Validate token" }));
	await screen.findByText("Token valid");
	await user.click(screen.getByRole("button", { name: "Next" }));
	await user.click(await screen.findByRole("radio", { name: group }));
	await user.click(screen.getByRole("button", { name: "Next" }));
	await screen.findByRole("radio", { name: /^All projects/u });
}

function createButton() {
	return within(screen.getByRole("main")).getByRole<HTMLButtonElement>("button", {
		name: "Create workspace",
	});
}

describe("GitLab workspace wizard: projects to monitor", () => {
	it("creates a workspace for all projects without listing them or sending project IDs", async () => {
		const user = userEvent.setup();
		const requests = serveGitLab();
		renderRouteAt("/workspaces/new/gitlab");
		await configure(user);

		expect(screen.getByRole("radio", { name: /^All projects/u }).getAttribute("aria-checked")).toBe(
			"true",
		);
		await user.click(createButton());

		await screen.findByText("Workspace “Hephaestus” created");
		expect(requests.projects).toStrictEqual([]);
		expect(requests.create).toHaveLength(1);
		expect(requests.create[0]?.repositorySelection).toBe("ALL");
		expect(requests.create[0]).not.toHaveProperty("selectedProjectIds");
	});

	it("creates a workspace for projects chosen across pages and a search, with the token only in the body", async () => {
		const user = userEvent.setup();
		const requests = serveGitLab();
		renderRouteAt("/workspaces/new/gitlab");
		await configure(user);

		await user.click(screen.getByRole("radio", { name: /^Selected projects/u }));
		await user.click(await screen.findByRole("checkbox", { name: "API" }));
		await user.click(screen.getByRole("button", { name: "Next page" }));
		await user.click(await screen.findByRole("checkbox", { name: "Web" }));

		// A search reads its first page again, and what was chosen on other pages stays chosen.
		await user.type(screen.getByRole("searchbox", { name: "Search projects" }), "api");
		await user.click(screen.getByRole("button", { name: "Search" }));
		await waitFor(() => {
			expect(screen.queryByRole("checkbox", { name: "Web" })).toBeNull();
		});
		const api = await screen.findByRole("checkbox", { name: "API" });
		expect(api.getAttribute("aria-checked")).toBe("true");
		screen.getByText("2 projects selected");

		await user.click(createButton());
		await screen.findByText("Workspace “Hephaestus” created");

		expect(requests.create[0]).toMatchObject({
			repositorySelection: "SELECTED",
			selectedProjectIds: [11, 13],
		});
		expect(
			requests.projects.map(({ body, url }) => ({
				token: body.personalAccessToken,
				group: body.groupFullPath,
				page: url.searchParams.get("page"),
				search: url.searchParams.get("search"),
			})),
		).toStrictEqual([
			{ token: "glpat-secret", group: "ls1intum/hephaestus", page: "1", search: null },
			{ token: "glpat-secret", group: "ls1intum/hephaestus", page: "2", search: null },
			{ token: "glpat-secret", group: "ls1intum/hephaestus", page: "1", search: "api" },
		]);
		expect(requests.projects.some(({ url }) => url.href.includes("glpat"))).toBe(false);
	});

	it("asks for the page the server names next, not the one after the current page", async () => {
		const user = userEvent.setup();
		serveGitLab();
		const pages = serveNoncontiguousProjectPages();
		renderRouteAt("/workspaces/new/gitlab");
		await configure(user);

		await user.click(screen.getByRole("radio", { name: /^Selected projects/u }));
		await screen.findByRole("checkbox", { name: "API" });
		await user.click(screen.getByRole("button", { name: "Next page" }));

		await screen.findByRole("checkbox", { name: "Web" });
		expect(pages).toStrictEqual(["1", "3"]);
		// The server named no page after this one.
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Next page" }).disabled).toBe(
			true,
		);
	});

	it("waits for a chosen project before it creates, and sends none once all projects is chosen again", async () => {
		const user = userEvent.setup();
		const requests = serveGitLab();
		renderRouteAt("/workspaces/new/gitlab");
		await configure(user);

		await user.click(screen.getByRole("radio", { name: /^Selected projects/u }));
		await screen.findByRole("checkbox", { name: "API" });
		screen.getByText("Choose at least one project, or monitor all projects.");
		expect(createButton().disabled).toBe(true);

		await user.click(screen.getByRole("checkbox", { name: "API" }));
		expect(createButton().disabled).toBe(false);

		// Back to all projects and to selected projects again: the choice and its list are still there.
		await user.click(screen.getByRole("radio", { name: /^All projects/u }));
		expect(screen.queryByRole("checkbox", { name: "API" })).toBeNull();
		await user.click(screen.getByRole("radio", { name: /^Selected projects/u }));
		expect(screen.getByRole("checkbox", { name: "API" }).getAttribute("aria-checked")).toBe("true");
		expect(requests.projects).toHaveLength(1);

		await user.click(screen.getByRole("radio", { name: /^All projects/u }));
		await user.click(createButton());
		await screen.findByText("Workspace “Hephaestus” created");
		expect(requests.create[0]?.repositorySelection).toBe("ALL");
		expect(requests.create[0]).not.toHaveProperty("selectedProjectIds");
	});

	it("shows no projects that answer for a group the user has left", async () => {
		const user = userEvent.setup();
		const hephaestusAnswer = deferred();
		const requests = serveGitLab({ firstProjectsHeld: hephaestusAnswer.promise });
		renderRouteAt("/workspaces/new/gitlab");
		try {
			await configure(user);
			await user.click(screen.getByRole("radio", { name: /^Selected projects/u }));
			await waitFor(() => expect(requests.projects).toHaveLength(1));

			await user.click(screen.getByRole("button", { name: "Back to previous step" }));
			await user.click(await screen.findByRole("radio", { name: /Artemis/u }));
			await user.click(screen.getByRole("button", { name: "Next" }));
			await user.click(await screen.findByRole("radio", { name: /^Selected projects/u }));
			// The left group's list answers first, then the current one.
			hephaestusAnswer.resolve();
			await screen.findByRole("checkbox", { name: "Server" });

			expect(screen.queryByRole("checkbox", { name: "API" })).toBeNull();
			expect(requests.projects.map(({ body }) => body.groupFullPath)).toStrictEqual([
				"ls1intum/hephaestus",
				"ls1intum/artemis",
			]);
		} finally {
			hephaestusAnswer.resolve();
		}
	});

	it("keeps selected projects on a failed list, says why, and lists them on retry", async () => {
		const user = userEvent.setup();
		const requests = serveGitLab();
		const detail = "GitLab did not answer. Try again in a moment.";
		server.use(
			http.post(
				"*/workspaces/gitlab/projects",
				() =>
					HttpResponse.json(
						{ title: "Bad Gateway", status: 502, detail },
						{ status: 502, headers: { "Content-Type": "application/problem+json" } },
					),
				{ once: true },
			),
		);
		renderRouteAt("/workspaces/new/gitlab");
		await configure(user);

		await user.click(screen.getByRole("radio", { name: /^Selected projects/u }));
		await screen.findByText("We could not load the group’s projects");
		screen.getByText(detail, { exact: false });
		// No quiet fallback to every project: the choice stays, and nothing can be created yet.
		expect(
			screen.getByRole("radio", { name: /^Selected projects/u }).getAttribute("aria-checked"),
		).toBe("true");
		expect(createButton().disabled).toBe(true);

		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("checkbox", { name: "API" });
		expect(requests.create).toStrictEqual([]);
	});

	it("says when no project matches a search, and still waits for a chosen project", async () => {
		const user = userEvent.setup();
		serveGitLab();
		renderRouteAt("/workspaces/new/gitlab");
		await configure(user);

		await user.click(screen.getByRole("radio", { name: /^Selected projects/u }));
		await screen.findByRole("checkbox", { name: "API" });
		await user.type(screen.getByRole("searchbox", { name: "Search projects" }), "zzz");
		await user.click(screen.getByRole("button", { name: "Search" }));

		await screen.findByText("No matching projects on this page.");
		expect(createButton().disabled).toBe(true);
	});
});
