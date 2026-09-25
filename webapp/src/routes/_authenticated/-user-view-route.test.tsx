import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";

import { client } from "@/api/client.gen";
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
};
const users = { content: [{ userId: 11, login: "never-signed-in", name: "Sam" }], totalPages: 1 };
const viewedProfile = "/w/engineering/user/never-signed-in";

async function submitReason() {
	const user = userEvent.setup();
	await user.click(
		await screen.findByRole("button", { name: "View as user: never-signed-in" }, ROUTE_RENDER_WAIT),
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

	it("opens the normal app for an accountless user after an audited read", async () => {
		let reasonHeader: string | null = null;
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId", ({ request }) => {
				reasonHeader = request.headers.get("X-User-View-Reason");
				return HttpResponse.json(users.content[0]);
			}),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await submitReason();
		await waitFor(() => expect(assigned).toStrictEqual([viewedProfile]));
		expect(reasonHeader).toBe("Check%20Sam's%20profile");
		expect(getUserViewSession()).toStrictEqual({
			operatorAccountId: 42,
			workspaceSlug: "engineering",
			workspaceName: "Engineering",
			userId: 11,
			login: "never-signed-in",
			name: "Sam",
			hasAccount: false,
			reason: "Check Sam's profile",
		});
	});

	it("does not start when the audited read fails", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId", () =>
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
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId", () =>
				HttpResponse.json(
					{ status: 403, code: "step_up_required", maxAgeSeconds: 300 },
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
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId", async () => {
				started.resolve();
				await pendingRead.promise;
				return HttpResponse.json(users.content[0]);
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
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId", () =>
				HttpResponse.json(users.content[0]),
			),
		);
		await act(async () => {
			await router.navigate({
				to: "/admin/workspaces/$workspaceSlug/users",
				params: { workspaceSlug: "engineering" },
				search: { page: 0, user: 11 },
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
		const requests: { path: string; viewedUser: string | null }[] = [];
		const record = ({ request }: { request: Request }) => {
			requests.push({
				path: new URL(request.url).pathname,
				viewedUser: request.headers.get("X-User-View-User"),
			});
		};
		server.events.on("request:start", record);
		const { router } = renderRouteAtWithRouter("/");
		await waitFor(
			() => expect(router.state.resolvedLocation?.pathname).toMatch(/^\/w\/engineering/u),
			ROUTE_RENDER_WAIT,
		);
		server.events.removeListener("request:start", record);

		expect(requests.filter(({ viewedUser }) => viewedUser !== null)).toStrictEqual([]);
		expect(getUserViewSession()).toBeUndefined();
	});

	it.each([
		["/settings", "/w/engineering/user/alex"],
		["/w/other/user/alex", "/w/engineering/user/alex"],
		["/w/engineering/admin", "/w/engineering/user/alex"],
		["/w/engineering/user/sam", "/w/engineering/user/sam"],
	])("resolves %s to %s", async (path, expected) => {
		const { router } = renderRouteAtWithRouter(path);
		await waitFor(
			() => expect(router.state.resolvedLocation?.pathname).toBe(expected),
			ROUTE_RENDER_WAIT,
		);
	});
});
