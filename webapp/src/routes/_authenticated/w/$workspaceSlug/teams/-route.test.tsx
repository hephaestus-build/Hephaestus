import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, describe, expect, it, vi } from "vitest";

import { getAllTeamsQueryKey } from "@/api/@tanstack/react-query.gen";
import type { TeamInfo } from "@/api/types.gen";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ObserverStub } from "@/test/observers";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

beforeAll(() => loadRouteCode("/w/acme/teams"), ROUTE_RENDER_WAIT.timeout);

const FAILED = "We could not load teams";
const NONE = /No teams yet/u;

function team(id: number, name: string, memberName: string): TeamInfo {
	return {
		id,
		name,
		description: "",
		htmlUrl: `https://github.com/orgs/example/teams/team-${id}`,
		organization: "example",
		privacy: "SECRET",
		membershipCount: 1,
		repoPermissionCount: 0,
		slug: `team-${id}`,
		parentId: undefined,
		repositories: [],
		labels: [],
		members: [
			{
				id,
				login: `member-${id}`,
				name: memberName,
				avatarUrl: "",
				htmlUrl: `https://github.com/member-${id}`,
			},
		],
		hidden: false,
	};
}

/** A member of two workspaces, so the page can move from one to the other. */
function member() {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("globex", { id: 2 })]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("MEMBER")),
		),
	);
	vi.stubGlobal("IntersectionObserver", ObserverStub);
}

const REFRESH_FAILED = "We could not refresh teams";

/** Acme's teams, read once, then read again through their generated key with `answer`. */
async function refreshWith(answer: () => Response) {
	member();
	let refreshing = false;
	server.use(
		http.get("*/workspaces/:workspaceSlug/teams", () =>
			refreshing ? answer() : HttpResponse.json([team(1, "Frontend", "Jane Doe")]),
		),
	);
	const { queryClient } = renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { level: 2, name: "Frontend" }, ROUTE_RENDER_WAIT);
	refreshing = true;
	await act(async () =>
		queryClient.invalidateQueries({
			queryKey: getAllTeamsQueryKey({ path: { workspaceSlug: "acme" } }),
			exact: true,
		}),
	);
	return {
		succeed: () => {
			refreshing = false;
		},
	};
}

describe("a refresh of the teams page", () => {
	it("keeps the teams on screen when it fails, until a retry reads them", async () => {
		const user = userEvent.setup();
		const { succeed } = await refreshWith(() => new HttpResponse(null, { status: 503 }));

		await screen.findByText(REFRESH_FAILED);
		screen.getByRole("heading", { level: 2, name: "Frontend" });
		screen.getByText("Jane Doe");
		succeed();
		const retryResponse = deferred<Response>();
		server.use(http.get("*/workspaces/acme/teams", async () => retryResponse.promise));
		await user.click(screen.getByRole("button", { name: "Retry" }));
		const retrying = await screen.findByRole("button", { name: "Retrying…" });
		expect(retrying.hasAttribute("disabled")).toBe(true);
		screen.getByRole("heading", { level: 2, name: "Frontend" });
		screen.getByText("Jane Doe");
		retryResponse.resolve(HttpResponse.json([team(1, "Frontend", "Jane Doe")]));
		await waitFor(() => expect(screen.queryByText(REFRESH_FAILED)).toBeNull());
		screen.getByRole("heading", { level: 2, name: "Frontend" });
	});

	it("never restores refused teams when the reset's read then fails with 503", async () => {
		member();
		let reads = 0;
		server.use(
			http.get(
				"*/workspaces/acme/teams",
				() => {
					reads += 1;
					return HttpResponse.json([team(1, "Frontend", "Jane Doe")]);
				},
				{ once: true },
			),
			http.get(
				"*/workspaces/acme/teams",
				() => {
					reads += 1;
					return HttpResponse.json({ type: "about:blank", status: 403 }, { status: 403 });
				},
				{ once: true },
			),
			http.get("*/workspaces/acme/teams", () => {
				reads += 1;
				return HttpResponse.json({ type: "about:blank", status: 503 }, { status: 503 });
			}),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/teams");
		await screen.findByRole("heading", { level: 2, name: "Frontend" }, ROUTE_RENDER_WAIT);
		await act(async () =>
			queryClient.invalidateQueries({
				queryKey: getAllTeamsQueryKey({ path: { workspaceSlug: "acme" } }),
				exact: true,
			}),
		);
		await screen.findByText(FAILED);
		expect(reads).toBe(3);
		expect(screen.queryByRole("heading", { level: 2, name: "Frontend" })).toBeNull();
		expect(screen.queryByText("Jane Doe")).toBeNull();
		expect(screen.queryByText(REFRESH_FAILED)).toBeNull();
		expect(screen.queryByText(NONE)).toBeNull();
	});

	it.each([401, 403, 404])(
		"hides the teams when it is refused with %i, and offers no retry",
		async (status) => {
			await refreshWith(() =>
				HttpResponse.json(
					{ type: "about:blank", title: "Refused", status },
					{ status, headers: { "Content-Type": "application/problem+json" } },
				),
			);

			await waitFor(() =>
				expect(screen.queryByRole("heading", { level: 2, name: "Frontend" })).toBeNull(),
			);
			expect(screen.queryByText("Jane Doe")).toBeNull();
			expect(screen.queryByText(REFRESH_FAILED)).toBeNull();
			expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
		},
	);
});

describe("the teams page", () => {
	it("offers a retry when the teams cannot be read, and never says there are none", async () => {
		const user = userEvent.setup();
		member();
		server.use(
			http.get("*/workspaces/:workspaceSlug/teams", () =>
				HttpResponse.json([team(1, "Frontend", "Jane Doe")]),
			),
		);
		// Added last, so it answers first: the first read fails once and then reads.
		server.use(
			http.get("*/workspaces/:workspaceSlug/teams", () => new HttpResponse(null, { status: 503 }), {
				once: true,
			}),
		);
		renderRouteAtWithRouter("/w/acme/teams");

		await screen.findByText(FAILED, undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText(NONE)).toBeNull();
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("heading", { level: 2, name: "Frontend" });
		expect(screen.queryByText(FAILED)).toBeNull();
	});

	it("says there are no teams when the workspace has none", async () => {
		member();
		server.use(http.get("*/workspaces/:workspaceSlug/teams", () => HttpResponse.json([])));
		renderRouteAtWithRouter("/w/acme/teams");

		await screen.findByText(NONE, undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByText(FAILED)).toBeNull();
	});

	it("shows neither the last workspace's teams nor none when the next workspace's read fails", async () => {
		const user = userEvent.setup();
		member();
		server.use(
			http.get("*/workspaces/acme/teams", () =>
				HttpResponse.json([team(1, "Frontend", "Jane Doe")]),
			),
			http.get("*/workspaces/globex/teams", () => new HttpResponse(null, { status: 503 }), {
				once: true,
			}),
			http.get("*/workspaces/globex/teams", () =>
				HttpResponse.json([team(2, "Platform", "Bob Smith")]),
			),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/teams");
		await screen.findByRole("heading", { level: 2, name: "Frontend" }, ROUTE_RENDER_WAIT);

		await act(async () => router.history.push("/w/globex/teams"));
		await screen.findByText(FAILED, undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("heading", { name: "Frontend" })).toBeNull();
		expect(screen.queryByText("Jane Doe")).toBeNull();
		expect(screen.queryByText(NONE)).toBeNull();

		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("heading", { level: 2, name: "Platform" });
		expect(screen.queryByText(FAILED)).toBeNull();
	});
});
