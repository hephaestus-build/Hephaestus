import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, beforeEach, describe, expect, it, vi } from "vitest";

import {
	getActivityFacetsQueryKey,
	getActivityPeopleQueryKey,
	getActivityPersonWorkInfiniteQueryKey,
	getActivityWorkInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type {
	ActivityPeople,
	ActivityWork,
	ActivityWorkPage,
	OpenWork,
	ReviewContext,
	ReviewRequestOutcome,
	WorkItem,
	WorkspaceMembership,
} from "@/api/types.gen";
import { ITEM_EVENT_REASON_SENTENCES } from "@/components/practice-vocabulary/item-event-defs";
import type { Wire } from "@/lib/dates";
import { currentUser } from "@/mocks/fixtures/auth";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { QUERY_STALE_TIME_MS } from "@/runtime/tanstack-query/query-defaults";
import { deferred } from "@/test/async";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter, testQueryClient } from "@/test/router-harness";
import { storeUserView } from "@/test/user-view";
import { switchWorkspace } from "@/test/workspace-switcher";

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

function servePeople(answers: readonly (Wire<ActivityPeople> | { status: number })[]) {
	const served: { path: string; status: number }[] = [];
	server.use(
		http.get("*/workspaces/:workspaceSlug/activity/people", ({ request }) => {
			const answer = answers[served.length];
			assert(answer, "People read exceeded the prepared responses");
			const status = "status" in answer ? answer.status : 200;
			served.push({ path: new URL(request.url).pathname, status });
			return HttpResponse.json(answer, { status });
		}),
	);
	return served;
}

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
	return http.get(
		/\/workspaces\/[^/]+\/activity\/(?:(?:people\/\d+|me)\/)?work/u,
		({ request }) => {
			record(request);
			const cursor = new URL(request.url).searchParams.get("cursor") ?? "first";
			return HttpResponse.json(pages[cursor]);
		},
	);
}

function adaMembership(
	role: WorkspaceMembership["role"],
	identities: WorkspaceMembership["account"]["identities"] = [
		{ provider: "GITHUB", login: "ada-lrz", displayName: "Ada", avatarUrl: null },
	],
): Wire<WorkspaceMembership> {
	return {
		account: { id: "70", status: "ACTIVE", displayName: "Ada", avatarUrl: null, identities },
		role,
		joinedAt: "2026-01-01T00:00:00Z",
		leftAt: null,
		hidden: false,
		reviewSelected: true,
		eligibleForPracticeReview: true,
	};
}

/** Your own pull requests waiting for review are folded away until you open them. */
async function openWaitingWork(user: ReturnType<typeof userEvent.setup>) {
	await user.click(
		await screen.findByRole("button", { name: /^Waiting on others/u }, ROUTE_RENDER_WAIT),
	);
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
				HttpResponse.json(adaMembership("MEMBER")),
			),
			http.get("*/workspaces/:workspaceSlug/activity/people", ({ request }) => {
				record(request);
				return HttpResponse.json(people);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/me", ({ request }) => {
				record(request);
				return HttpResponse.json(personDetail);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/work", ({ request }) => {
				record(request);
				return HttpResponse.json(workPage);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/me/work", ({ request }) => {
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
		await waitFor(() => expect(readsOf("/activity/me").length).toBeGreaterThan(0));
		expect(readsOf("/activity/me").every((url) => !url.searchParams.has("login"))).toBe(true);
		expect(readsOf("/activity/people")).toStrictEqual([]);
		expect(reads.filter((url) => url.pathname.includes("/activity/people/"))).toStrictEqual([]);
		expect(readsOf("/activity/me").every((url) => !url.searchParams.has("membersOnly"))).toBe(true);
		expect(readsOf("/open-work").map((url) => url.pathname)).toContain(
			"/workspaces/acme/activity/members/ada-lrz/open-work",
		);
		expect(reads.some((url) => url.searchParams.get("login") === "ada")).toBe(false);
	});

	it("pages your timeline by the server's cursor and asks the server for the period", async () => {
		server.use(pagedWork(record));
		const user = userEvent.setup();
		renderRouteAtWithRouter("/w/acme/activity");

		await user.click(
			await screen.findByRole("button", { name: "Show earlier activity" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() =>
			expect(readsOf("/activity/me/work").map((url) => url.searchParams.get("cursor"))).toContain(
				"page-2",
			),
		);
		// A preset is the server's to resolve, so no read names a start or an end of its own.
		for (const url of readsOf("/activity/me/work")) {
			expect(url.searchParams.get("range")).toBe("90d");
			expect(url.searchParams.has("from")).toBe(false);
		}
		// The one read with days of its own is the period before, which ends where this one begins.
		const earlier = readsOf("/activity/me").filter((url) => url.searchParams.has("to"));
		expect(earlier.map((url) => url.searchParams.get("to"))).toStrictEqual([
			"2026-09-01T00:00:00.000Z",
		]);
	});

	it("reads a custom range from local midnight of its first day to the night after its last", async () => {
		renderRouteAtWithRouter("/w/acme/activity?from=2026-03-01&to=2026-03-31");

		await waitFor(
			() => expect(readsOf("/activity/me").length).toBeGreaterThan(0),
			ROUTE_RENDER_WAIT,
		);
		const [first] = readsOf("/activity/me");
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
					readsOf("/activity/me/work").some((url) => url.searchParams.getAll("kinds").length > 0),
				).toBe(true),
			ROUTE_RENDER_WAIT,
		);
		const filtered = readsOf("/activity/me/work").find(
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
			readsOf("/activity/me/work")
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
				HttpResponse.json(
					adaMembership("MEMBER", [
						{ provider: "SLACK", login: null, displayName: "Ada", avatarUrl: null },
					]),
				),
			),
		);
		renderRouteAtWithRouter("/w/acme/activity");

		await screen.findByRole("heading", { name: "No connected account" }, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("link", { name: "Connect an account" }).getAttribute("href")).toBe(
			"/settings#linked-accounts-heading",
		);
		expect(readsOf("/activity/me")).toStrictEqual([]);
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
					readsOf("/activity/me/work").some((url) => url.searchParams.getAll("kinds").length > 0),
				).toBe(true),
			ROUTE_RENDER_WAIT,
		);
		await expect(screen.findAllByRole("dialog")).resolves.toHaveLength(1);
		expect(
			readsOf("/activity/me/work").flatMap((url) => url.searchParams.getAll("kinds")),
		).not.toContain("ISSUE_OPENED");
	});

	it("opens no level for a category it does not know", async () => {
		renderRouteAtWithRouter("/w/acme/activity?detail=activity:bogus");

		await screen.findByRole("heading", { name: "Needs you" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("dialog")).toBeNull();
	});

	describe("Request review", () => {
		// The activity row's id is the provider work's; the review asks for the practice item, which
		// only the review context knows. Different on purpose, so a row id sent as the item fails.
		const ownPullRequest = {
			id: 27_001,
			type: "PULL_REQUEST",
			number: 27,
			title: "Pull request 27",
			state: "OPEN",
			isDraft: false,
			htmlUrl: "https://github.com/acme/app/pull/27",
			author: { id: 7, login: "ada-lrz", name: "Ada", avatarUrl: "", htmlUrl: "" },
		} satisfies Wire<WorkItem>;
		const assignedIssue = {
			id: 31_001,
			type: "ISSUE",
			number: 31,
			title: "Issue 31",
			state: "OPEN",
			isDraft: false,
			htmlUrl: "https://github.com/acme/app/issues/31",
			author: { id: 8, login: "bob", name: "Bob", avatarUrl: "", htmlUrl: "" },
		} satisfies Wire<WorkItem>;
		const someoneElsesPullRequest = {
			id: 45_001,
			type: "PULL_REQUEST",
			number: 45,
			title: "Pull request 45",
			state: "OPEN",
			isDraft: false,
			htmlUrl: "https://github.com/acme/app/pull/45",
			author: { id: 8, login: "bob", name: "Bob", avatarUrl: "", htmlUrl: "" },
		} satisfies Wire<WorkItem>;
		const ownOpenWork = {
			reviewRequests: { content: [someoneElsesPullRequest], hasMore: false },
			teamReviewRequests: nothing,
			pullRequests: { content: [ownPullRequest], hasMore: false },
			issues: { content: [assignedIssue], hasMore: false },
		} satisfies Wire<OpenWork>;
		const ITEM_OF_URL: Record<string, string> = {
			[ownPullRequest.htmlUrl]: "item-pull-request-27",
			[assignedIssue.htmlUrl]: "item-issue-31",
		};

		function reviewContext(url: string, requestAllowed = true): Wire<ReviewContext> {
			return {
				canInspectReviewDetails: true,
				item: {
					id: ITEM_OF_URL[url] ?? "item-unknown",
					kind: "PULL_REQUEST",
					label: "#27",
					container: "acme/app",
					parentId: null,
					path: null,
					provider: "GITHUB",
					removedAt: null,
					title: null,
					url,
				},
				reasonDescription: requestAllowed ? null : "Reviews of this work are paused.",
				requestAllowed,
				workspaceId: "1",
				workspaceSlug: "acme",
			};
		}

		/**
		 * The page's own work, every review-context read by the address it names, and every review asked
		 * for by its body. A held step waits for its promise; the answers run in order.
		 */
		function serveReviewRequests({
			context = (url: string) => HttpResponse.json(reviewContext(url)),
			heldContext = Promise.resolve(),
			heldAsk = Promise.resolve(),
			answers = [{ status: "SUBMITTED", reason: null, reviewId: "review-77" }],
		}: {
			context?: (url: string) => Response;
			heldContext?: Promise<void>;
			heldAsk?: Promise<void>;
			answers?: readonly (ReviewRequestOutcome | { problem: number })[];
		} = {}) {
			const contexts: (string | null)[] = [];
			const asks: unknown[] = [];
			/** Every lookup and ask as `METHOD path`, in the order they reached the server. */
			const requests: string[] = [];
			server.use(
				http.get("*/workspaces/:workspaceSlug/activity/members/:login/open-work", () =>
					HttpResponse.json(ownOpenWork),
				),
				http.get("*/workspaces/:workspaceSlug/practices/review-context", async ({ request }) => {
					const url = new URL(request.url).searchParams.get("url");
					requests.push(`GET ${new URL(request.url).pathname}`);
					contexts.push(url);
					await heldContext;
					return context(String(url));
				}),
				http.post("*/workspaces/:workspaceSlug/practices/review-requests", async ({ request }) => {
					const body: unknown = await request.json();
					requests.push(`POST ${new URL(request.url).pathname}`);
					asks.push(body);
					await heldAsk;
					const answer = answers[asks.length - 1];
					assert(answer, "Review request exceeded the prepared answers");
					return "problem" in answer
						? HttpResponse.json(
								{ status: answer.problem, title: "Unavailable", detail: "Reviews are paused." },
								{ status: answer.problem },
							)
						: HttpResponse.json(answer);
				}),
			);
			return { contexts, asks, requests };
		}

		it.each([
			{
				work: "your own pull request",
				reference: "#27",
				other: "#31",
				url: ownPullRequest.htmlUrl,
				itemId: "item-pull-request-27",
			},
			{
				work: "an issue assigned to you",
				reference: "#31",
				other: "#27",
				url: assignedIssue.htmlUrl,
				itemId: "item-issue-31",
			},
		])(
			"asks a review of $work for its practice item, keeping only that row busy until the review exists",
			async ({ reference, other, url, itemId }) => {
				const resolved = deferred();
				const asked = deferred();
				const { contexts, asks } = serveReviewRequests({
					heldContext: resolved.promise,
					heldAsk: asked.promise,
				});
				const user = userEvent.setup();
				renderRouteAtWithRouter("/w/acme/activity");
				await openWaitingWork(user);
				try {
					await user.click(
						await screen.findByRole(
							"button",
							{ name: `Request review: ${reference}` },
							ROUTE_RENDER_WAIT,
						),
					);

					// Busy while the work is looked up, and still while the review is asked for.
					await waitFor(() => expect(contexts).toStrictEqual([url]));
					expect(
						screen.getByRole<HTMLButtonElement>("button", {
							name: `Requesting review…: ${reference}`,
						}).disabled,
					).toBe(true);
					expect(
						screen.getByRole<HTMLButtonElement>("button", { name: `Request review: ${other}` })
							.disabled,
					).toBe(false);
					resolved.resolve();
					await waitFor(() => expect(asks).toStrictEqual([{ itemId }]));
					expect(
						screen.getByRole<HTMLButtonElement>("button", {
							name: `Requesting review…: ${reference}`,
						}).disabled,
					).toBe(true);
				} finally {
					resolved.resolve();
					asked.resolve();
				}

				const note = await screen.findByRole("note");
				const link = within(note).getByRole("link", { name: "View requested review" });
				const target = new URL(String(link.getAttribute("href")), "http://localhost");
				expect({
					path: target.pathname,
					detail: target.searchParams.getAll("detail"),
				}).toStrictEqual({ path: "/w/acme/practice-profile", detail: ["review:review-77"] });
				// Accepted is not started: the new review's own level says whether it is queued or running.
				expect(note.textContent).not.toMatch(/started|running|queued/iu);
				expect(asks).toStrictEqual([{ itemId }]);
			},
		);

		it("follows the requested review to your practice profile, and Back returns without asking again", async () => {
			const { asks } = serveReviewRequests();
			const user = userEvent.setup();
			const { router } = renderRouteAtWithRouter("/w/acme/activity");
			await openWaitingWork(user);

			await user.click(
				await screen.findByRole("button", { name: "Request review: #27" }, ROUTE_RENDER_WAIT),
			);
			const note = await screen.findByRole("note");
			await user.click(within(note).getByRole("link", { name: "View requested review" }));
			await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/practice-profile"));
			expect(new URLSearchParams(router.state.location.searchStr).getAll("detail")).toStrictEqual([
				"review:review-77",
			]);

			await act(async () => {
				router.history.back();
			});
			await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/activity"));
			await screen.findByRole("heading", { name: "Needs you" }, ROUTE_RENDER_WAIT);
			expect(asks).toStrictEqual([{ itemId: "item-pull-request-27" }]);
		});

		it("keeps a review asked in one workspace out of the next workspace's Activity", async () => {
			// Beta's own work differs from acme's in row id, number, title, address and practice item.
			const betaIssue = {
				id: 52_001,
				type: "ISSUE",
				number: 52,
				title: "Issue 52",
				state: "OPEN",
				isDraft: false,
				htmlUrl: "https://github.com/beta/web/issues/52",
				author: { id: 8, login: "bob", name: "Bob", avatarUrl: "", htmlUrl: "" },
			} satisfies Wire<WorkItem>;
			const betaContext = {
				canInspectReviewDetails: true,
				item: {
					id: "item-issue-52",
					kind: "ISSUE",
					label: "#52",
					container: "beta/web",
					parentId: null,
					path: null,
					provider: "GITHUB",
					removedAt: null,
					title: null,
					url: betaIssue.htmlUrl,
				},
				reasonDescription: null,
				requestAllowed: true,
				workspaceId: "2",
				workspaceSlug: "beta",
			} satisfies Wire<ReviewContext>;
			const acmeLookup = deferred();
			const betaAnswer = deferred();
			const { asks, requests } = serveReviewRequests({ heldContext: acmeLookup.promise });
			const betaAsks: unknown[] = [];
			server.use(
				http.get("*/workspaces", () =>
					HttpResponse.json([
						workspaceListItem("acme", { practicesEnabled: true }),
						workspaceListItem("beta", { practicesEnabled: true }),
					]),
				),
				http.get("*/workspaces/beta", () => HttpResponse.json(workspaceListItem("beta"))),
				http.get("*/workspaces/beta/activity/members/:login/open-work", () =>
					HttpResponse.json({
						reviewRequests: nothing,
						teamReviewRequests: nothing,
						pullRequests: nothing,
						issues: { content: [betaIssue], hasMore: false },
					} satisfies Wire<OpenWork>),
				),
				http.get("*/workspaces/beta/practices/review-context", ({ request }) => {
					requests.push(`GET ${new URL(request.url).pathname}`);
					return HttpResponse.json(betaContext);
				}),
				http.post("*/workspaces/beta/practices/review-requests", async ({ request }) => {
					const body: unknown = await request.json();
					requests.push(`POST ${new URL(request.url).pathname}`);
					betaAsks.push(body);
					await betaAnswer.promise;
					return HttpResponse.json({
						status: "SUBMITTED",
						reason: null,
						reviewId: "review-88",
					} satisfies ReviewRequestOutcome);
				}),
			);
			const user = userEvent.setup();
			const { router } = renderRouteAtWithRouter("/w/acme/activity");
			await openWaitingWork(user);
			try {
				await user.click(
					await screen.findByRole("button", { name: "Request review: #27" }, ROUTE_RENDER_WAIT),
				);
				await waitFor(() =>
					expect(requests).toStrictEqual(["GET /workspaces/acme/practices/review-context"]),
				);

				// Acme's lookup is still out when the reader moves to beta's Activity.
				await act(async () => {
					await router.navigate({
						to: "/w/$workspaceSlug/activity",
						params: { workspaceSlug: "beta" },
					});
				});
				const betaRow = await screen.findByRole<HTMLButtonElement>(
					"button",
					{ name: "Request review: #52" },
					ROUTE_RENDER_WAIT,
				);
				expect({
					usable: !betaRow.disabled,
					busy: screen.queryAllByRole("button", { name: /^Requesting review…/u }).length,
					acmeWork: screen.queryByRole("link", { name: /^Pull request 27/u }),
				}).toStrictEqual({ usable: true, busy: 0, acmeWork: null });

				// Beta's own ask is out too when acme's lookup answers.
				await user.click(betaRow);
				await waitFor(() => expect(betaAsks).toStrictEqual([{ itemId: "item-issue-52" }]));
				await act(async () => {
					acmeLookup.resolve();
				});
				await waitFor(() => expect(asks).toStrictEqual([{ itemId: "item-pull-request-27" }]));
				// Acme's ask is answered; its acceptance is acme's, never a note on beta's page.
				await waitFor(() =>
					expect(screen.getAllByText("Review requested").length).toBeGreaterThan(0),
				);
				expect({
					note: screen.queryByRole("note"),
					betaBusy: screen.getByRole<HTMLButtonElement>("button", {
						name: "Requesting review…: #52",
					}).disabled,
					requests,
				}).toStrictEqual({
					note: null,
					betaBusy: true,
					requests: [
						"GET /workspaces/acme/practices/review-context",
						"GET /workspaces/beta/practices/review-context",
						"POST /workspaces/beta/practices/review-requests",
						"POST /workspaces/acme/practices/review-requests",
					],
				});

				await act(async () => {
					betaAnswer.resolve();
				});
				const note = await screen.findByRole("note");
				const link = within(note).getByRole("link", { name: "View requested review" });
				const target = new URL(String(link.getAttribute("href")), "http://localhost");
				expect({
					path: target.pathname,
					detail: target.searchParams.getAll("detail"),
					asks,
					betaAsks,
				}).toStrictEqual({
					path: "/w/beta/practice-profile",
					detail: ["review:review-88"],
					asks: [{ itemId: "item-pull-request-27" }],
					betaAsks: [{ itemId: "item-issue-52" }],
				});
			} finally {
				acmeLookup.resolve();
				betaAnswer.resolve();
			}
		});

		it("offers no review of someone else's pull request that asks for your review", async () => {
			serveReviewRequests();
			const user = userEvent.setup();
			renderRouteAtWithRouter("/w/acme/activity");
			await openWaitingWork(user);

			await screen.findByRole("button", { name: "Request review: #27" }, ROUTE_RENDER_WAIT);
			screen.getByRole("link", { name: /^Pull request 45/u });
			expect(screen.queryByRole("button", { name: /^Request review: #45/u })).toBeNull();
		});

		it("says why a review was declined, and asks again on the next press", async () => {
			const { asks } = serveReviewRequests({
				answers: [
					{ status: "REFUSED", reason: "COOLDOWN_ACTIVE", reviewId: null },
					{ status: "SUBMITTED", reason: null, reviewId: "review-78" },
				],
			});
			const user = userEvent.setup();
			renderRouteAtWithRouter("/w/acme/activity");
			await openWaitingWork(user);

			await user.click(
				await screen.findByRole("button", { name: "Request review: #27" }, ROUTE_RENDER_WAIT),
			);
			await screen.findByText(ITEM_EVENT_REASON_SENTENCES.COOLDOWN_ACTIVE);
			expect(screen.queryByRole("note")).toBeNull();

			await user.click(screen.getByRole("button", { name: "Request review: #27" }));
			await screen.findByRole("note");
			expect(asks).toStrictEqual([
				{ itemId: "item-pull-request-27" },
				{ itemId: "item-pull-request-27" },
			]);
		});

		it("says a review request failed, and asks again on the next press", async () => {
			const { asks } = serveReviewRequests({
				answers: [{ problem: 503 }, { status: "SUBMITTED", reason: null, reviewId: "review-79" }],
			});
			const user = userEvent.setup();
			renderRouteAtWithRouter("/w/acme/activity");
			await openWaitingWork(user);

			await user.click(
				await screen.findByRole("button", { name: "Request review: #27" }, ROUTE_RENDER_WAIT),
			);
			await screen.findByText("Reviews are paused.");

			await user.click(screen.getByRole("button", { name: "Request review: #27" }));
			await screen.findByRole("note");
			expect(asks).toStrictEqual([
				{ itemId: "item-pull-request-27" },
				{ itemId: "item-pull-request-27" },
			]);
		});

		it.each([
			{
				answer: "does not allow a request",
				context: (url: string) => HttpResponse.json(reviewContext(url, false)),
				shown: "Reviews of this work are paused.",
			},
			{
				answer: "finds no reviewed work at the address",
				context: () =>
					HttpResponse.json(
						{
							status: 404,
							title: "Not Found",
							detail: "This workspace has no reviewed work at this address.",
						},
						{ status: 404 },
					),
				shown: "This workspace has no reviewed work at this address.",
			},
			{
				answer: "names no practice item",
				context: (url: string) => HttpResponse.json({ ...reviewContext(url), item: null }),
				shown: "You cannot request a review of this work.",
			},
		])(
			"asks for no review when the work's review context $answer, and says why",
			async ({ context, shown }) => {
				const { contexts, asks } = serveReviewRequests({ context });
				const user = userEvent.setup();
				renderRouteAtWithRouter("/w/acme/activity");
				await openWaitingWork(user);

				await user.click(
					await screen.findByRole("button", { name: "Request review: #27" }, ROUTE_RENDER_WAIT),
				);
				await screen.findByText(shown);
				await waitFor(() =>
					expect(
						screen.getByRole<HTMLButtonElement>("button", { name: "Request review: #27" }).disabled,
					).toBe(false),
				);
				expect({ contexts, asks, note: screen.queryByRole("note") }).toStrictEqual({
					contexts: [ownPullRequest.htmlUrl],
					asks: [],
					note: null,
				});
			},
		);

		it.each([
			{
				when: "an administrator views the page as the developer",
				arrange: () => storeUserView({ workspaceSlug: "acme" }),
			},
			{
				when: "the workspace reviews no practices",
				arrange: () =>
					server.use(
						http.get("*/workspaces", () =>
							HttpResponse.json([workspaceListItem("acme", { practicesEnabled: false })]),
						),
					),
			},
		])("offers no review request when $when", async ({ arrange }) => {
			const { contexts, asks } = serveReviewRequests();
			arrange();
			const user = userEvent.setup();
			renderRouteAtWithRouter("/w/acme/activity");
			await openWaitingWork(user);

			await screen.findByRole("link", { name: /^Pull request 27/u }, ROUTE_RENDER_WAIT);
			screen.getByRole("link", { name: /^Issue 31/u });
			expect({
				requests: screen.queryAllByRole("button", { name: /^Request review/u }).length,
				contexts,
				asks,
			}).toStrictEqual({ requests: 0, contexts: [], asks: [] });
		});
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

type WorkAnswer = { status: 200; page: Wire<ActivityWorkPage> } | { status: 403 | 503 };

const earlierWork: WorkAnswer = { status: 200, page: { content: [workItem(101)] } };
const laterWork: WorkAnswer = { status: 200, page: { content: [workItem(202)] } };

/** Each answer once, in order, and the last for every read after it; `served` records each status. */
function workAnswers(path: string, served: number[], answers: readonly WorkAnswer[]) {
	return answers.map((answer, index) =>
		http.get(
			path,
			() => {
				served.push(answer.status);
				return answer.status === 200
					? HttpResponse.json(answer.page)
					: HttpResponse.json(
							{ status: answer.status, title: "Not now" },
							{ status: answer.status },
						);
			},
			{ once: index < answers.length - 1 },
		),
	);
}

/** Both timelines one hook reads: everyone's on the page, and one person's on their level. */
const TIMELINES = [
	[
		"everyone's timeline",
		"/w/acme/workspace-activity?view=timeline",
		"*/workspaces/acme/activity/work",
		() =>
			getActivityWorkInfiniteQueryKey({
				path: { workspaceSlug: "acme" },
				query: { range: "90d", size: 30 },
			}),
		async () => document.body,
	],
	[
		"a person's timeline",
		"/w/acme/workspace-activity?detail=person:bob",
		"*/workspaces/acme/activity/people/8/work",
		() =>
			getActivityPersonWorkInfiniteQueryKey({
				path: { workspaceSlug: "acme", userId: 8 },
				query: { range: "90d", size: 30 },
			}),
		async () => screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT),
	],
] as const;

/** Confirms Hide on the person level in front, and waits for the confirmation to close. */
async function confirmHide(user: ReturnType<typeof userEvent.setup>) {
	await user.click(
		await screen.findByRole("button", { name: "Hide from activity" }, ROUTE_RENDER_WAIT),
	);
	await user.click(
		within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Hide" }),
	);
	await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
}

/** Closes the person level in front and opens Ada from the people table, as an admin would. */
async function openAdaInstead(
	user: ReturnType<typeof userEvent.setup>,
	router: ReturnType<typeof renderRouteAtWithRouter>["router"],
) {
	await user.keyboard("{Escape}");
	await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
	await user.click(
		within(screen.getByRole("table", { name: "People" })).getByRole("link", { name: "Ada" }),
	);
	await screen.findByRole("dialog", { name: "Ada" }, ROUTE_RENDER_WAIT);
	expect(router.state.location.search.detail).toBe("person:ada-lrz");
}

/** The team picker's options, read with the picker open and closed again after. */
async function teamOptions(user: ReturnType<typeof userEvent.setup>) {
	await user.click(screen.getByRole("combobox", { name: /^Team: /u }));
	const options = await screen.findAllByRole("option");
	const names = options.map((option) => option.textContent);
	await user.keyboard("{Escape}");
	await waitFor(() => expect(screen.queryByRole("option")).toBeNull());
	return names;
}

describe("Workspace activity", () => {
	let reads: URL[] = [];
	let automation: URL[] = [];
	let visibility: URL[] = [];
	let role: WorkspaceMembership["role"] = "MEMBER";
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
				HttpResponse.json(adaMembership(role)),
			),
			// Ada is the only member; Bob contributes without a membership.
			http.get("*/workspaces/:workspaceSlug/users", () =>
				HttpResponse.json([{ id: 7, login: "ada-lrz", name: "Ada", teams: [], url: "" }]),
			),
			http.get("*/workspaces/:workspaceSlug/activity/people", ({ request }) => {
				record(request);
				return HttpResponse.json(people);
			}),
			http.get("*/workspaces/:workspaceSlug/activity/facets", ({ request }) => {
				record(request);
				return HttpResponse.json({ repositories: people.repositories, teams: people.teams });
			}),
			http.get("*/workspaces/:workspaceSlug/activity/people/:userId", ({ request }) => {
				record(request);
				return HttpResponse.json(personDetail);
			}),
			http.get(/\/workspaces\/[^/]+\/activity\/(?:(?:people\/\d+|me)\/)?work/u, ({ request }) => {
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

	/**
	 * People and automation as the server classifies them, by workspace: a classification moves the
	 * account between the two lists. Returns the classified accounts, `workspace/userId`, for a test
	 * that holds a reply to record the change itself.
	 */
	function serveClassification(): Set<string> {
		const automated = new Set<string>();
		server.use(
			http.get("*/workspaces/:workspaceSlug/activity/people", ({ request, params }) => {
				record(request);
				const moved = (id: number) => automated.has(`${String(params.workspaceSlug)}/${id}`);
				return HttpResponse.json({
					...people,
					people: people.people.filter(({ person }) => !moved(person.id)),
					automation: people.people
						.filter(({ person }) => moved(person.id))
						.map((entry) => ({ ...entry, kind: "AUTOMATION" })),
				});
			}),
			http.patch(
				"*/workspaces/:workspaceSlug/activity/people/:userId/automation",
				({ request, params }) => {
					const url = new URL(request.url);
					automation.push(url);
					const account = `${String(params.workspaceSlug)}/${String(params.userId)}`;
					if (url.searchParams.get("treatAsAutomation") === "true") {
						automated.add(account);
					} else {
						automated.delete(account);
					}
					return new HttpResponse(null, { status: 204 });
				},
			),
		);
		return automated;
	}

	it("reads everyone in the last 90 days and keeps the defaults out of the address", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity");

		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
		await waitFor(() => expect(readsOf("/activity/people").length).toBeGreaterThan(0));
		const [first] = readsOf("/activity/people");
		expect(first?.search).toBe("?range=90d");
		expect(readsOf("/activity/work")).toHaveLength(0);
		expect(router.state.location.href).toBe("/w/acme/workspace-activity");
	});

	describe("People read recovery", () => {
		const recoveredPeople = {
			...people,
			people: people.people.slice(0, 1).map((entry) => ({
				...entry,
				person: { ...entry.person, id: 19, login: "grace", name: "Grace" },
			})),
		};
		const queryKey = getActivityPeopleQueryKey({
			path: { workspaceSlug: "acme" },
			query: { range: "90d" },
		});

		it("keeps the same workspace's people through a failed refresh and retries the warning", async () => {
			const user = userEvent.setup();
			const served = servePeople([people, { status: 503 }, recoveredPeople]);
			const { queryClient } = renderRouteAtWithRouter("/w/acme/workspace-activity");
			await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
			await act(async () => {
				await queryClient.invalidateQueries({ queryKey, exact: true });
			});

			const warning = await screen.findByRole("alert");
			expect(served.map(({ status }) => status)).toStrictEqual([200, 503]);
			expect(screen.queryByRole("table", { name: "People" })).not.toBeNull();
			expect(screen.queryByRole("link", { name: "Bob" })).not.toBeNull();
			await user.click(within(warning).getByRole("button", { name: "Retry" }));

			await screen.findByRole("link", { name: "Grace" });
			expect(served.map(({ status }) => status)).toStrictEqual([200, 503, 200]);
			expect(screen.queryByRole("link", { name: "Bob" })).toBeNull();
			expect(screen.queryByRole("alert")).toBeNull();
		});

		it("does not call a failed first People read empty and retries it", async () => {
			const user = userEvent.setup();
			const served = servePeople([{ status: 503 }, recoveredPeople]);
			renderRouteAtWithRouter("/w/acme/workspace-activity");
			const warning = await screen.findByRole("alert", undefined, ROUTE_RENDER_WAIT);

			expect(screen.queryByRole("table", { name: "People" })).toBeNull();
			expect(screen.queryByText(/^No activity/u)).toBeNull();
			await user.click(within(warning).getByRole("button", { name: "Retry" }));
			await screen.findByRole("link", { name: "Grace" });
			expect(served.map(({ status }) => status)).toStrictEqual([503, 200]);
		});

		it("keeps refused people hidden through a later outage until an authorized read", async () => {
			const served = servePeople([people, { status: 403 }, { status: 503 }, recoveredPeople]);
			const { queryClient } = renderRouteAtWithRouter("/w/acme/workspace-activity");
			await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
			const refresh = async () => {
				await act(async () => {
					await queryClient.invalidateQueries({ queryKey, exact: true });
				});
			};

			await refresh();
			await screen.findByText("Ask a workspace admin or instance admin for access.");
			expect(screen.queryByRole("table", { name: "People" })).toBeNull();
			await refresh();
			await screen.findByRole("button", { name: "Retry" });
			expect(screen.queryByRole("table", { name: "People" })).toBeNull();
			expect(screen.queryByRole("link", { name: "Bob" })).toBeNull();
			await refresh();

			await screen.findByRole("link", { name: "Grace" });
			expect(served.map(({ status }) => status)).toStrictEqual([200, 403, 503, 200]);
			expect(screen.queryByRole("link", { name: "Bob" })).toBeNull();
		});

		it("shows none of the previous workspace's people when the next workspace read fails", async () => {
			const user = userEvent.setup();
			const served = servePeople([people, { status: 503 }, recoveredPeople]);
			server.use(
				http.get("*/workspaces", () =>
					HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
				),
				http.get("*/workspaces/other", () => HttpResponse.json(workspaceListItem("other"))),
			);
			const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity");
			await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
			await router.navigate({
				to: "/w/$workspaceSlug/workspace-activity",
				params: { workspaceSlug: "other" },
			});
			const warning = await screen.findByRole("alert");

			expect(screen.queryByRole("table", { name: "People" })).toBeNull();
			expect(screen.queryByRole("link", { name: "Bob" })).toBeNull();
			await user.click(within(warning).getByRole("button", { name: "Retry" }));
			await screen.findByRole("link", { name: "Grace" });
			expect(served).toStrictEqual([
				{ path: "/workspaces/acme/activity/people", status: 200 },
				{ path: "/workspaces/other/activity/people", status: 503 },
				{ path: "/workspaces/other/activity/people", status: 200 },
			]);
		});
	});

	it("opens the timeline independently and retains the scope and sort when switching views", async () => {
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?range=30d&team=core&repo=acme/api&sort=reviews&dir=asc",
		);
		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
		await user.click(screen.getByRole("tab", { name: "Timeline" }));
		await screen.findByRole("heading", { name: "Timeline" });
		await waitFor(() => expect(readsOf("/activity/work")).toHaveLength(1));
		expect(readsOf("/activity/work")[0]?.searchParams.get("range")).toBe("30d");
		expect(readsOf("/activity/work")[0]?.searchParams.get("team")).toBe("core");
		expect(readsOf("/activity/work")[0]?.searchParams.getAll("repo")).toStrictEqual(["acme/api"]);
		// The people already read hold the pickers' options.
		expect(readsOf("/activity/facets")).toStrictEqual([]);
		expect(router.state.location.search.view).toBe("timeline");
		await user.click(screen.getByRole("tab", { name: "People" }));
		await waitFor(() =>
			expect(router.state.location.href).toBe(
				"/w/acme/workspace-activity?range=30d&team=core&repo=acme/api&sort=reviews&dir=asc",
			),
		);
		expect(screen.getByRole("columnheader", { name: /^Reviews/u }).getAttribute("aria-sort")).toBe(
			"ascending",
		);
	});

	it("counts nobody for a timeline opened by address, and reads its pickers' options once", async () => {
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity?view=timeline");

		await user.click(
			await screen.findByRole("combobox", { name: "Team: Everyone" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("option", { name: "Core / Backend" }));
		await waitFor(() =>
			expect(readsOf("/activity/work").map((url) => url.searchParams.get("team"))).toContain(
				"backend",
			),
		);
		expect(router.state.location.search.team).toBe("backend");
		expect(readsOf("/activity/facets").map(({ search }) => search)).toStrictEqual([""]);
		expect(readsOf("/activity/people")).toStrictEqual([]);
	});

	it("keeps a filtered timeline visible when its pickers fail, and retries only the pickers", async () => {
		server.use(
			http.get(
				"*/workspaces/acme/activity/facets",
				({ request }) => {
					record(request);
					return HttpResponse.json({ status: 500 }, { status: 500 });
				},
				{ once: true },
			),
		);
		server.use(
			http.get("*/workspaces/acme/activity/work", ({ request }) => {
				record(request);
				return HttpResponse.json({ content: [workItem(1)] });
			}),
		);
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?view=timeline&team=backend",
		);
		await screen.findByText("We could not load activity filters", undefined, ROUTE_RENDER_WAIT);
		await screen.findByText("Pull request 1");
		expect(screen.getByRole("combobox", { name: "Team: backend" })).not.toBeNull();
		await user.click(screen.getByRole("button", { name: "Copy as Markdown" }));
		await screen.findByText("Copied 1 item as Markdown");
		await expect(navigator.clipboard.readText()).resolves.toContain("## backend,");
		const response = deferred<Response>();
		server.use(
			http.get("*/workspaces/acme/activity/facets", async ({ request }) => {
				record(request);
				return response.promise;
			}),
		);
		await user.click(screen.getByRole("button", { name: /^Retry$/u }));
		await waitFor(() => expect(readsOf("/activity/facets")).toHaveLength(2));
		expect(screen.queryByRole("button", { name: /^Retry/u })).toBeNull();
		expect(screen.getByText("Pull request 1")).not.toBeNull();
		expect(screen.getByRole("button", { name: "Copy as Markdown" })).not.toBeNull();
		expect(screen.getByRole("combobox", { name: "Team: backend" })).not.toBeNull();
		response.resolve(HttpResponse.json({ teams: people.teams, repositories: people.repositories }));
		await screen.findByRole("combobox", { name: "Team: Core / Backend" });
		expect(screen.queryByText("We could not load activity filters")).toBeNull();
		expect(router.state.location.search.team).toBe("backend");
		expect(readsOf("/activity/facets")).toHaveLength(2);
		expect(readsOf("/activity/work")).toHaveLength(1);
		expect(readsOf("/activity/people")).toHaveLength(0);
	});

	it("does not carry the pickers' options into another workspace's timeline", async () => {
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
			),
			http.get("*/workspaces/other", () => HttpResponse.json(workspaceListItem("other"))),
			http.get("*/workspaces/other/activity/facets", ({ request }) => {
				record(request);
				return HttpResponse.json({
					repositories: [{ id: 99, key: "other/api", name: "other-api" }],
					teams: [{ id: 99, key: "other", name: "Other team" }],
				});
			}),
		);
		const user = userEvent.setup();
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity");
		await screen.findByRole("table", { name: "People" }, ROUTE_RENDER_WAIT);
		await router.navigate({
			to: "/w/$workspaceSlug/workspace-activity",
			params: { workspaceSlug: "other" },
			search: { view: "timeline" },
		});
		await waitFor(() =>
			expect(readsOf("/activity/facets").map(({ pathname }) => pathname)).toContain(
				"/workspaces/other/activity/facets",
			),
		);
		await user.click(await screen.findByRole("combobox", { name: "Team: Everyone" }));
		await expect(screen.findByRole("option", { name: "Other team" })).resolves.not.toBeNull();
		expect(screen.queryByRole("option", { name: "Core" })).toBeNull();
		expect(screen.queryByRole("option", { name: "Core / Backend" })).toBeNull();
		expect(readsOf("/activity/people").every(({ pathname }) => pathname.includes("/acme/"))).toBe(
			true,
		);
	});

	/** The timeline's filter reads answer in turn; its work read always answers. */
	function serveFacets(
		answers: readonly ({ teams: unknown[]; repositories: unknown[] } | { status: number })[],
	) {
		const served: number[] = [];
		server.use(
			http.get("*/workspaces/acme/activity/facets", ({ request }) => {
				record(request);
				const answer = answers[served.length];
				assert(answer, "Filter read exceeded the prepared responses");
				const status = "status" in answer ? answer.status : 200;
				served.push(status);
				return HttpResponse.json(answer, { status });
			}),
			http.get("*/workspaces/acme/activity/work", ({ request }) => {
				record(request);
				return HttpResponse.json({ content: [workItem(1)] });
			}),
		);
		return served;
	}

	describe("Timeline filter read recovery", () => {
		const freshFacets = {
			teams: [{ id: 30, key: "platform", name: "Platform" }],
			repositories: [
				{ id: 30, key: "acme/platform", name: "platform" },
				{ id: 31, key: "acme/docs", name: "docs" },
			],
		};
		const facetsKey = getActivityFacetsQueryKey({ path: { workspaceSlug: "acme" } });

		it("hides refused filter options from a timeline, keeps them hidden through an outage, and shows only a fresh read", async () => {
			const served = serveFacets([
				{ teams: people.teams, repositories: people.repositories },
				{ status: 403 },
				{ status: 503 },
				freshFacets,
			]);
			const user = userEvent.setup();
			const { router, queryClient } = renderRouteAtWithRouter(
				"/w/acme/workspace-activity?view=timeline&team=backend",
			);
			await screen.findByRole("combobox", { name: "Team: Core / Backend" }, ROUTE_RENDER_WAIT);
			await screen.findByText("Pull request 1");
			await expect(teamOptions(user)).resolves.toStrictEqual([
				"Everyone",
				"Core",
				"Core / Backend",
			]);
			screen.getByRole("combobox", { name: "Repository" });
			const reread = async () => {
				await act(async () => {
					await queryClient.invalidateQueries({ queryKey: facetsKey, exact: true });
				});
			};

			await reread();
			await screen.findByText("Ask a workspace admin or instance admin for access.");
			expect(served).toStrictEqual([200, 403]);
			await expect(teamOptions(user)).resolves.toStrictEqual(["Everyone"]);
			expect(screen.queryByRole("combobox", { name: "Repository" })).toBeNull();
			// The chosen team stays as the address names it, so the filter can still be cleared.
			screen.getByRole("combobox", { name: "Team: backend" });
			expect(screen.queryByRole("button", { name: /^Retry/u })).toBeNull();

			await reread();
			await screen.findByRole("button", { name: /^Retry$/u });
			expect(served).toStrictEqual([200, 403, 503]);
			await expect(teamOptions(user)).resolves.toStrictEqual(["Everyone"]);
			expect(screen.queryByRole("combobox", { name: "Repository" })).toBeNull();

			await user.click(screen.getByRole("button", { name: /^Retry$/u }));
			await screen.findByRole("combobox", { name: "Repository" });
			expect(served).toStrictEqual([200, 403, 503, 200]);
			await expect(teamOptions(user)).resolves.toStrictEqual(["Everyone", "Platform"]);
			expect(screen.queryByText("We could not load activity filters")).toBeNull();
			expect(screen.getByText("Pull request 1")).not.toBeNull();
			expect(screen.getByRole("button", { name: "Copy as Markdown" })).not.toBeNull();
			expect(router.state.location.search.team).toBe("backend");
			expect(readsOf("/activity/work")).toHaveLength(1);
			expect(readsOf("/activity/people")).toHaveLength(0);
		});

		it("keeps a timeline's filter options through an outage beside the failure, and retries it", async () => {
			const served = serveFacets([
				{ teams: people.teams, repositories: people.repositories },
				{ status: 503 },
			]);
			const user = userEvent.setup();
			const { queryClient } = renderRouteAtWithRouter("/w/acme/workspace-activity?view=timeline");
			await screen.findByRole("combobox", { name: "Team: Everyone" }, ROUTE_RENDER_WAIT);
			await screen.findByText("Pull request 1");
			await act(async () => {
				await queryClient.invalidateQueries({ queryKey: facetsKey, exact: true });
			});

			await screen.findByText("We could not load activity filters");
			expect(served).toStrictEqual([200, 503]);
			await expect(teamOptions(user)).resolves.toStrictEqual([
				"Everyone",
				"Core",
				"Core / Backend",
			]);
			screen.getByRole("combobox", { name: "Repository" });

			const response = deferred<Response>();
			server.use(
				http.get("*/workspaces/acme/activity/facets", async ({ request }) => {
					record(request);
					return response.promise;
				}),
			);
			await user.click(screen.getByRole("button", { name: /^Retry$/u }));
			try {
				const retrying = await screen.findByRole("button", { name: "Retrying…" });
				expect(retrying.hasAttribute("disabled")).toBe(true);
			} finally {
				response.resolve(HttpResponse.json(freshFacets));
			}
			await waitFor(() =>
				expect(screen.queryByText("We could not load activity filters")).toBeNull(),
			);
			await expect(teamOptions(user)).resolves.toStrictEqual(["Everyone", "Platform"]);
			expect(readsOf("/activity/facets")).toHaveLength(3);
			expect(readsOf("/activity/people")).toHaveLength(0);
		});

		it("offers no filter options and no retry when a timeline's first filter read is refused", async () => {
			const served = serveFacets([{ status: 403 }]);
			const user = userEvent.setup();
			renderRouteAtWithRouter("/w/acme/workspace-activity?view=timeline&repo=acme/api");
			await screen.findByText(
				"Ask a workspace admin or instance admin for access.",
				undefined,
				ROUTE_RENDER_WAIT,
			);
			await screen.findByText("Pull request 1");
			expect(served).toStrictEqual([403]);
			await expect(teamOptions(user)).resolves.toStrictEqual(["Everyone"]);
			expect(screen.queryByRole("button", { name: /^Retry/u })).toBeNull();
			// The chosen repository is only the address's own key, and can still be cleared.
			expect(screen.queryByRole("combobox", { name: /^Repository/u })).toBeNull();
			screen.getByRole("button", { name: "Reset" });
			expect(readsOf("/activity/people")).toHaveLength(0);
		});
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
			screen.getByRole("columnheader", { name: /contributions/iu }).getAttribute("aria-sort"),
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
		await user.click(screen.getByRole("button", { name: /contributions/iu }));
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
			http.get(/\/workspaces\/[^/]+\/activity\/(?:(?:people\/\d+|me)\/)?work/u, () =>
				HttpResponse.json({ content: [workItem(1)] }),
			),
		);
		renderRouteAtWithRouter("/w/acme/workspace-activity?view=timeline&detail=person:bob");

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

	it("marks retained repository counts until the newly selected range arrives", async () => {
		const held = deferred();
		const api = {
			repository: { id: 1, key: "acme/api", name: "api" },
			counts: {
				...counts,
				contributions: 3,
				pullRequestsOpened: 1,
				pullRequestsReviewed: 2,
				comments: 2,
			},
		};
		const web = {
			repository: { id: 2, key: "acme/web", name: "web" },
			counts: {
				...counts,
				contributions: 2,
				pullRequestsOpened: 1,
				pullRequestsMerged: 0,
				pullRequestsReviewed: 1,
				comments: 2,
			},
		};
		server.use(
			http.get("*/workspaces/:workspaceSlug/activity/people/:userId", () =>
				HttpResponse.json({ ...personDetail, repositories: [api, web] }),
			),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:ada-lrz");
		try {
			const level = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
			const region = await within(level).findByRole("region", { name: "Repositories" });
			expect(region.getAttribute("aria-busy")).not.toBe("true");
			within(region).getByRole("cell", { name: "3" });
			server.use(
				http.get("*/workspaces/:workspaceSlug/activity/people/:userId", async () => {
					await held.promise;
					return HttpResponse.json({
						...personDetail,
						counts: { ...counts, contributions: 9, pullRequestsOpened: 6 },
						weeks: personDetail.weeks.map((week) => ({
							...week,
							counts: { ...counts, contributions: 9, pullRequestsOpened: 6 },
						})),
						repositories: [
							{ ...api, counts: { ...api.counts, contributions: 7, pullRequestsOpened: 5 } },
							web,
						],
					});
				}),
			);
			await router.navigate({
				to: "/w/$workspaceSlug/workspace-activity",
				params: { workspaceSlug: "acme" },
				search: { detail: ["person:ada-lrz"], range: "30d" },
			});
			await waitFor(() => expect(region.getAttribute("aria-busy")).toBe("true"));
			within(region).getByRole("cell", { name: "3" });
			expect(within(region).getByRole("paragraph").textContent).toBe("Updating repository counts…");
			held.resolve();
			await waitFor(() => expect(region.getAttribute("aria-busy")).not.toBe("true"));
			expect(within(region).queryByRole("cell", { name: "3" })).toBeNull();
			expect(within(region).queryByText("Updating repository counts…")).toBeNull();
			within(region).getByRole("cell", { name: "7" });
			within(region).getByRole("cell", { name: "acme/api" });
		} finally {
			held.resolve();
		}
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

		const heading = await screen.findByRole("heading", { name: "Reviews" }, ROUTE_RENDER_WAIT);
		const level = heading.closest<HTMLElement>('[role="dialog"]');
		assert(level);
		await within(level).findByText("No activity in this range");
	});

	it("lets an admin treat a person as automation, and nobody else", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		serveClassification();
		const { router } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?view=timeline&detail=person:bob",
		);

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
		await screen.findByText("Bob is treated as automation");
		screen.getByText("In workspace acme.");
		// The account stays open, with the way back.
		await user.click(
			await screen.findByRole("button", { name: "Count as a person" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByText("Bob counts as a person");
		await screen.findByRole("button", { name: "Treat as automation" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.search.detail).toBe("person:bob");
		expect(automation.map((url) => url.search)).toStrictEqual([
			"?treatAsAutomation=true",
			"?treatAsAutomation=false",
		]);
	});

	it("says a failed classification for its own account and workspace, and leaves the other workspace alone", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const response = deferred<Response>();
		serveClassification();
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme"), workspaceListItem("beta", { id: 2 })]),
			),
			http.get("*/workspaces/beta", () => HttpResponse.json(workspaceListItem("beta", { id: 2 }))),
			http.patch(
				"*/workspaces/acme/activity/people/8/automation",
				async ({ request }) => {
					automation.push(new URL(request.url));
					return response.promise;
				},
				{ once: true },
			),
		);
		const { router, queryClient } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?detail=person:bob",
		);
		try {
			await user.click(
				await screen.findByRole("button", { name: "Treat as automation" }, ROUTE_RENDER_WAIT),
			);
			await screen.findByRole("button", { name: "Saving…" });
			await act(async () => {
				await router.navigate({
					to: "/w/$workspaceSlug/workspace-activity",
					params: { workspaceSlug: "beta" },
					search: { detail: ["person:bob"] },
				});
			});
			// Beta's Bob has the same account id, and acme's change does not hold his action.
			const level = await screen.findByRole("dialog", { name: "Bob" }, ROUTE_RENDER_WAIT);
			const betaAction = await within(level).findByRole("button", { name: "Treat as automation" });
			expect(betaAction.getAttribute("aria-disabled")).not.toBe("true");

			await act(async () => {
				response.resolve(HttpResponse.json({ status: 500 }, { status: 500 }));
			});
			await screen.findByText("We could not change how Bob counts");
			screen.getByText(/In workspace acme\.$/u);
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			expect(screen.queryByText(/is treated as automation/u)).toBeNull();
			expect(router.state.location.pathname).toBe("/w/beta/workspace-activity");
			expect(router.state.location.search.detail).toBe("person:bob");
			within(screen.getByRole("dialog", { name: "Bob" })).getByRole("button", {
				name: "Treat as automation",
			});
			expect(automation.map((url) => `${url.pathname}${url.search}`)).toStrictEqual([
				"/workspaces/acme/activity/people/8/automation?treatAsAutomation=true",
			]);
		} finally {
			response.resolve(HttpResponse.json({ status: 500 }, { status: 500 }));
		}
	});

	it("keeps an account busy while its own change is out, after another account's change finished", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const response = deferred<Response>();
		const automated = serveClassification();
		server.use(
			http.patch(
				"*/workspaces/acme/activity/people/8/automation",
				async ({ request }) => {
					automation.push(new URL(request.url));
					return response.promise;
				},
				{ once: true },
			),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");
		try {
			await user.click(
				await screen.findByRole("button", { name: "Treat as automation" }, ROUTE_RENDER_WAIT),
			);
			await screen.findByRole("button", { name: "Saving…" });
			await user.keyboard("{Escape}");
			await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
			await user.click(
				within(screen.getByRole("table", { name: "People" })).getByRole("link", { name: "Ada" }),
			);
			const ada = await screen.findByRole("dialog", { name: "Ada" }, ROUTE_RENDER_WAIT);
			// Another account can be classified while Bob's change is out, and finishes first.
			await user.click(await within(ada).findByRole("button", { name: "Treat as automation" }));
			await screen.findByText("Ada is treated as automation");

			await user.keyboard("{Escape}");
			await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
			await user.click(
				within(screen.getByRole("table", { name: "People" })).getByRole("link", { name: "Bob" }),
			);
			const bob = await screen.findByRole("dialog", { name: "Bob" }, ROUTE_RENDER_WAIT);
			expect(
				within(bob).getByRole("button", { name: "Saving…" }).getAttribute("aria-disabled"),
			).toBe("true");

			automated.add("acme/8");
			await act(async () => {
				response.resolve(new HttpResponse(null, { status: 204 }));
			});
			await screen.findByText("Bob is treated as automation");
			await within(bob).findByRole("button", { name: "Count as a person" });
			expect(automation.map((url) => `${url.pathname}${url.search}`)).toStrictEqual([
				"/workspaces/acme/activity/people/8/automation?treatAsAutomation=true",
				"/workspaces/acme/activity/people/7/automation?treatAsAutomation=true",
			]);
		} finally {
			response.resolve(new HttpResponse(null, { status: 204 }));
		}
	});

	it("refreshes the submitted workspace after an automation reply arrives in another workspace", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const response = deferred<Response>();
		let acmePeople: Wire<ActivityPeople> = people;
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme"), workspaceListItem("beta", { id: 2 })]),
			),
			http.get("*/workspaces/beta", () => HttpResponse.json(workspaceListItem("beta", { id: 2 }))),
			http.get("*/workspaces/acme/activity/people", ({ request }) => {
				record(request);
				return HttpResponse.json(acmePeople);
			}),
			http.get("*/workspaces/beta/activity/people", ({ request }) => {
				record(request);
				return HttpResponse.json(people);
			}),
			http.patch("*/workspaces/acme/activity/people/8/automation", async ({ request }) => {
				automation.push(new URL(request.url));
				return response.promise;
			}),
		);
		const client = testQueryClient();
		client.setDefaultOptions({
			...client.getDefaultOptions(),
			queries: { ...client.getDefaultOptions().queries, staleTime: QUERY_STALE_TIME_MS },
		});
		const { router, queryClient } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?detail=person:bob",
			client,
		);
		const acmeKey = getActivityPeopleQueryKey({
			path: { workspaceSlug: "acme" },
			query: { range: "90d" },
		});
		try {
			await user.click(
				await screen.findByRole("button", { name: "Treat as automation" }, ROUTE_RENDER_WAIT),
			);
			await waitFor(() =>
				expect(automation.map((url) => `${url.pathname}${url.search}`)).toStrictEqual([
					"/workspaces/acme/activity/people/8/automation?treatAsAutomation=true",
				]),
			);
			await user.keyboard("{Escape}");
			await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
			await switchWorkspace(user, "acme", "beta");
			await waitFor(() =>
				expect(router.state.location.pathname).toBe("/w/beta/workspace-activity"),
			);
			await user.click(
				within(await screen.findByRole("table", { name: "People" })).getByRole("link", {
					name: "Ada",
				}),
			);
			await screen.findByRole("dialog", { name: "Ada" }, ROUTE_RENDER_WAIT);
			const bob = people.people.find(({ person }) => person.id === 8);
			assert(bob);
			acmePeople = {
				...people,
				people: people.people.filter(({ person }) => person.id !== 8),
				automation: [{ ...bob, kind: "AUTOMATION" }],
			};
			await act(async () => {
				response.resolve(new HttpResponse(null, { status: 204 }));
			});
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			expect(router.state.location.pathname).toBe("/w/beta/workspace-activity");
			expect(router.state.location.search.detail).toBe("person:ada-lrz");
			const ownership = JSON.stringify({
				acmeInvalidatedBeforeReturn: queryClient.getQueryState(acmeKey)?.isInvalidated,
				peopleReads: readsOf("/activity/people").map((url) => url.pathname),
				productionStaleTime: QUERY_STALE_TIME_MS,
			});
			await user.keyboard("{Escape}");
			await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
			await switchWorkspace(user, "beta", "acme");
			await waitFor(() =>
				expect(router.state.location.pathname).toBe("/w/acme/workspace-activity"),
			);
			await waitFor(() => expect(queryClient.getQueryState(acmeKey)?.fetchStatus).toBe("idle"));
			await waitFor(() =>
				expect(screen.queryByRole("heading", { name: "Automation" }), ownership).not.toBeNull(),
			);
			const automationList = within(screen.getByRole("region", { name: "Automation" }));
			automationList.getByRole("link", { name: "Bob" });
			expect(
				within(screen.getByRole("table", { name: "People" })).queryByRole("link", { name: "Bob" }),
			).toBeNull();
		} finally {
			response.resolve(new HttpResponse(null, { status: 204 }));
		}
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
		await screen.findByText("Bob is hidden from activity");
		screen.getByText("In workspace acme.");
		await user.click(await screen.findByRole("button", { name: "Undo" }));
		await waitFor(() =>
			expect(visibility.map((url) => url.search)).toStrictEqual(["?hidden=true", "?hidden=false"]),
		);
		await screen.findByText("Bob is shown in activity again");
	});

	it("keeps another person's drawer open when an earlier hide finishes", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const response = deferred<Response>();
		let visiblePeople = people;
		server.use(
			http.get("*/workspaces/acme/activity/people", () => HttpResponse.json(visiblePeople)),
			// The hide is held; its Undo shows Bob on the server again.
			http.patch(
				"*/workspaces/acme/activity/people/8/public-visibility",
				async ({ request }) => {
					visibility.push(new URL(request.url));
					return response.promise;
				},
				{ once: true },
			),
			http.patch("*/workspaces/acme/activity/people/8/public-visibility", ({ request }) => {
				visibility.push(new URL(request.url));
				visiblePeople = people;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?detail=person:bob",
		);
		try {
			await user.click(
				await screen.findByRole("button", { name: "Hide from activity" }, ROUTE_RENDER_WAIT),
			);
			await user.click(
				within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Hide" }),
			);
			await waitFor(() =>
				expect(visibility.map((url) => url.search)).toStrictEqual(["?hidden=true"]),
			);
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
			await user.keyboard("{Escape}");
			await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
			await user.click(
				within(screen.getByRole("table", { name: "People" })).getByRole("link", { name: "Ada" }),
			);
			await screen.findByRole("dialog", { name: "Ada" }, ROUTE_RENDER_WAIT);
			expect(router.state.location.search.detail).toBe("person:ada-lrz");
			visiblePeople = { ...people, people: people.people.filter(({ person }) => person.id !== 8) };
			await act(async () => {
				response.resolve(new HttpResponse(null, { status: 204 }));
			});
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			expect(router.state.location.search.detail).toBe("person:ada-lrz");
			screen.getByRole("dialog", { name: "Ada" });
			await screen.findByText("Bob is hidden from activity");
			await user.click(screen.getByRole("button", { name: "Undo" }));
			await waitFor(() =>
				expect(visibility.map((url) => `${url.pathname}${url.search}`)).toStrictEqual([
					"/workspaces/acme/activity/people/8/public-visibility?hidden=true",
					"/workspaces/acme/activity/people/8/public-visibility?hidden=false",
				]),
			);
			expect(router.state.location.search.detail).toBe("person:ada-lrz");
			await screen.findByText("Bob is shown in activity again");
			// Bob is listed again once Ada's level is closed.
			await user.keyboard("{Escape}");
			await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
			await within(screen.getByRole("table", { name: "People" })).findByRole("link", {
				name: "Bob",
			});
		} finally {
			response.resolve(new HttpResponse(null, { status: 204 }));
		}
	});

	it("says a failed hide was for its own person, and leaves the one opened since alone", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const response = deferred<Response>();
		server.use(
			http.patch("*/workspaces/acme/activity/people/8/public-visibility", async ({ request }) => {
				visibility.push(new URL(request.url));
				return response.promise;
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?detail=person:bob",
		);
		try {
			await confirmHide(user);
			await openAdaInstead(user, router);
			await act(async () => {
				response.resolve(HttpResponse.json({ status: 500 }, { status: 500 }));
			});
			await screen.findByText("We could not hide Bob from activity");
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			expect(screen.queryByText("Bob is hidden from activity")).toBeNull();
			expect(router.state.location.search.detail).toBe("person:ada-lrz");
			screen.getByRole("dialog", { name: "Ada" });
			expect(visibility.map((url) => url.search)).toStrictEqual(["?hidden=true"]);
		} finally {
			response.resolve(HttpResponse.json({ status: 500 }, { status: 500 }));
		}
	});

	it("leaves the same person open in another workspace when a hide in the first finishes", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const response = deferred<Response>();
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme"), workspaceListItem("beta", { id: 2 })]),
			),
			http.get("*/workspaces/beta", () => HttpResponse.json(workspaceListItem("beta", { id: 2 }))),
			http.patch(
				"*/workspaces/acme/activity/people/8/public-visibility",
				async ({ request }) => {
					visibility.push(new URL(request.url));
					return response.promise;
				},
				{ once: true },
			),
		);
		const { router, queryClient } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?detail=person:bob",
		);
		try {
			await confirmHide(user);
			await act(async () => {
				await router.navigate({
					to: "/w/$workspaceSlug/workspace-activity",
					params: { workspaceSlug: "beta" },
					search: { detail: ["person:bob"] },
				});
			});
			const level = await screen.findByRole("dialog", { name: "Bob" }, ROUTE_RENDER_WAIT);
			// Beta's own Bob can be hidden while acme's request is out.
			const betaHide = await within(level).findByRole("button", { name: "Hide from activity" });
			expect(betaHide.getAttribute("aria-disabled")).not.toBe("true");

			await act(async () => {
				response.resolve(new HttpResponse(null, { status: 204 }));
			});
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			await screen.findByText("Bob is hidden from activity");
			screen.getByText("In workspace acme.");
			expect(router.state.location.pathname).toBe("/w/beta/workspace-activity");
			expect(router.state.location.search.detail).toBe("person:bob");
			await user.click(screen.getByRole("button", { name: "Undo" }));
			await waitFor(() =>
				expect(visibility.map((url) => `${url.pathname}${url.search}`)).toStrictEqual([
					"/workspaces/acme/activity/people/8/public-visibility?hidden=true",
					"/workspaces/acme/activity/people/8/public-visibility?hidden=false",
				]),
			);
			expect(router.state.location.search.detail).toBe("person:bob");
		} finally {
			response.resolve(new HttpResponse(null, { status: 204 }));
		}
	});

	it("preserves the destination person while a workspace navigation is waiting for membership", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const response = deferred<Response>();
		const membership = deferred<Response>();
		let membershipRequested = false;
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme"), workspaceListItem("beta", { id: 2 })]),
			),
			http.get("*/workspaces/beta", () => HttpResponse.json(workspaceListItem("beta", { id: 2 }))),
			http.get("*/workspaces/beta/members/me", async () => {
				membershipRequested = true;
				return membership.promise;
			}),
			http.patch("*/workspaces/acme/activity/people/8/public-visibility", async ({ request }) => {
				visibility.push(new URL(request.url));
				return response.promise;
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?detail=person:bob",
		);
		let navigation: ReturnType<typeof router.navigate> | undefined;
		try {
			await confirmHide(user);
			await act(async () => {
				navigation = router.navigate({
					to: "/w/$workspaceSlug/workspace-activity",
					params: { workspaceSlug: "beta" },
					search: { detail: ["person:bob"] },
				});
			});
			await waitFor(() => expect(membershipRequested).toBe(true));
			expect(router.state.location.pathname).toBe("/w/beta/workspace-activity");
			expect(router.state.location.search.detail).toBe("person:bob");
			await act(async () => {
				response.resolve(new HttpResponse(null, { status: 204 }));
			});
			await screen.findByText("Bob is hidden from activity");
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			expect(router.state.location.pathname).toBe("/w/beta/workspace-activity");
			expect(router.state.location.search.detail).toBe("person:bob");
			await act(async () => {
				membership.resolve(
					HttpResponse.json(
						ownMembership("ADMIN", {
							account: {
								id: "7",
								status: "ACTIVE",
								displayName: "Ada",
								avatarUrl: null,
								identities: [
									{ provider: "GITLAB", login: "ada-lrz", displayName: "Ada", avatarUrl: null },
								],
							},
						}),
					),
				);
				await navigation;
			});
			await screen.findByRole("dialog", { name: "Bob" }, ROUTE_RENDER_WAIT);
			expect(router.state.location.search.detail).toBe("person:bob");
		} finally {
			await act(async () => {
				response.resolve(new HttpResponse(null, { status: 204 }));
				membership.resolve(
					HttpResponse.json(
						ownMembership("ADMIN", {
							account: {
								id: "7",
								status: "ACTIVE",
								displayName: "Ada",
								avatarUrl: null,
								identities: [
									{ provider: "GITLAB", login: "ada-lrz", displayName: "Ada", avatarUrl: null },
								],
							},
						}),
					),
				);
				await navigation;
			});
		}
	});

	it("decides whom a hide closes only once the people are read again", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const reread = deferred<Response>();
		const { router, queryClient } = renderRouteAtWithRouter(
			"/w/acme/workspace-activity?detail=person:bob",
		);
		await screen.findByRole("button", { name: "Hide from activity" }, ROUTE_RENDER_WAIT);
		// The hide answers at once; the reads after it are held while the admin moves to Ada.
		server.use(http.get("*/workspaces/acme/activity/people", async () => reread.promise));
		try {
			await confirmHide(user);
			await waitFor(() =>
				expect(visibility.map((url) => url.search)).toStrictEqual(["?hidden=true"]),
			);
			await openAdaInstead(user, router);
			await act(async () => {
				reread.resolve(HttpResponse.json(people));
			});
			await screen.findByText("Bob is hidden from activity");
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			expect(router.state.location.search.detail).toBe("person:ada-lrz");
			screen.getByRole("dialog", { name: "Ada" });
		} finally {
			reread.resolve(HttpResponse.json(people));
		}
	});

	it("closes a category opened over the hidden person together with the person", async () => {
		role = "ADMIN";
		const user = userEvent.setup();
		const response = deferred<Response>();
		server.use(
			http.patch("*/workspaces/acme/activity/people/8/public-visibility", async ({ request }) => {
				visibility.push(new URL(request.url));
				return response.promise;
			}),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/workspace-activity?detail=person:bob");
		try {
			await confirmHide(user);
			await act(async () => {
				await router.navigate({
					to: "/w/$workspaceSlug/workspace-activity",
					params: { workspaceSlug: "acme" },
					search: { detail: ["person:bob", "activity:reviews"] },
				});
			});
			await screen.findByRole("dialog", { name: "Reviews" }, ROUTE_RENDER_WAIT);
			await act(async () => {
				response.resolve(new HttpResponse(null, { status: 204 }));
			});
			await waitFor(() => expect(router.state.location.search.detail).toBeUndefined());
		} finally {
			response.resolve(new HttpResponse(null, { status: 204 }));
		}
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
		renderRouteAtWithRouter("/w/acme/workspace-activity?view=timeline&detail=person:bob");

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
		renderRouteAtWithRouter("/w/acme/workspace-activity?view=timeline&detail=person:bob");

		const level = await screen.findByRole("dialog", undefined, ROUTE_RENDER_WAIT);
		await within(level).findByRole("heading", { name: "Bob" });
		expect(within(level).queryByRole("button", { name: /automation|as a person/u })).toBeNull();
	});

	it("offers a member no automation action", async () => {
		renderRouteAtWithRouter("/w/acme/workspace-activity?view=timeline&detail=person:bob");

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
		// The practice profile sends a workspace that reviews no practices away, so this one reviews them.
		server.use(
			http.get("*/workspaces", () =>
				HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
			),
		);
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
		// The addressed group level has mounted over the profile before its final address is checked.
		await screen.findByRole("dialog", { name: "Practice group" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.href).toBe(
			"/w/acme/practice-profile?detail=practice-group:review-ready-work",
		);
	});

	describe.each(TIMELINES)("%s", (_timeline, address, path, queryKeyOf, regionOf) => {
		async function openWithAnswers(answers: readonly WorkAnswer[]) {
			const served: number[] = [];
			server.use(...workAnswers(path, served, answers));
			const { queryClient } = renderRouteAtWithRouter(address);
			const region = await regionOf();
			await within(region).findByText("Pull request 101", undefined, ROUTE_RENDER_WAIT);
			const refresh = async () => {
				await act(async () => {
					await queryClient.invalidateQueries({ queryKey: queryKeyOf(), exact: true });
				});
			};
			return { served, region, refresh, stateOf: () => queryClient.getQueryState(queryKeyOf()) };
		}

		it("keeps refused work hidden through a later failure, until an authorized read", async () => {
			const { served, region, refresh } = await openWithAnswers([
				earlierWork,
				{ status: 403 },
				{ status: 503 },
				laterWork,
			]);
			const refusal = /Ask a workspace admin or instance admin for access/u;
			expect(served).toStrictEqual([200]);

			await refresh();
			expect(served).toStrictEqual([200, 403]);
			await within(region).findByText(refusal);
			expect(within(region).queryByText("Pull request 101")).toBeNull();

			await refresh();
			expect(served).toStrictEqual([200, 403, 503]);
			// The query settles before its observers hear of it, so the screen has caught up once the
			// refusal it was showing is gone.
			await waitFor(() => expect(within(region).queryByText(refusal)).toBeNull());
			expect(within(region).queryByText("Pull request 101")).toBeNull();
			expect(within(region).queryByRole("button", { name: /Copy as Markdown/u })).toBeNull();

			await refresh();
			expect(served).toStrictEqual([200, 403, 503, 200]);
			await within(region).findByText("Pull request 202");
			expect(within(region).queryByText("Pull request 101")).toBeNull();
		});

		it("keeps its work through a failed refresh and retries the visible warning", async () => {
			const user = userEvent.setup();
			const { served, region, refresh, stateOf } = await openWithAnswers([
				earlierWork,
				{ status: 503 },
				laterWork,
			]);
			expect(served).toStrictEqual([200]);

			await refresh();
			expect(served).toStrictEqual([200, 503]);
			expect(stateOf()?.status).toBe("error");
			expect(within(region).queryByText("Pull request 101")).not.toBeNull();
			expect(within(region).queryByRole("button", { name: /Copy as Markdown/u })).not.toBeNull();

			await within(region).findByText("We could not refresh activity");
			await user.click(within(region).getByRole("button", { name: "Retry" }));
			expect(served).toStrictEqual([200, 503, 200]);
			await within(region).findByText("Pull request 202");
			expect(within(region).queryByText("Pull request 101")).toBeNull();
		});
	});
});
