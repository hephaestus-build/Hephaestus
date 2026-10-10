import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import type {
	ActivityPeople,
	ActivityWork,
	ActivityWorkPage,
	OpenWork,
	WorkItem,
} from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { currentUser } from "@/mocks/fixtures/auth";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { clearUserView } from "@/runtime/user-view/session";
import { deferred } from "@/test/async";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";
import { storeUserView } from "@/test/user-view";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 40_000 });

// The end of a list asks for the next page as it scrolls into view, which jsdom cannot report.
beforeEach(() => {
	vi.stubGlobal("IntersectionObserver", ObserverStub);
});

const counts = {
	contributions: 5,
	pullRequestsOpened: 2,
	pullRequestsMerged: 1,
	pullRequestsReviewed: 3,
	peopleHelped: 2,
	issuesOpened: 0,
	comments: 4,
	activeWeeks: 1,
};
const people = {
	from: "2026-09-01T00:00:00Z",
	to: "2026-10-01T00:00:00Z",
	// The server lists people by id, not by name: a tie must still read in name order.
	people: ["bob", "ada-lrz"].map((login) => ({
		person: {
			id: login === "bob" ? 8 : 7,
			login,
			name: login === "bob" ? "Bob" : "Ada",
			email: "",
			avatarUrl: "",
			htmlUrl: "",
		},
		kind: "PERSON",
		counts: { ...counts, pullRequestsReviewed: login === "bob" ? 9 : 3 },
		weeks: [{ start: "2026-09-21T00:00:00Z", contributions: counts.contributions }],
	})),
	automation: [],
	coverage: { completeRepositories: 1, totalRepositories: 1 },
	highlights: { firstContributors: [], mostPeopleHelped: [] },
	repositories: [
		{ id: 1, key: "acme/api", name: "api" },
		{ id: 2, key: "acme/web", name: "web" },
	],
	teams: [
		{ id: 5, key: "core", name: "Core" },
		{ id: 8, key: "backend", name: "Backend", parentId: 5 },
	],
} satisfies Wire<ActivityPeople>;

const personDetail = {
	from: people.from,
	to: people.to,
	person: people.people[1]?.person,
	kind: "PERSON",
	counts,
	breakdown: {
		pullRequestsClosed: 0,
		approvals: 2,
		changeRequests: 1,
		commentReviews: 0,
		discussionComments: 4,
		codeComments: 0,
		issuesClosed: 0,
	},
	weeks: [
		{
			start: "2026-09-21T00:00:00Z",
			counts,
			breakdown: {
				pullRequestsClosed: 0,
				approvals: 2,
				changeRequests: 1,
				commentReviews: 0,
				discussionComments: 4,
				codeComments: 0,
				issuesClosed: 0,
			},
		},
	],
	repositories: [],
};

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
	return http.get(/\/workspaces\/[^/]+\/activity\/(?:people\/\d+\/)?work/u, ({ request }) => {
		record(request);
		const cursor = new URL(request.url).searchParams.get("cursor") ?? "first";
		return HttpResponse.json(pages[cursor]);
	});
}

const ownPullRequest = {
	id: 3101,
	type: "PULL_REQUEST",
	number: 42,
	title: "Retry webhook delivery",
	state: "OPEN",
	isDraft: false,
	// Approved, so it needs Ada and is not folded away with what waits on others.
	reviewDecision: "APPROVED",
	author: { id: 7, login: "ada-lrz", name: "Ada", avatarUrl: "", htmlUrl: "" },
} satisfies WorkItem;
const ownIssue = {
	id: 3102,
	type: "ISSUE",
	number: 43,
	title: "Document the restore drill",
	state: "OPEN",
	isDraft: false,
} satisfies WorkItem;
const reviewRequested = {
	...ownPullRequest,
	reviewDecision: undefined,
	id: 3103,
	number: 44,
	title: "Bob's pull request",
	author: { id: 8, login: "bob", name: "Bob", avatarUrl: "", htmlUrl: "" },
} satisfies WorkItem;
/** Ada's own pull request and assigned issue, and one of Bob's she was asked to review. */
const ownWork = {
	reviewRequests: { content: [reviewRequested], hasMore: false },
	teamReviewRequests: nothing,
	pullRequests: { content: [ownPullRequest], hasMore: false },
	issues: { content: [ownIssue], hasMore: false },
} satisfies OpenWork;

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
			http.get("*/workspaces/:workspaceSlug/activity/people", ({ request }) => {
				record(request);
				return HttpResponse.json(people);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/people/:userId", ({ request }) => {
				record(request);
				return HttpResponse.json(personDetail);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/work", ({ request }) => {
				record(request);
				return HttpResponse.json(workPage);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/people/:userId/work", ({ request }) => {
				record(request);
				return HttpResponse.json(workPage);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/members/:login/open-work", ({ request }) => {
				record(request);
				return HttpResponse.json(openWork);
			}),
		);
	});

	const readsOf = (path: string) => reads.filter((url) => url.pathname.endsWith(path));

	it("reads your Activity for the workspace identity", async () => {
		renderRouteAtWithRouter("/w/acme/activity");

		await screen.findByRole("heading", { name: "Needs you" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(readsOf("/activity/people/7").length).toBeGreaterThan(0));
		expect(readsOf("/activity/people/7").every((url) => !url.searchParams.has("login"))).toBe(true);
		expect(readsOf("/activity/people")).toStrictEqual([]);
		expect(readsOf("/activity/people/7").every((url) => !url.searchParams.has("membersOnly"))).toBe(
			true,
		);
		expect(readsOf("/open-work").map((url) => url.pathname)).toContain(
			"/workspaces/acme/activity/members/ada-lrz/open-work",
		);
		expect(reads.some((url) => url.searchParams.get("login") === "ada")).toBe(false);
	});

	describe("Request review", () => {
		let asked: unknown[] = [];
		beforeEach(() => {
			asked = [];
			server.use(
				http.get("*/workspaces/:workspaceSlug/activity/members/:login/open-work", () =>
					HttpResponse.json(ownWork),
				),
				http.post("*/workspaces/:workspaceSlug/practices/review-requests", async ({ request }) => {
					asked.push(await request.json());
					return HttpResponse.json({ status: "SUBMITTED", jobId: "job-1" });
				}),
			);
		});
		afterEach(clearUserView);

		it("asks for a review of your own work by its kind and id, and of nobody else's", async () => {
			const user = userEvent.setup();
			renderRouteAtWithRouter("/w/acme/activity");

			await user.click(
				await screen.findByRole("button", { name: "Request review: #42" }, ROUTE_RENDER_WAIT),
			);
			await user.click(screen.getByRole("button", { name: "Request review: #43" }));

			await waitFor(() =>
				expect(asked).toStrictEqual([
					{ artifactKind: "scm.pull_request", artifactId: 3101 },
					{ artifactKind: "scm.issue", artifactId: 3102 },
				]),
			);
			expect(screen.queryByRole("button", { name: "Request review: #44" })).toBeNull();
		});

		it("says why a review asked for from open work was refused", async () => {
			server.use(
				http.post("*/workspaces/:workspaceSlug/practices/review-requests", () =>
					HttpResponse.json({
						status: "REFUSED",
						reason: "REQUEST_COOLDOWN_ACTIVE",
						reasonDescription: "A review of this was already asked for a moment ago.",
					}),
				),
			);
			const user = userEvent.setup();
			renderRouteAtWithRouter("/w/acme/activity");

			await user.click(
				await screen.findByRole("button", { name: "Request review: #42" }, ROUTE_RENDER_WAIT),
			);

			await screen.findByText("No review was started", undefined, ROUTE_RENDER_WAIT);
			await screen.findByText("A review of this was already asked for a moment ago.");
		});

		/** A refusal answered after a second ask was sent is still said: every answer is its own. */
		it("says why an earlier ask was refused after a later one was sent", async () => {
			// The first ask is answered only once the test says so; the second at once.
			const firstAnswer = deferred();
			const answers = [
				async () => {
					await firstAnswer.promise;
					return {
						status: "REFUSED",
						reason: "REQUEST_COOLDOWN_ACTIVE",
						reasonDescription: "A review of this was already asked for a moment ago.",
					};
				},
				async () => ({ status: "SUBMITTED", jobId: "job-2" }),
			];
			server.use(
				http.post("*/workspaces/:workspaceSlug/practices/review-requests", async ({ request }) => {
					asked.push(await request.json());
					const answer = answers.shift();
					assert(answer, "Only two reviews are asked for");
					return HttpResponse.json(await answer());
				}),
			);
			const user = userEvent.setup();
			renderRouteAtWithRouter("/w/acme/activity");

			await user.click(
				await screen.findByRole("button", { name: "Request review: #42" }, ROUTE_RENDER_WAIT),
			);
			await user.click(screen.getByRole("button", { name: "Request review: #43" }));
			await waitFor(() => expect(asked).toHaveLength(2));
			await screen.findByText("Review started");
			firstAnswer.resolve();

			await screen.findByText("No review was started", undefined, ROUTE_RENDER_WAIT);
			await screen.findByText("A review of this was already asked for a moment ago.");
		});

		it("offers no review where the workspace reviews no practices", async () => {
			server.use(
				http.get("*/workspaces", () =>
					HttpResponse.json([workspaceListItem("acme", { practicesEnabled: false })]),
				),
			);
			renderRouteAtWithRouter("/w/acme/activity");

			await screen.findByText("Retry webhook delivery", undefined, ROUTE_RENDER_WAIT);
			expect(screen.queryByRole("button", { name: /^Request review/u })).toBeNull();
		});

		it("offers no review in a user view, which never spends the member's budget", async () => {
			storeUserView({ workspaceSlug: "acme" });
			renderRouteAtWithRouter("/w/acme/activity");

			await screen.findByText("Retry webhook delivery", undefined, ROUTE_RENDER_WAIT);
			// The view is on, so the absence below is the view's doing.
			screen.getByText(/read-only/u);
			expect(screen.queryByRole("button", { name: /^Request review/u })).toBeNull();
		});
	});

	it("pages your timeline by the server's cursor and asks the server for the period", async () => {
		server.use(pagedWork(record));
		const user = userEvent.setup();
		renderRouteAtWithRouter("/w/acme/activity");

		await user.click(
			await screen.findByRole("button", { name: "Show earlier activity" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() =>
			expect(
				readsOf("/activity/people/7/work").map((url) => url.searchParams.get("cursor")),
			).toContain("page-2"),
		);
		// A preset is the server's to resolve, so no read names a start or an end of its own.
		for (const url of readsOf("/activity/people/7/work")) {
			expect(url.searchParams.get("range")).toBe("90d");
			expect(url.searchParams.has("from")).toBe(false);
		}
		// The one read with days of its own is the period before, which ends where this one begins.
		const earlier = readsOf("/activity/people/7").filter((url) => url.searchParams.has("to"));
		expect(earlier.map((url) => url.searchParams.get("to"))).toStrictEqual([
			"2026-09-01T00:00:00.000Z",
		]);
	});

	it("reads a custom range from local midnight of its first day to the night after its last", async () => {
		renderRouteAtWithRouter("/w/acme/activity?from=2026-03-01&to=2026-03-31");

		await waitFor(
			() => expect(readsOf("/activity/people/7").length).toBeGreaterThan(0),
			ROUTE_RENDER_WAIT,
		);
		const [first] = readsOf("/activity/people/7");
		assert(first);
		expect(first.searchParams.get("range")).toBe("custom");
		expect(new Date(String(first.searchParams.get("from")))).toStrictEqual(new Date(2026, 2, 1));
		expect(new Date(String(first.searchParams.get("to")))).toStrictEqual(new Date(2026, 3, 1));
	});

	it("lists only a category's kinds when its level is open", async () => {
		renderRouteAtWithRouter("/w/acme/activity?detail=activity:reviews");

		await waitFor(
			() =>
				expect(
					readsOf("/activity/people/7/work").some(
						(url) => url.searchParams.getAll("kinds").length > 0,
					),
				).toBe(true),
			ROUTE_RENDER_WAIT,
		);
		const filtered = readsOf("/activity/people/7/work").find(
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
			readsOf("/activity/people/7/work")
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
		expect(readsOf("/activity/people/7")).toStrictEqual([]);
	});

	it("reads nobody's activity when the membership cannot be read, and says so", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ status: 500 }, { status: 500 }),
			),
		);
		renderRouteAtWithRouter("/w/acme/activity");

		await screen.findByText(
			"We could not load your membership in this workspace",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(reads.filter((url) => url.pathname.includes("/activity/"))).toStrictEqual([]);
	});

	it("opens one category level per owner, stopping at a second", async () => {
		renderRouteAtWithRouter("/w/acme/activity?detail=activity:reviews&detail=activity:issues");

		await waitFor(
			() =>
				expect(
					readsOf("/activity/people/7/work").some(
						(url) => url.searchParams.getAll("kinds").length > 0,
					),
				).toBe(true),
			ROUTE_RENDER_WAIT,
		);
		await expect(screen.findAllByRole("dialog")).resolves.toHaveLength(1);
		expect(
			readsOf("/activity/people/7/work").flatMap((url) => url.searchParams.getAll("kinds")),
		).not.toContain("ISSUE_OPENED");
	});

	it("opens no level for a category it does not know", async () => {
		renderRouteAtWithRouter("/w/acme/activity?detail=activity:bogus");

		await screen.findByRole("heading", { name: "Needs you" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("dialog")).toBeNull();
	});
});

/** Each row's position and name, in the order the table shows them. */
function positions(): (string | null | undefined)[][] {
	return within(screen.getByRole("table", { name: "People" }))
		.getAllByRole("row")
		.slice(1)
		.map((row) => [
			within(row).getAllByRole("cell")[0]?.textContent,
			within(row).getByRole("link").textContent,
		]);
}

/** The people at once for the first read; every later read waits until `held` settles. */
function firstAtOnce(held: Promise<void>) {
	let answered = false;
	return async () => {
		if (answered) {
			await held;
		}
		answered = true;
		return HttpResponse.json(people);
	};
}

/** The people for every scope but one that names a repository, which the server does not know. */
function unknownRepository(request: Request) {
	return new URL(request.url).searchParams.has("repo")
		? HttpResponse.json({ title: "Not Found", status: 404 }, { status: 404 })
		: HttpResponse.json(people);
}

describe("Workspace activity", () => {
	let reads: URL[] = [];
	let automation: URL[] = [];
	let visibility: URL[] = [];
	let role = "MEMBER";
	const record = (request: Request) => {
		reads.push(new URL(request.url));
	};
	const readsOf = (path: string) => reads.filter((url) => url.pathname.endsWith(path));
	beforeEach(() => {
		reads = [];
		automation = [];
		visibility = [];
		role = "MEMBER";
		server.use(
			http.get("*/user", () => HttpResponse.json({ ...currentUser, username: "ada" })),
			http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
			http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspaceListItem("acme"))),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role, userId: 7, userLogin: "ada-lrz", userName: "Ada" }),
			),
			// Ada is the only member; Bob contributes without a membership.
			http.get("*/workspaces/:workspaceSlug/users", () =>
				HttpResponse.json([{ id: 7, login: "ada-lrz", name: "Ada", teams: [], url: "" }]),
			),
			http.get("*/workspaces/:workspaceSlug/activity/people", ({ request }) => {
				record(request);
				return HttpResponse.json(people);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/people/:userId", ({ request }) => {
				record(request);
				return HttpResponse.json(personDetail);
			}),
			http.get(/\/workspaces\/[^/]+\/activity\/(?:people\/\d+\/)?work/u, ({ request }) => {
				record(request);
				return HttpResponse.json(workPage);
			}),
			http.patch(
				"*/workspaces/:workspaceSlug/activity/people/:userId/public-visibility",
				({ request }) => {
					visibility.push(new URL(request.url));
					return new HttpResponse(null, { status: 204 });
				},
			),
			http.patch(
				"*/workspaces/:workspaceSlug/activity/people/:userId/automation",
				({ request }) => {
					automation.push(new URL(request.url));
					return new HttpResponse(null, { status: 204 });
				},
			),
		);
	});

	it("reads everyone in the last 90 days and keeps the defaults out of the address", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity");

		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(readsOf("/activity/people").length).toBeGreaterThan(0));
		const [first] = readsOf("/activity/people");
		expect(first?.search).toBe("?range=90d");
		expect(router.state.location.href).toBe("/w/acme/workspace-activity");
	});

	it("reads the scope a readable address names, by slug and path", async () => {
		const address =
			"/w/acme/workspace-activity?range=1y&team=core&repo=acme/api&repo=acme/web&sort=reviews&dir=asc";
		const { router } = renderRouteAtWithRouter(address);

		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(readsOf("/activity/people").length).toBeGreaterThan(0));
		const [first] = readsOf("/activity/people");
		expect(first?.searchParams.get("range")).toBe("1y");
		expect(first?.searchParams.get("team")).toBe("core");
		expect(first?.searchParams.getAll("repo")).toStrictEqual(["acme/api", "acme/web"]);
		expect(router.state.location.href).toBe(address);
		expect(screen.getByRole("columnheader", { name: /^Reviews/u }).getAttribute("aria-sort")).toBe(
			"ascending",
		);
	});

	it("falls back to the defaults for values it does not know", async () => {
		renderRouteAtWithRouter(
			"/w/acme/workspace-activity?range=5y&sort=score&dir=up&from=2026-13-01",
		);

		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(readsOf("/activity/people").length).toBeGreaterThan(0));
		expect(readsOf("/activity/people")[0]?.search).toBe("?range=90d");
		expect(
			screen.getByRole("columnheader", { name: /^Contributions/u }).getAttribute("aria-sort"),
		).toBe("descending");
	});

	it("writes a sort to the address and drops it again at the default", async () => {
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity");

		await user.click(await screen.findByRole("button", { name: /^Reviews/u }, ROUTE_RENDER_WAIT));
		await waitFor(() => expect(router.state.location.searchStr).toBe("?sort=reviews"));
		// Sorted by Reviews, Bob comes first, at position 1.
		expect(positions()).toStrictEqual([
			["1", "Bob"],
			["2", "Ada"],
		]);
		await user.click(screen.getByRole("button", { name: /^Contributions/u }));
		await waitFor(() => expect(router.state.location.href).toBe("/w/acme/workspace-activity"));
		// The same count is the same position, and a tie stays in name order.
		expect(positions()).toStrictEqual([
			["1", "Ada"],
			["1", "Bob"],
		]);
	});

	it("flips a sort to the fewest first, with the positions running backwards", async () => {
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity");

		await user.click(await screen.findByRole("button", { name: /^Reviews/u }, ROUTE_RENDER_WAIT));
		await user.click(screen.getByRole("button", { name: /^Reviews/u }));

		await waitFor(() => expect(router.state.location.searchStr).toBe("?sort=reviews&dir=asc"));
		expect(positions()).toStrictEqual([
			["2", "Ada"],
			["1", "Bob"],
		]);
	});

	it("writes each repository as its own key, and Reset clears them", async () => {
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity");

		await user.click(
			await screen.findByRole("combobox", { name: "Repository" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("option", { name: "acme/api" }));
		await user.click(await screen.findByRole("option", { name: "acme/web" }));

		await waitFor(() =>
			expect(router.state.location.searchStr).toBe("?repo=acme/api&repo=acme/web"),
		);
		await waitFor(() =>
			expect(readsOf("/activity/people").at(-1)?.searchParams.getAll("repo")).toStrictEqual([
				"acme/api",
				"acme/web",
			]),
		);
		await user.keyboard("{Escape}");
		await user.click(screen.getByRole("button", { name: "Reset" }));
		await waitFor(() => expect(router.state.location.href).toBe("/w/acme/workspace-activity"));
	});

	it("keeps a repository the server does not know clearable", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/activity/people", ({ request }) =>
				unknownRepository(request),
			),
		);
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity?repo=acme/old");

		await screen.findByRole("alert", undefined, ROUTE_RENDER_WAIT);
		await user.click(screen.getByRole("button", { name: "Reset" }));

		await waitFor(() => expect(router.state.location.href).toBe("/w/acme/workspace-activity"));
		await screen.findByRole("table", { name: "People" });
	});

	it("writes a preset over a custom range and drops the custom days", async () => {
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?from=2026-03-01&to=2026-03-31",
		);
		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
		expect(readsOf("/activity/people")[0]?.searchParams.get("range")).toBe("custom");

		await user.click(screen.getByRole("button", { name: "12 months" }));

		await waitFor(() =>
			expect(router.state.location.href).toBe("/w/acme/workspace-activity?range=1y"),
		);
	});

	it.each([
		["a last day after today", "from=2026-01-01&to=2999-12-31"],
		["a first day before any history", "from=1970-01-01&to=2026-01-31"],
	])("drops a custom range with %s", async (_case, days) => {
		renderRouteAtWithRouter(`/w/acme/workspace-activity?${days}`);

		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
		expect(readsOf("/activity/people")[0]?.search).toBe("?range=90d");
	});

	it("keeps the pickers' options while another team's people load", async () => {
		const held = deferred();
		server.use(http.get("*/workspaces/:workspaceSlug/activity/people", firstAtOnce(held.promise)));
		const user = userEvent.setup();
		renderRouteAtWithRouter("/w/acme/workspace-activity");

		await user.click(
			await screen.findByRole("combobox", { name: "Team: Everyone" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("option", { name: "Core" }));
		await waitFor(() =>
			expect(screen.getByRole("table", { name: "People" }).getAttribute("aria-busy")).toBe("true"),
		);

		screen.getByRole("combobox", { name: "Team: Core" });
		await user.click(screen.getByRole("combobox", { name: "Repository" }));
		const options = await screen.findAllByRole("option");
		expect(options.map((option) => option.textContent)).toStrictEqual(["acme/api", "acme/web"]);
		held.resolve();
	});

	it("lists none of everyone's work as a person's before the people say who they are", async () => {
		const held = deferred();
		server.use(
			http.get("*/workspaces/:workspaceSlug/activity/people", async () => {
				await held.promise;
				return HttpResponse.json(people);
			}),
			http.get(/\/workspaces\/[^/]+\/activity\/(?:people\/\d+\/)?work/u, () =>
				HttpResponse.json({ content: [workItem(1)] }),
			),
		);
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");

		const level = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		// The page's own timeline has the work; the level, whose person is not known yet, does not.
		await waitFor(() => expect(screen.getAllByText("Pull request 1")).toHaveLength(1));
		expect(within(level).queryByText("Pull request 1")).toBeNull();
		expect(within(level).queryByRole("button", { name: /Copy as Markdown/u })).toBeNull();
		held.resolve();
	});

	it("writes a team by its slug, never its id", async () => {
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity");

		await user.click(
			await screen.findByRole("combobox", { name: "Team: Everyone" }, ROUTE_RENDER_WAIT),
		);
		const options = await screen.findAllByRole("option");
		expect(options.map((option) => option.textContent)).toStrictEqual([
			"Everyone",
			"Core",
			"Core / Backend",
		]);
		await user.click(screen.getByRole("option", { name: "Core / Backend" }));
		await waitFor(() =>
			expect(router.state.location.href).toBe("/w/acme/workspace-activity?team=backend"),
		);
		await waitFor(() =>
			expect(readsOf("/activity/people").map((url) => url.searchParams.get("team"))).toContain(
				"backend",
			),
		);
	});

	it("carries the period, and only the period, to your own Activity", async () => {
		const { router } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?from=2026-03-01&to=2026-03-31&team=core",
		);
		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);

		await router.navigate({ to: "/w/$workspaceSlug/activity", params: { workspaceSlug: "acme" } });

		expect(router.state.location.href).toBe("/w/acme/activity?from=2026-03-01&to=2026-03-31");
	});

	it("reads a person opened by login in the page's scope", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?team=core&detail=person:bob");

		await waitFor(
			() => expect(readsOf("/activity/people/8").length).toBeGreaterThan(0),
			ROUTE_RENDER_WAIT,
		);
		expect(readsOf("/activity/people/8")[0]?.searchParams.get("team")).toBe("core");
		expect(readsOf("/activity/people/8/work")[0]?.searchParams.get("team")).toBe("core");
	});

	it("reads a category stacked over a person as that person's", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob&detail=activity:reviews");

		await waitFor(
			() =>
				expect(
					readsOf("/activity/people/8/work").map((url) =>
						url.searchParams.getAll("kinds").join(","),
					),
				).toContain("REVIEW_APPROVED,REVIEW_CHANGES_REQUESTED,REVIEW_COMMENTED"),
			ROUTE_RENDER_WAIT,
		);
	});

	it("opens no category without a person to own it", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=activity:reviews");

		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("dialog")).toBeNull();
	});

	it("says a person opened by address did nothing in the period", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:carol");

		const level = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await within(level).findByText("No activity in this range");
		expect(reads.filter((url) => /\/people\/\d+/u.test(url.pathname))).toStrictEqual([]);
	});

	it("says a category of a person who did nothing has nothing, rather than loading", async () => {
		renderRouteAtWithRouter(
			"/w/acme/workspace-activity?detail=person:carol&detail=activity:reviews",
		);

		const level = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await within(level).findByRole("heading", { name: "Reviews" });
		await within(level).findByText("No activity in this range");
	});

	it("lets an admin treat a person as automation, and nobody else", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");

		const action = await screen.findByRole(
			"button",
			{ name: "Treat as automation" },
			ROUTE_RENDER_WAIT,
		);
		const readsBefore = readsOf("/activity/people").length;
		await user.click(action);

		await waitFor(() =>
			expect(automation.map((url) => `${url.pathname}${url.search}`)).toStrictEqual([
				"/workspaces/acme/activity/people/8/automation?treatAsAutomation=true",
			]),
		);
		// The people are read again, so the account moves between the lists at once.
		await waitFor(() => expect(readsOf("/activity/people").length).toBeGreaterThan(readsBefore));
	});

	it("lets an admin hide a person from the activity pages, and take it back", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");

		await user.click(
			await screen.findByRole("button", { name: "Hide from activity" }, ROUTE_RENDER_WAIT),
		);
		const confirm = await screen.findByRole("alertdialog");
		expect(visibility).toStrictEqual([]);
		await user.click(within(confirm).getByRole("button", { name: "Hide" }));

		await waitFor(() =>
			expect(visibility.map((url) => `${url.pathname}${url.search}`)).toStrictEqual([
				"/workspaces/acme/activity/people/8/public-visibility?hidden=true",
			]),
		);
		// The person has left the list, so the level over it closes.
		await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
		await user.click(await screen.findByRole("button", { name: "Undo" }));
		await waitFor(() =>
			expect(visibility.map((url) => url.search)).toStrictEqual(["?hidden=true", "?hidden=false"]),
		);
	});

	it("leaves a member to be hidden under Members, not from their activity page", async () => {
		role = "ADMIN";
		server.use(
			http.get("*/workspaces/:workspaceSlug/users", () =>
				HttpResponse.json([{ id: 8, login: "bob", name: "Bob", teams: [], url: "" }]),
			),
		);
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");

		const level = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await within(level).findByRole("button", { name: "Treat as automation" });
		expect(within(level).queryByRole("button", { name: "Hide from activity" })).toBeNull();
	});

	it("offers a member no way to hide a person", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");

		const level = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await within(level).findByRole("heading", { name: "Bob" });
		expect(within(level).queryByRole("button", { name: "Hide from activity" })).toBeNull();
	});

	it("lets an admin count an account treated as automation as a person again", async () => {
		role = "ADMIN";
		const [bob, ada] = people.people;
		server.use(
			http.get("*/workspaces/:workspaceSlug/activity/people", () =>
				HttpResponse.json({
					...people,
					people: [ada],
					automation: [{ ...bob, kind: "AUTOMATION" }],
				}),
			),
		);
		const user = userEvent.setup();
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");

		await user.click(
			await screen.findByRole("button", { name: "Count as a person" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() =>
			expect(automation.map((url) => `${url.pathname}${url.search}`)).toStrictEqual([
				"/workspaces/acme/activity/people/8/automation?treatAsAutomation=false",
			]),
		);
	});

	it("offers no action for a provider's bot account", async () => {
		role = "ADMIN";
		const [bob, ada] = people.people;
		server.use(
			http.get("*/workspaces/:workspaceSlug/activity/people", () =>
				HttpResponse.json({ ...people, people: [ada], automation: [{ ...bob, kind: "BOT" }] }),
			),
		);
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");

		const level = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await within(level).findByRole("heading", { name: "Bob" });
		expect(within(level).queryByRole("button", { name: /automation|as a person/u })).toBeNull();
	});

	it("offers a member no automation action", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");

		const level = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await within(level).findByRole("heading", { name: "Bob" });
		expect(within(level).queryByRole("button", { name: "Treat as automation" })).toBeNull();
	});

	it("sends an old profile address to the person on Workspace activity", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/user/bob");

		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/workspace-activity"),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.href).toBe("/w/acme/workspace-activity?detail=person:bob");
	});

	it("sends someone else's old practice group page to them on Workspace activity", async () => {
		const { router } = renderRouteAtWithRouter(
			"/w/acme/user/bob/practice-groups/review-ready-work",
		);

		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/workspace-activity"),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.href).toBe("/w/acme/workspace-activity?detail=person:bob");
	});

	it("sends an old practice group page to its level on the practice profile", async () => {
		const { router } = renderRouteAtWithRouter(
			"/w/acme/user/ada-lrz/practice-groups/review-ready-work",
		);

		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/practice-profile"),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.href).toBe(
			"/w/acme/practice-profile?detail=practice-group:review-ready-work",
		);
	});
});
