import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";

import { client } from "@/api/client.gen";
import type { PageResponseDtoUserViewAccount, UserViewAccount } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { problemType } from "@/lib/problem-detail";
import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import {
	applyUserViewHeaders,
	clearUserView,
	getUserViewSession,
} from "@/runtime/user-view/session";
import { deferred } from "@/test/async";
import { captureNavigation, restoreNavigation } from "@/test/navigation";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";
import { storeUserView } from "@/test/user-view";

const workspace = {
	id: 1,
	workspaceSlug: "engineering",
	displayName: "Engineering",
	practicesEnabled: true,
};
const sam: Wire<UserViewAccount> = {
	accountId: "11",
	providerLogin: "never-signed-in",
	account: {
		id: "11",
		status: "UNCLAIMED",
		displayName: "Sam",
		avatarUrl: null,
		identities: [
			{ provider: "GITHUB", login: "never-signed-in", displayName: "Sam", avatarUrl: null },
			{ provider: "GITLAB", login: "sam-lrz", displayName: "Sam", avatarUrl: null },
		],
	},
};
function usersPage(content: Wire<UserViewAccount>[]): Wire<PageResponseDtoUserViewAccount> {
	const sort = { empty: true, sorted: false, unsorted: true };
	return {
		content,
		empty: content.length === 0,
		first: true,
		last: true,
		number: 0,
		numberOfElements: content.length,
		pageable: { offset: 0, pageNumber: 0, pageSize: 20, paged: true, unpaged: false, sort },
		size: 20,
		sort,
		totalElements: content.length,
		totalPages: 1,
	};
}
const users = usersPage([sam]);
const viewedProfile = "/w/engineering/workspace-activity?detail=person:never-signed-in";

async function submitReason() {
	const user = userEvent.setup();
	await user.click(
		await screen.findByRole("button", { name: "View as user: Sam" }, ROUTE_RENDER_WAIT),
	);
	const dialog = screen.getByRole("dialog", { name: "View as Sam" });
	await user.type(
		within(dialog).getByRole("textbox", { name: "Reason for access" }),
		"Check Sam's profile",
	);
	await user.click(within(dialog).getByRole("button", { name: "View as user" }));
}

describe("view as user entry", () => {
	let assigned: string[] = [];
	beforeEach(() => {
		assigned = captureNavigation();
		server.use(
			http.get("*/workspaces", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspace)),
			http.get("*/workspaces/:workspaceSlug/user-view/users", () => HttpResponse.json(users)),
		);
	});
	afterEach(() => {
		clearUserView();
		restoreNavigation();
	});

	it("views one multi-provider account by its account id after an audited read", async () => {
		let read: { accountId: unknown; reason: string | null } | undefined;
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:accountId", ({ params, request }) => {
				read = { accountId: params.accountId, reason: request.headers.get("X-User-View-Reason") };
				return HttpResponse.json(sam);
			}),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await expect(
			screen.findAllByRole("button", { name: /^View as user/u }, ROUTE_RENDER_WAIT),
		).resolves.toHaveLength(1);
		await submitReason();
		await waitFor(() => expect(assigned).toStrictEqual([viewedProfile]));
		expect(read).toStrictEqual({ accountId: "11", reason: "Check%20Sam's%20profile" });
		expect(getUserViewSession()).toStrictEqual({
			operatorAccountId: 42,
			workspaceSlug: "engineering",
			workspaceName: "Engineering",
			accountId: "11",
			providerLogin: "never-signed-in",
			name: "Sam",
			unclaimed: true,
			reason: "Check Sam's profile",
		});
	});

	it("opens the workspace home for an account with no login in this workspace", async () => {
		const noLogin = { ...sam, providerLogin: null };
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users", () =>
				HttpResponse.json(usersPage([noLogin])),
			),
			http.get("*/workspaces/:workspaceSlug/user-view/users/:accountId", () =>
				HttpResponse.json(noLogin),
			),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await submitReason();
		await waitFor(() => expect(assigned).toStrictEqual(["/w/engineering"]));
		expect(getUserViewSession()?.providerLogin).toBeNull();
	});

	it("does not take a provider login for an account id", async () => {
		renderRouteAt("/admin/workspaces/engineering/users?account=never-signed-in");
		await screen.findByRole("button", { name: "View as user: Sam" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("dialog")).toBeNull();
	});

	it("does not start when the audited read fails", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:accountId", () =>
				HttpResponse.json(
					{ status: 503, detail: "User view audit is unavailable" },
					{ status: 503 },
				),
			),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await submitReason();
		await screen.findByText("User view audit is unavailable");
		expect(getUserViewSession()).toBeUndefined();
		expect(assigned).toStrictEqual([]);
	});

	it("asks for a new sign-in when the audited read requires it", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:accountId", () =>
				HttpResponse.json(
					{ status: 403, type: problemType("step-up-required"), maxAgeSeconds: 300 },
					{ status: 403 },
				),
			),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await submitReason();
		await screen.findByRole("dialog", { name: "Confirm access" });
		expect(screen.queryByRole("dialog", { name: "View as Sam" })).toBeNull();
		expect(getUserViewSession()).toBeUndefined();
		expect(assigned).toStrictEqual([]);
	});

	it("cancels a pending read and lets the same user be selected again", async () => {
		const pendingRead = deferred();
		const started = deferred();
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:accountId", async () => {
				started.resolve();
				await pendingRead.promise;
				return HttpResponse.json(sam);
			}),
		);
		const { queryClient, router } = renderRouteAtWithRouter("/admin/workspaces/engineering/users");
		await submitReason();
		await started.promise;
		await act(async () => {
			await router.navigate({
				to: "/admin/workspaces/$workspaceSlug/users",
				params: { workspaceSlug: "engineering" },
				search: { page: 0 },
			});
		});
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:accountId", () =>
				HttpResponse.json(sam),
			),
		);
		await act(async () => {
			await router.navigate({
				to: "/admin/workspaces/$workspaceSlug/users",
				params: { workspaceSlug: "engineering" },
				search: { page: 0, account: "11" },
			});
		});
		const dialog = await screen.findByRole("dialog", { name: "View as Sam" });
		const user = userEvent.setup();
		await user.type(
			within(dialog).getByRole("textbox", { name: "Reason for access" }),
			"Check again",
		);
		await user.click(within(dialog).getByRole("button", { name: "View as user" }));
		await waitFor(() => expect(assigned).toStrictEqual([viewedProfile]));
		// The cancelled read still answers; it must settle without starting a second view.
		await act(async () => pendingRead.resolve());
		await waitFor(() => expect(queryClient.isMutating()).toBe(0));
		expect(assigned).toStrictEqual([viewedProfile]);
		expect(getUserViewSession()?.reason).toBe("Check again");
	});
});

describe("user view guard", () => {
	beforeAll(() => client.interceptors.request.use(applyUserViewHeaders));
	beforeEach(() => {
		storeUserView();
		server.use(http.get("*/workspaces", () => HttpResponse.json([workspace])));
	});
	afterEach(clearUserView);
	afterAll(() => client.interceptors.request.eject(applyUserViewHeaders));

	it("drops a view another account left in the tab before any request carries it", async () => {
		storeUserView({ operatorAccountId: 7 });
		server.use(http.get("*/user", () => HttpResponse.json({ ...currentUser, id: 8 })));
		const requests: { path: string; viewedAccount: string | null }[] = [];
		const record = ({ request }: { request: Request }) => {
			requests.push({
				path: new URL(request.url).pathname,
				viewedAccount: request.headers.get("X-User-View-Account"),
			});
		};
		server.events.on("request:start", record);
		const { router } = renderRouteAtWithRouter("/");
		await waitFor(
			() => expect(router.state.resolvedLocation?.pathname).toMatch(/^\/w\/engineering/u),
			ROUTE_RENDER_WAIT,
		);
		server.events.removeListener("request:start", record);

		expect(requests.filter(({ viewedAccount }) => viewedAccount !== null)).toStrictEqual([]);
		expect(getUserViewSession()).toBeUndefined();
	});

	// A view outside its workspace lands on the workspace home, which the home route decides.
	it.each([
		["/settings", "/w/engineering/practice-profile"],
		["/w/other/workspace-activity?detail=person:alex", "/w/engineering/practice-profile"],
		["/w/engineering/admin", "/w/engineering/practice-profile"],
		["/w/engineering/workspace-activity?detail=person:sam", "/w/engineering/workspace-activity"],
	])("resolves %s to %s", async (path, expected) => {
		const { router } = renderRouteAtWithRouter(path);
		await waitFor(
			() => expect(router.state.resolvedLocation?.pathname).toBe(expected),
			ROUTE_RENDER_WAIT,
		);
	});
});
