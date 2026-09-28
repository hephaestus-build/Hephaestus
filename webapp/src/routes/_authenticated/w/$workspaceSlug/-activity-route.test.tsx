import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, beforeEach, describe, expect, it, vi } from "vitest";

import type {
	ActivityOverview,
	ActivitySummary,
	ActivityWork,
	ActivityWorkPage,
	OpenWork,
	TeamInfo,
} from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { currentUser } from "@/mocks/fixtures/auth";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 40_000 });

const summary = {
	pullRequestsOpened: 2,
	pullRequestsMerged: 1,
	pullRequestsClosed: 0,
	approvals: 2,
	changeRequests: 1,
	commentReviews: 0,
	comments: 4,
	codeComments: 0,
	issuesOpened: 0,
	issuesClosed: 0,
} satisfies ActivitySummary;

/** One bucket holding the whole summary, as a one-day range would read. */
const overview = {
	summary,
	bucket: "DAY",
	buckets: [{ start: "2026-09-27T00:00:00Z", summary }],
} satisfies Wire<ActivityOverview>;

const nothing = { content: [], hasMore: false };
const openWork = {
	reviewRequests: nothing,
	teamReviewRequests: nothing,
	pullRequests: nothing,
	issues: nothing,
} satisfies OpenWork;
const workPage = { content: [] } satisfies ActivityWorkPage;

function workItem(id: number): Wire<ActivityWork> {
	return {
		id: `work:${id}`,
		work: {
			id,
			type: "PULL_REQUEST",
			number: id,
			title: `Pull request ${id}`,
			state: "MERGED",
			isDraft: false,
			htmlUrl: `https://github.com/acme/app/pull/${id}`,
		},
		actions: [{ kind: "PULL_REQUEST_MERGED", count: 1 }],
		lastOccurredAt: "2026-09-27T09:00:00Z",
		people: [],
	};
}

/** Four pieces of work over three pages: one on the first, two on the next, one on the last. */
function pagedWork(record: (request: Request) => void) {
	const pages: Record<string, Wire<ActivityWorkPage>> = {
		first: { content: [workItem(1)], nextCursor: "page-2" },
		"page-2": { content: [workItem(2), workItem(3)], nextCursor: "page-3" },
		"page-3": { content: [workItem(4)] },
	};
	return http.get("*/workspaces/:workspaceSlug/activity/work", ({ request }) => {
		record(request);
		const cursor = new URL(request.url).searchParams.get("cursor") ?? "first";
		return HttpResponse.json(pages[cursor]);
	});
}

function team(
	id: number,
	name: string,
	options: { hidden?: boolean; parentId?: number } = {},
): TeamInfo {
	return {
		id,
		name,
		hidden: options.hidden ?? false,
		parentId: options.parentId,
		labels: [],
		members: [],
		membershipCount: 0,
		repositories: [],
		repoPermissionCount: 0,
	};
}

/** Signed in as `ada`, while the workspace's connected instance knows the same account as `ada-lrz`. */
describe("Activity", () => {
	let reads: URL[] = [];
	const record = (request: Request) => {
		reads.push(new URL(request.url));
	};

	beforeEach(() => {
		reads = [];
		server.use(
			http.get("*/user", () => HttpResponse.json({ ...currentUser, username: "ada" })),
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
			),
			http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspaceListItem("acme"))),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "MEMBER", userId: 7, userLogin: "ada-lrz", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/activity/summary", ({ request }) => {
				record(request);
				return HttpResponse.json(overview);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/work", ({ request }) => {
				record(request);
				return HttpResponse.json(workPage);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/members", ({ request }) => {
				record(request);
				return HttpResponse.json([]);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/members/:login/open-work", ({ request }) => {
				record(request);
				return HttpResponse.json(openWork);
			}),
			http.get("*/workspaces/:workspaceSlug/team", () =>
				HttpResponse.json([
					team(5, "Platform"),
					team(8, "Backend", { parentId: 5 }),
					team(6, "Secret", { hidden: true }),
					team(7, "Payments", { parentId: 6 }),
				]),
			),
		);
	});

	const readsOf = (path: string) => reads.filter((url) => url.pathname.endsWith(path));

	it("reads your Activity for the workspace identity", async () => {
		renderRouteAtWithRouter("/w/acme/activity");

		await screen.findByRole("heading", { name: "Needs you" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(readsOf("/activity/summary").length).toBeGreaterThan(0));
		expect(readsOf("/activity/summary").map((url) => url.searchParams.get("login"))).toContain(
			"ada-lrz",
		);
		expect(readsOf("/open-work").map((url) => url.pathname)).toContain(
			"/workspaces/acme/activity/members/ada-lrz/open-work",
		);
		expect(reads.some((url) => url.searchParams.get("login") === "ada")).toBe(false);
	});

	it("reads the summary in the browser's time zone", async () => {
		renderRouteAtWithRouter("/w/acme/activity");

		await waitFor(
			() => expect(readsOf("/activity/summary").length).toBeGreaterThan(0),
			ROUTE_RENDER_WAIT,
		);
		expect(readsOf("/activity/summary")[0]?.searchParams.get("zone")).toBe(
			Intl.DateTimeFormat().resolvedOptions().timeZone,
		);
	});

	it("pages the timeline by the server's cursor and sends no end of its own", async () => {
		server.use(pagedWork(record));
		const user = userEvent.setup();
		renderRouteAtWithRouter("/w/acme/activity");

		await user.click(await screen.findByRole("button", { name: "Show more" }, ROUTE_RENDER_WAIT));

		await waitFor(() =>
			expect(readsOf("/activity/work").map((url) => url.searchParams.get("cursor"))).toContain(
				"page-2",
			),
		);
		// The range runs to whenever the server reads it; the cursor carries that end between pages.
		expect(readsOf("/activity/work").map((url) => url.searchParams.has("to"))).not.toContain(true);
		const [first, next] = readsOf("/activity/work");
		const from = first?.searchParams.get("from");
		expect(next?.searchParams.get("from")).toBe(from);
		// The one summary read with an end is the period before, which ends where the range begins.
		const ended = readsOf("/activity/summary").filter((url) => url.searchParams.has("to"));
		expect(ended.map((url) => url.searchParams.get("to"))).toContain(from);
		expect(ended.map((url) => url.searchParams.get("from"))).not.toContain(from);
	});

	it("reads the summary from local midnight of the range's first day", async () => {
		renderRouteAtWithRouter("/w/acme/activity?range=30d");

		await waitFor(
			() => expect(readsOf("/activity/summary").length).toBeGreaterThan(0),
			ROUTE_RENDER_WAIT,
		);
		const [first] = readsOf("/activity/summary");
		assert(first);
		const from = new Date(String(first.searchParams.get("from")));
		expect(from.getHours()).toBe(0);
		expect(from.getMinutes()).toBe(0);
		// oxlint-disable-next-line eslint/no-restricted-properties -- the test compares the request against the real clock the route read
		const days = Math.round((Date.now() - from.getTime()) / 86_400_000);
		expect(days).toBeGreaterThanOrEqual(29);
		expect(days).toBeLessThanOrEqual(30);
	});

	it("lists only a category's kinds when its level is open", async () => {
		renderRouteAtWithRouter("/w/acme/activity?detail=activity:reviews");

		await waitFor(
			() =>
				expect(
					readsOf("/activity/work").some((url) => url.searchParams.getAll("kinds").length > 0),
				).toBe(true),
			ROUTE_RENDER_WAIT,
		);
		const filtered = readsOf("/activity/work").find(
			(url) => url.searchParams.getAll("kinds").length > 0,
		);
		expect(filtered?.searchParams.getAll("kinds")).toStrictEqual([
			"REVIEW_APPROVED",
			"REVIEW_CHANGES_REQUESTED",
			"REVIEW_COMMENTED",
		]);
	});

	it("copies every page of the timeline, reading the rest at the largest page size", async () => {
		server.use(pagedWork(record));
		const user = userEvent.setup();
		renderRouteAtWithRouter("/w/acme/activity");

		await user.click(
			await screen.findByRole("button", { name: "Copy as Markdown" }, ROUTE_RENDER_WAIT),
		);

		await screen.findByText("Copied 4 items as Markdown", undefined, ROUTE_RENDER_WAIT);
		expect(
			readsOf("/activity/work")
				.filter((url) => url.searchParams.get("size") === "100")
				.map((url) => url.searchParams.get("cursor")),
		).toStrictEqual(["page-2", "page-3"]);
		const copied = await navigator.clipboard.readText();
		expect(copied.split("\n").slice(2)).toStrictEqual(
			[1, 2, 3, 4].map(
				(id) => `- [Pull request ${id}](https://github.com/acme/app/pull/${id}) · #${id} · merged`,
			),
		);
	});

	it("reads a team's activity", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?team=5");

		await screen.findByRole("heading", { name: "Members" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(readsOf("/activity/members").length).toBeGreaterThan(0));
		expect(readsOf("/activity/members")[0]?.searchParams.get("teamId")).toBe("5");
		expect(readsOf("/activity/summary")[0]?.searchParams.get("teamId")).toBe("5");
	});

	it("offers only teams that are not hidden, naming a sub-team by its path through visible teams", async () => {
		const user = userEvent.setup();
		renderRouteAtWithRouter("/w/acme/workspace-activity");

		await user.click(await screen.findByRole("combobox", { name: "Team" }, ROUTE_RENDER_WAIT));
		const options = await screen.findAllByRole("option");
		expect(options.map((option) => option.textContent)).toStrictEqual([
			"Everyone",
			"Payments",
			"Platform",
			"Platform / Backend",
		]);
	});

	it("tells a member with no connected account in the workspace how to connect one", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "MEMBER", userId: 7, userName: "Ada" }),
			),
		);
		renderRouteAtWithRouter("/w/acme/activity");

		await screen.findByRole("heading", { name: "No connected account" }, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("link", { name: "Connect an account" }).getAttribute("href")).toBe(
			"/settings#linked-accounts-heading",
		);
		expect(readsOf("/activity/summary")).toStrictEqual([]);
	});

	it("reads nobody's activity when the membership cannot be read, and says so", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ status: 500 }, { status: 500 }),
			),
		);
		renderRouteAtWithRouter("/w/acme/activity");

		await screen.findByText(
			"Couldn't load your membership in this workspace",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(reads.some((url) => url.searchParams.get("login") === "ada")).toBe(false);
		expect(readsOf("/open-work")).toStrictEqual([]);
	});

	it("drops a team the page does not offer and never reads its activity", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity?team=6");

		await waitFor(
			() => expect(router.state.location.search).not.toHaveProperty("team"),
			ROUTE_RENDER_WAIT,
		);
		await waitFor(() => expect(readsOf("/activity/summary").length).toBeGreaterThan(0));
		expect(reads.some((url) => url.searchParams.get("teamId") === "6")).toBe(false);
	});

	it("opens one category level per owner, stopping at a second", async () => {
		renderRouteAtWithRouter("/w/acme/activity?detail=activity:reviews&detail=activity:issues");

		await waitFor(
			() =>
				expect(
					readsOf("/activity/work").some((url) => url.searchParams.getAll("kinds").length > 0),
				).toBe(true),
			ROUTE_RENDER_WAIT,
		);
		await expect(screen.findAllByRole("dialog")).resolves.toHaveLength(1);
		expect(
			readsOf("/activity/work").flatMap((url) => url.searchParams.getAll("kinds")),
		).not.toContain("ISSUE_OPENED");
	});

	it("reads a category stacked over a member as that member's", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=member:bob&detail=activity:reviews");

		await waitFor(
			() =>
				expect(
					readsOf("/activity/work").map((url) => [
						url.searchParams.get("login"),
						url.searchParams.getAll("kinds").join(","),
					]),
				).toContainEqual(["bob", "REVIEW_APPROVED,REVIEW_CHANGES_REQUESTED,REVIEW_COMMENTED"]),
			ROUTE_RENDER_WAIT,
		);
		const categoryReads = readsOf("/activity/work").filter((url) => url.searchParams.has("kinds"));
		expect(categoryReads.map((url) => url.searchParams.get("login"))).not.toContain(null);
	});

	it("reads a category under a member as the page's, with the member opened over it", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=activity:reviews&detail=member:bob");

		await waitFor(
			() =>
				expect(
					readsOf("/activity/work").map((url) => [
						url.searchParams.get("login"),
						url.searchParams.getAll("kinds").join(","),
					]),
				).toContainEqual([null, "REVIEW_APPROVED,REVIEW_CHANGES_REQUESTED,REVIEW_COMMENTED"]),
			ROUTE_RENDER_WAIT,
		);
		await waitFor(() =>
			expect(readsOf("/activity/summary").map((url) => url.searchParams.get("login"))).toContain(
				"bob",
			),
		);
	});

	it("opens no level for a category it does not know", async () => {
		renderRouteAtWithRouter("/w/acme/activity?detail=activity:bogus");

		await screen.findByRole("heading", { name: "Needs you" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("dialog")).toBeNull();
	});

	it("reads a member's own activity in their level", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=member:bob");

		await waitFor(
			() =>
				expect(readsOf("/open-work").map((url) => url.pathname)).toContain(
					"/workspaces/acme/activity/members/bob/open-work",
				),
			ROUTE_RENDER_WAIT,
		);
		expect(readsOf("/activity/summary").map((url) => url.searchParams.get("login"))).toContain(
			"bob",
		);
	});

	it("reads a member opened under a team in that team's scope", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?team=5&detail=member:bob");

		await waitFor(
			() =>
				expect(
					readsOf("/activity/summary").map((url) => [
						url.searchParams.get("login"),
						url.searchParams.get("teamId"),
					]),
				).toContainEqual(["bob", "5"]),
			ROUTE_RENDER_WAIT,
		);
	});

	it("sends an old profile address to the member on Workspace activity", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/user/bob");

		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/workspace-activity"),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.search).toMatchObject({ detail: ["member:bob"] });
	});

	it("sends someone else's old practice group page to them on Workspace activity", async () => {
		const { router } = renderRouteAtWithRouter(
			"/w/acme/user/bob/practice-groups/review-ready-work",
		);

		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/workspace-activity"),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.search).toMatchObject({ detail: ["member:bob"] });
	});

	it("sends an old practice group page to its level on the practice profile", async () => {
		const { router } = renderRouteAtWithRouter(
			"/w/acme/user/ada-lrz/practice-groups/review-ready-work",
		);

		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/practice-profile"),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.search).toMatchObject({
			detail: ["practice-group:review-ready-work"],
		});
	});
});
