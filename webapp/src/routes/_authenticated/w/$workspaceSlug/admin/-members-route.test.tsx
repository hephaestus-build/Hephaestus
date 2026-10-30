import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type {
	AssignRoleRequest,
	SetMemberHiddenRequest,
	WorkspaceMembership,
} from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { currentUser } from "@/mocks/fixtures/auth";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 15_000 });

const WORKSPACE = workspaceListItem("acme", { displayName: "Acme", practicesEnabled: true });
const TUTORS = workspaceListItem("tutors", {
	id: 2,
	displayName: "Tutors",
	practicesEnabled: true,
});

function member(
	accountId: string,
	account: Partial<Wire<WorkspaceMembership>["account"]>,
	overrides: Partial<Wire<WorkspaceMembership>> = {},
): Wire<WorkspaceMembership> {
	return {
		account: {
			id: accountId,
			status: "ACTIVE",
			displayName: null,
			avatarUrl: null,
			identities: [],
			...account,
		},
		role: "MEMBER",
		joinedAt: "2026-03-02T09:00:00Z",
		leftAt: null,
		hidden: false,
		reviewSelected: true,
		eligibleForPracticeReview: true,
		publicActivityAnswered: null,
		...overrides,
	};
}

const ADA = member("7", {
	displayName: "Ada Lovelace",
	identities: [
		{ provider: "GITHUB", login: "ada", displayName: "Ada Lovelace", avatarUrl: null },
		{ provider: "GITLAB", login: "ada-lrz", displayName: "Ada (LRZ)", avatarUrl: null },
	],
});

const SAM = member("8", {
	status: "UNCLAIMED",
	identities: [{ provider: "GITHUB", login: "sam", displayName: "Sam Unclaimed", avatarUrl: null }],
});

function membershipPage(
	content: Wire<WorkspaceMembership>[],
	number = 0,
	totalElements = content.length,
	size = 10,
) {
	return {
		content,
		number,
		size,
		totalElements,
		totalPages: Math.ceil(totalElements / size),
		first: number === 0,
		last: (number + 1) * size >= totalElements,
		numberOfElements: content.length,
		empty: content.length === 0,
	};
}

function mockMembersRoute(members: Wire<WorkspaceMembership>[] = [ADA, SAM]) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces", () => HttpResponse.json([WORKSPACE])),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(WORKSPACE)),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/members", () =>
			HttpResponse.json(membershipPage(members)),
		),
		http.get("*/workspaces/:workspaceSlug/team", () => HttpResponse.json([])),
	);
}

const REVIEWERS = {
	id: 3,
	name: "Reviewers",
	slug: "reviewers",
	hidden: false,
	membershipCount: 1,
	repoPermissionCount: 0,
	repositories: [],
	labels: [],
	members: [],
};

/**
 * The members read: Ada and Sam at once, until a read names `param`. That read waits for `held`,
 * then answers Sam alone. Every read's search params land in `requests`.
 */
function holdMembersReadNaming(
	param: "q" | "team",
	held: Promise<void>,
	requests: Record<string, string>[] = [],
) {
	return http.get("*/workspaces/:workspaceSlug/members", async ({ request }) => {
		const params = new URL(request.url).searchParams;
		requests.push(Object.fromEntries(params));
		if (params.get(param) === null) {
			return HttpResponse.json(membershipPage([ADA, SAM]));
		}
		await held;
		return HttpResponse.json(membershipPage([SAM]));
	});
}

function membersTable(): HTMLElement {
	return screen.getByRole("table", { name: "Workspace members" });
}

function rowOf(name: string): HTMLElement {
	const row = screen.getByText(name, { selector: "div" }).closest("tr");
	if (row === null) {
		throw new Error(`No row for ${name}`);
	}
	return row;
}

describe("workspace members route", () => {
	it("does not trap sidebar navigation", async () => {
		mockMembersRoute();
		const { router } = renderRouteAtWithRouter("/w/acme/admin/members");

		await screen.findByRole("heading", { name: "Members" }, ROUTE_RENDER_WAIT);
		fireEvent.click(await screen.findByRole("link", { name: "Settings" }));

		await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/admin/settings"));
	});

	it("shows one row per account, with every identity and unclaimed accounts marked", async () => {
		mockMembersRoute();
		renderRouteAt("/w/acme/admin/members");

		await screen.findByText("Ada Lovelace", { selector: "div" }, ROUTE_RENDER_WAIT);
		const ada = within(rowOf("Ada Lovelace"));
		ada.getByText("ada");
		ada.getByText("ada-lrz");
		screen.getByText("Showing 2 of 2 members");

		const sam = within(rowOf("Sam Unclaimed"));
		sam.getByText("Unclaimed");
		expect(sam.queryByText("Active")).toBeNull();
	});

	it("reads only the selected page and uses the server count before an explicit page jump", async () => {
		const many = Array.from({ length: 10 }, (_, index) =>
			member(String(index + 100), { displayName: `Member ${String(index).padStart(3, "0")}` }),
		);
		const requests: Record<string, string>[] = [];
		const pages: number[] = [];
		mockMembersRoute();
		server.use(
			http.get("*/workspaces/:workspaceSlug/members", ({ request }) => {
				const params = new URL(request.url).searchParams;
				const page = Number(params.get("page"));
				pages.push(page);
				requests.push(Object.fromEntries(params));
				return HttpResponse.json(membershipPage(many, 0, 21));
			}),
		);
		renderRouteAt("/w/acme/admin/members");
		await screen.findByText("Showing 10 of 21 members", undefined, ROUTE_RENDER_WAIT);
		expect(pages).toStrictEqual([0]);
		expect(requests).toStrictEqual([{ page: "0", size: "10", sort: "name,asc" }]);
		expect(screen.queryByText("Sam Unclaimed", { selector: "div" })).toBeNull();
		server.use(
			http.get("*/workspaces/:workspaceSlug/members", ({ request }) => {
				pages.push(Number(new URL(request.url).searchParams.get("page")));
				return HttpResponse.json(membershipPage([SAM], 2, 21));
			}),
		);
		await userEvent.click(screen.getByRole("link", { name: "Go to page 3" }));
		await screen.findByText("Sam Unclaimed", { selector: "div" }, ROUTE_RENDER_WAIT);
		screen.getByText("Showing 1 of 21 members");
		expect(pages).toStrictEqual([0, 2]);
	});

	it("opens a later page directly without reading earlier pages", async () => {
		const requests: Record<string, string>[] = [];
		mockMembersRoute();
		server.use(
			http.get("*/workspaces/:workspaceSlug/members", ({ request }) => {
				requests.push(Object.fromEntries(new URL(request.url).searchParams));
				return HttpResponse.json(membershipPage([SAM], 2, 21));
			}),
		);
		renderRouteAt("/w/acme/admin/members?page=2");
		await screen.findByText("Sam Unclaimed", { selector: "div" }, ROUTE_RENDER_WAIT);
		screen.getByText("Showing 1 of 21 members");
		expect(screen.queryByText("Ada Lovelace", { selector: "div" })).toBeNull();
		expect(requests).toStrictEqual([{ page: "2", size: "10", sort: "name,asc" }]);
	});

	it("keeps an empty page honest and lets the reader return to the first page", async () => {
		const pages: number[] = [];
		mockMembersRoute();
		server.use(
			http.get("*/workspaces/:workspaceSlug/members", ({ request }) => {
				const page = Number(new URL(request.url).searchParams.get("page"));
				pages.push(page);
				return HttpResponse.json(membershipPage([], 9, 2));
			}),
		);
		renderRouteAt("/w/acme/admin/members?page=9");
		await screen.findByText("No members on this page", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("This workspace has no members yet.")).toBeNull();
		expect(pages).toStrictEqual([9]);
		server.use(
			http.get("*/workspaces/:workspaceSlug/members", ({ request }) => {
				pages.push(Number(new URL(request.url).searchParams.get("page")));
				return HttpResponse.json(membershipPage([ADA, SAM]));
			}),
		);
		await userEvent.click(screen.getByRole("button", { name: "Go to first page" }));
		await screen.findByText("Ada Lovelace", { selector: "div" }, ROUTE_RENDER_WAIT);
		expect(pages).toStrictEqual([9, 0]);
	});

	it.each(["Profile", "123"])(
		"sends search %s, team, sorting and page scope without filtering the server's result again",
		async (query) => {
			const profileMatch = {
				...ADA,
				account: {
					...ADA.account,
					identities: [
						{
							provider: "GITHUB" as const,
							login: "ada",
							displayName: `${query} match`,
							avatarUrl: null,
						},
					],
				},
			};
			const requests: Record<string, string>[] = [];
			mockMembersRoute();
			server.use(
				http.get("*/workspaces/:workspaceSlug/team", () => HttpResponse.json([REVIEWERS])),
				http.get("*/workspaces/:workspaceSlug/members", ({ request }) => {
					const params = new URL(request.url).searchParams;
					requests.push(Object.fromEntries(params));
					return HttpResponse.json(membershipPage([profileMatch], 1, 21, 20));
				}),
			);
			renderRouteAt(
				`/w/acme/admin/members?q=${query}&team=3&sort=username&desc=true&page=1&size=20`,
			);
			await screen.findByText("Ada Lovelace", { selector: "div" }, ROUTE_RENDER_WAIT);
			screen.getByText("Showing 1 of 21 members");
			expect(requests).toStrictEqual([
				{ q: query, team: "3", sort: "username,desc", page: "1", size: "20" },
			]);
			expect(screen.getByRole("columnheader", { name: "Username" }).getAttribute("aria-sort")).toBe(
				"descending",
			);
		},
	);

	it("preserves the server's username order and reverses the sort through the URL", async () => {
		const ada = {
			...ADA,
			account: {
				...ADA.account,
				identities: [
					{ provider: "GITHUB" as const, login: "zzz", displayName: null, avatarUrl: null },
				],
			},
		};
		const sam = {
			...SAM,
			account: {
				...SAM.account,
				identities: [
					{
						provider: "GITHUB" as const,
						login: "aaa",
						displayName: "Sam Unclaimed",
						avatarUrl: null,
					},
				],
			},
		};
		const sorts: (string | null)[] = [];
		mockMembersRoute();
		server.use(
			http.get("*/workspaces/:workspaceSlug/members", ({ request }) => {
				const sort = new URL(request.url).searchParams.get("sort");
				sorts.push(sort);
				return HttpResponse.json(membershipPage([ada, sam]));
			}),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/admin/members");
		await screen.findByText("Ada Lovelace", { selector: "div" }, ROUTE_RENDER_WAIT);
		server.use(
			http.get("*/workspaces/:workspaceSlug/members", ({ request }) => {
				sorts.push(new URL(request.url).searchParams.get("sort"));
				return HttpResponse.json(membershipPage([sam, ada]));
			}),
		);
		await userEvent.click(screen.getByRole("button", { name: "Username" }));
		await waitFor(() => expect(sorts).toContain("username,asc"), ROUTE_RENDER_WAIT);
		await waitFor(() =>
			expect(
				within(screen.getByRole("table", { name: "Workspace members" })).getAllByRole("row")[1],
			).toBe(rowOf("Sam Unclaimed")),
		);
		server.use(
			http.get("*/workspaces/:workspaceSlug/members", ({ request }) => {
				sorts.push(new URL(request.url).searchParams.get("sort"));
				return HttpResponse.json(membershipPage([ada, sam]));
			}),
		);
		await userEvent.click(screen.getByRole("button", { name: "Username" }));
		await waitFor(() => expect(sorts).toContain("username,desc"), ROUTE_RENDER_WAIT);
		await waitFor(() =>
			expect(
				within(screen.getByRole("table", { name: "Workspace members" })).getAllByRole("row")[1],
			).toBe(rowOf("Ada Lovelace")),
		);
		expect(router.state.location.search).toStrictEqual(
			expect.objectContaining({ sort: "username", desc: true }),
		);
	});

	it.each([
		{ scope: "every team", url: "/w/acme/admin/members", team: undefined },
		{ scope: "one team", url: "/w/acme/admin/members?team=3", team: "3" },
	])(
		"keeps the members of $scope on screen while a new search loads, then shows its result",
		async ({ url, team }) => {
			const searched = deferred();
			const requests: Record<string, string>[] = [];
			mockMembersRoute();
			server.use(
				http.get("*/workspaces/:workspaceSlug/team", () => HttpResponse.json([REVIEWERS])),
				holdMembersReadNaming("q", searched.promise, requests),
			);
			// A failed assertion must still answer the held search, or cleanup waits on it.
			try {
				renderRouteAt(url);
				await screen.findByText("Showing 2 of 2 members", undefined, ROUTE_RENDER_WAIT);

				fireEvent.change(screen.getByPlaceholderText("Search by name or username…"), {
					target: { value: "sam" },
				});
				await waitFor(() => expect(requests.some((request) => request.q === "sam")).toBe(true));

				// Same workspace and team: the rows already read stay, marked busy, until the search answers.
				expect(membersTable().getAttribute("aria-busy")).toBe("true");
				within(membersTable()).getByText("Ada Lovelace", { selector: "div" });
				within(membersTable()).getByText("Sam Unclaimed", { selector: "div" });

				searched.resolve();
				await screen.findByText("Showing 1 of 1 member", undefined, ROUTE_RENDER_WAIT);
				expect(screen.queryByText("Ada Lovelace", { selector: "div" })).toBeNull();
				expect(membersTable().getAttribute("aria-busy")).toBeNull();
				expect(requests.every((request) => request.team === team)).toBe(true);
			} finally {
				searched.resolve();
			}
		},
	);

	it("never shows another team's members while the chosen team loads", async () => {
		const reviewers = deferred();
		mockMembersRoute();
		server.use(
			http.get("*/workspaces/:workspaceSlug/team", () => HttpResponse.json([REVIEWERS])),
			holdMembersReadNaming("team", reviewers.promise),
		);
		try {
			renderRouteAt("/w/acme/admin/members");
			await screen.findByText("Showing 2 of 2 members", undefined, ROUTE_RENDER_WAIT);
			const user = userEvent.setup();

			await user.click(screen.getByRole("combobox", { name: "Filter members by team" }));
			await user.click(await screen.findByRole("option", { name: "Reviewers" }));

			await waitFor(() =>
				expect(screen.queryByText("Ada Lovelace", { selector: "div" })).toBeNull(),
			);
			expect(screen.queryByText("Sam Unclaimed", { selector: "div" })).toBeNull();
			expect(screen.queryByText("Showing 2 of 2 members")).toBeNull();
			expect(membersTable().getAttribute("aria-busy")).toBe("true");

			reviewers.resolve();
			await screen.findByText("Sam Unclaimed", { selector: "div" }, ROUTE_RENDER_WAIT);
			screen.getByText("Showing 1 of 1 member");
		} finally {
			reviewers.resolve();
		}
	});

	it("never shows one workspace's members while another workspace's load", async () => {
		const tutorsMembers = deferred();
		mockMembersRoute([ADA]);
		server.use(
			http.get("*/workspaces", () => HttpResponse.json([WORKSPACE, TUTORS])),
			http.get("*/workspaces/tutors", () => HttpResponse.json(TUTORS)),
			http.get("*/workspaces/tutors/members", async () => {
				await tutorsMembers.promise;
				return HttpResponse.json(membershipPage([SAM]));
			}),
		);
		try {
			const { router } = renderRouteAtWithRouter("/w/acme/admin/members");
			await screen.findByText("Ada Lovelace", { selector: "div" }, ROUTE_RENDER_WAIT);

			await router.navigate({
				to: "/w/$workspaceSlug/admin/members",
				params: { workspaceSlug: "tutors" },
			});

			await waitFor(() =>
				expect(screen.queryByText("Ada Lovelace", { selector: "div" })).toBeNull(),
			);
			expect(membersTable().getAttribute("aria-busy")).toBe("true");

			tutorsMembers.resolve();
			await screen.findByText("Sam Unclaimed", { selector: "div" }, ROUTE_RENDER_WAIT);
			expect(screen.queryByText("Ada Lovelace", { selector: "div" })).toBeNull();
		} finally {
			tutorsMembers.resolve();
		}
	});

	it("hides a member by account id", async () => {
		let hidden: { accountId: unknown; body: SetMemberHiddenRequest } | undefined;
		mockMembersRoute();
		server.use(
			http.patch<{ accountId: string }, SetMemberHiddenRequest>(
				"*/workspaces/:workspaceSlug/members/:accountId/hidden",
				async ({ params, request }) => {
					hidden = { accountId: params.accountId, body: await request.json() };
					return HttpResponse.json({ ...SAM, hidden: true });
				},
			),
		);
		renderRouteAt("/w/acme/admin/members");

		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Hide Sam Unclaimed from workspace activity" },
				ROUTE_RENDER_WAIT,
			),
		);

		await waitFor(() => expect(hidden).toStrictEqual({ accountId: "8", body: { hidden: true } }));
	});

	it("changes a role by account id and refreshes the list", async () => {
		let assigned: AssignRoleRequest | undefined;
		let members = [ADA, SAM];
		let reads = 0;
		mockMembersRoute();
		server.use(
			http.get("*/workspaces/:workspaceSlug/members", () => {
				reads += 1;
				return HttpResponse.json(membershipPage(members));
			}),
			http.post<never, AssignRoleRequest>(
				"*/workspaces/:workspaceSlug/members/assign",
				async ({ request }) => {
					const body = await request.json();
					assigned = body;
					members = [ADA, { ...SAM, role: body.role }];
					return HttpResponse.json({ ...SAM, role: body.role });
				},
			),
		);
		renderRouteAt("/w/acme/admin/members");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole("combobox", { name: "Role of Sam Unclaimed" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("option", { name: "Admin" }));

		await waitFor(() => expect(assigned).toStrictEqual({ accountId: "8", role: "ADMIN" }));
		await waitFor(() => expect(reads).toBe(2));
		await waitFor(() =>
			within(screen.getByRole("combobox", { name: "Role of Sam Unclaimed" })).getByText("Admin"),
		);
	});

	it("asks an owner before making a member an owner and sends the change only once confirmed", async () => {
		const assigned: AssignRoleRequest[] = [];
		mockMembersRoute();
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("OWNER")),
			),
			http.post<never, AssignRoleRequest>(
				"*/workspaces/:workspaceSlug/members/assign",
				async ({ request }) => {
					const body = await request.json();
					assigned.push(body);
					return HttpResponse.json({ ...SAM, role: body.role });
				},
			),
		);
		renderRouteAt("/w/acme/admin/members");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole("combobox", { name: "Role of Sam Unclaimed" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("option", { name: "Owner" }));

		const dialog = await screen.findByRole("alertdialog", {
			name: "Make Sam Unclaimed an owner?",
		});
		within(dialog).getByText(/can then delete this workspace/u);
		expect(assigned).toStrictEqual([]);
		// The modal hides the table from the accessibility tree while it asks.
		within(screen.getByRole("combobox", { name: "Role of Sam Unclaimed", hidden: true })).getByText(
			"Member",
		);

		await user.click(within(dialog).getByRole("button", { name: "Make owner" }));

		await waitFor(() => expect(assigned).toStrictEqual([{ accountId: "8", role: "OWNER" }]));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
	});

	it("asks before you lower your own role, and Cancel sends nothing", async () => {
		const own = member("42", { displayName: "The Octocat" }, { role: "ADMIN" });
		let assigns = 0;
		mockMembersRoute([ADA, own]);
		server.use(
			http.post("*/workspaces/:workspaceSlug/members/assign", () => {
				assigns += 1;
				return HttpResponse.json({ ...own, role: "MEMBER" });
			}),
		);
		renderRouteAt("/w/acme/admin/members");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole("combobox", { name: "Role of The Octocat" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("option", { name: "Member" }));

		const dialog = await screen.findByRole("alertdialog", { name: "Remove your admin access?" });
		await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		const role = screen.getByRole("combobox", { name: "Role of The Octocat" });
		within(role).getByText("Admin");
		// Focus returns to the member's Role menu, not to an option of its closed list.
		await waitFor(() => expect(document.activeElement).toBe(role));
		expect(assigns).toBe(0);
	});

	it("closes on confirm and reports a refused change as a toast", async () => {
		mockMembersRoute();
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json(ownMembership("OWNER")),
			),
			http.post("*/workspaces/:workspaceSlug/members/assign", () =>
				HttpResponse.json(
					{ status: 409, detail: "A workspace needs at least one owner." },
					{ status: 409 },
				),
			),
		);
		renderRouteAt("/w/acme/admin/members");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole("combobox", { name: "Role of Ada Lovelace" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("option", { name: "Owner" }));
		const dialog = await screen.findByRole("alertdialog", { name: "Make Ada Lovelace an owner?" });
		await user.click(within(dialog).getByRole("button", { name: "Make owner" }));

		await screen.findByText("We could not change the role");
		screen.getByText("A workspace needs at least one owner.");
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
	});

	it("offers an admin no Owner role and leaves an owner's row read-only", async () => {
		mockMembersRoute([ADA, { ...SAM, role: "OWNER" }]);
		renderRouteAt("/w/acme/admin/members");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole("combobox", { name: "Role of Ada Lovelace" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByRole("option", { name: "Admin" });
		expect(screen.queryByRole("option", { name: "Owner" })).toBeNull();
		expect(screen.queryByRole("combobox", { name: "Role of Sam Unclaimed" })).toBeNull();
		within(rowOf("Sam Unclaimed")).getByText("Owner");
	});

	it("keeps the list and says so when a role change is refused", async () => {
		mockMembersRoute([{ ...ADA, role: "ADMIN" }, SAM]);
		server.use(
			http.post("*/workspaces/:workspaceSlug/members/assign", () =>
				HttpResponse.json(
					{ status: 409, detail: "A workspace needs at least one owner." },
					{ status: 409 },
				),
			),
		);
		renderRouteAt("/w/acme/admin/members");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole("combobox", { name: "Role of Ada Lovelace" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("option", { name: "Member" }));

		await screen.findByText("We could not change the role");
		screen.getByText("Ada Lovelace", { selector: "div" });
	});
});

it("renders the verified nonmember target's members without adding that target to the member list", async () => {
	const requests: string[] = [];
	mockMembersRoute();
	server.use(
		http.get("*/user", () => HttpResponse.json(currentUser)),
		http.get("*/workspaces/tutors", () => HttpResponse.json(TUTORS)),
		http.get("*/workspaces/tutors/members/me", () =>
			HttpResponse.json({ status: 403 }, { status: 403 }),
		),
		http.get("*/workspaces/tutors/members", ({ request }) => {
			requests.push(new URL(request.url).pathname);
			return HttpResponse.json(membershipPage([SAM]));
		}),
	);
	const { router } = renderRouteAtWithRouter("/w/tutors/admin/members");
	await screen.findByText("Sam Unclaimed", { selector: "div" }, ROUTE_RENDER_WAIT);
	expect(router.state.location.pathname).toBe("/w/tutors/admin/members");
	screen.getByText("Tutors administration");
	expect(requests).toHaveLength(1);
	expect(requests[0]).toMatch(/\/workspaces\/tutors\/members$/u);
	expect(screen.queryByText("Ada Lovelace", { selector: "div" })).toBeNull();
});

describe("public activity answers", () => {
	it("marks the members who have not answered, and says nothing of the others' answers", async () => {
		mockMembersRoute([
			member("7", { displayName: "Ada Lovelace" }, { publicActivityAnswered: true }),
			member(
				"8",
				{ displayName: "Sam Unclaimed", status: "UNCLAIMED" },
				{ publicActivityAnswered: false },
			),
		]);
		renderRouteAt("/w/acme/admin/members");

		await screen.findByText("Ada Lovelace", { selector: "div" }, ROUTE_RENDER_WAIT);
		screen.getByRole("columnheader", { name: "Public activity" });
		within(rowOf("Sam Unclaimed")).getByRole("button", { name: "Not answered yet: Sam Unclaimed" });
		const ada = within(rowOf("Ada Lovelace"));
		expect(ada.queryByRole("button", { name: /^Not answered yet/u })).toBeNull();
		expect(ada.queryByText(/Shown|Hidden/u)).toBeNull();
	});

	it("leaves the column out where the workspace does not publish", async () => {
		mockMembersRoute();
		renderRouteAt("/w/acme/admin/members");

		await screen.findByText("Ada Lovelace", { selector: "div" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("columnheader", { name: "Public activity" })).toBeNull();
	});

	it("marks pending members for an instance admin who administers the workspace without a membership", async () => {
		mockMembersRoute();
		server.use(
			http.get("*/user", () => HttpResponse.json(currentUser)),
			http.get("*/workspaces/tutors", () => HttpResponse.json(TUTORS)),
			http.get("*/workspaces/tutors/members/me", () =>
				HttpResponse.json({ status: 403 }, { status: 403 }),
			),
			http.get("*/workspaces/tutors/members", () =>
				HttpResponse.json(membershipPage([{ ...SAM, publicActivityAnswered: false }])),
			),
		);
		renderRouteAt("/w/tutors/admin/members");

		await screen.findByText("Sam Unclaimed", { selector: "div" }, ROUTE_RENDER_WAIT);
		within(rowOf("Sam Unclaimed")).getByRole("button", { name: "Not answered yet: Sam Unclaimed" });
		// The notice asks members only: this admin has no membership to answer with.
		expect(screen.queryByRole("region", { name: /has a public activity page$/u })).toBeNull();
	});
});
