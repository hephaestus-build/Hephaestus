import { act, cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { adminListUsersInfiniteQueryKey } from "@/api/@tanstack/react-query.gen";
import type { AdminAccountView, InstanceSettings } from "@/api/types.gen";
import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { deferred, type Deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

const stepUpRefusal = () =>
	HttpResponse.json(
		{
			status: 403,
			title: "Confirm access",
			detail: "This action requires a recent sign-in.",
			code: "step_up_required",
			maxAgeSeconds: 300,
		},
		{ status: 403 },
	);

/** Opens one row's action menu and picks an item from it. */
async function chooseRowAction(rowName: string, action: string) {
	const user = userEvent.setup();
	await user.click(await screen.findByRole("button", { name: rowName }, ROUTE_RENDER_WAIT));
	await user.click(await screen.findByRole("menuitem", { name: action }));
	return user;
}

function serveRoleChanges(earlierAccounts: AdminAccountView[] = [], failFirstChange = false) {
	let account: AdminAccountView = {
		id: 7,
		displayName: "Ada Lovelace",
		primaryEmail: "ada@example.com",
		appRole: "USER",
		status: "ACTIVE",
	};
	const reads: AdminAccountView["appRole"][] = [];
	const pages: number[] = [];
	const changes: { id: string; body: unknown }[] = [];
	server.use(
		http.get("*/admin/users", ({ request }) => {
			const page = Number(new URL(request.url).searchParams.get("page") ?? 0);
			reads.push(account.appRole);
			pages.push(page);
			return HttpResponse.json([...earlierAccounts, account].slice(page * 25, (page + 1) * 25));
		}),
		http.patch("*/admin/users/:id", async ({ params, request }) => {
			changes.push({ id: String(params.id), body: await request.json() });
			if (failFirstChange && changes.length === 1) {
				return HttpResponse.json(
					{ status: 503, detail: "Role update unavailable." },
					{ status: 503 },
				);
			}
			account = { ...account, appRole: "APP_ADMIN" };
			return HttpResponse.json(account);
		}),
	);
	return { reads, pages, changes };
}

describe("instance users route", () => {
	it("shows the saved role and its inverse action after a successful role change", async () => {
		const { reads, changes } = serveRoleChanges();
		renderRouteAt("/admin/users");
		const table = await screen.findByRole(
			"table",
			{ name: "Application users" },
			ROUTE_RENDER_WAIT,
		);
		const row = await within(table).findByRole("row", { name: /Ada Lovelace/u });
		within(row).getByText("User", { exact: true });
		const user = await chooseRowAction("Actions for Ada Lovelace", "Grant instance admin");
		const dialog = await screen.findByRole("alertdialog", { name: "Grant instance admin?" });
		await user.click(within(dialog).getByRole("button", { name: "Grant instance admin" }));

		await screen.findByText("Role updated to Instance admin");
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect(changes).toStrictEqual([{ id: "7", body: { appRole: "APP_ADMIN" } }]);
		expect.soft(reads).toStrictEqual(["USER", "APP_ADMIN"]);
		await within(table).findByText("Instance admin", { exact: true });
		await user.click(screen.getByRole("button", { name: "Actions for Ada Lovelace" }));
		await screen.findByRole("menuitem", { name: "Revoke instance admin" });
		expect(screen.queryByRole("menuitem", { name: "Grant instance admin" })).toBeNull();
	});

	it("lets the reader close the role dialog while it saves, and reports a later refusal", async () => {
		const held = deferred();
		server.use(
			http.get("*/admin/users", () =>
				HttpResponse.json([
					{
						id: 7,
						displayName: "Ada Lovelace",
						primaryEmail: "ada@example.com",
						appRole: "USER",
						status: "ACTIVE",
					},
				]),
			),
			http.patch("*/admin/users/:id", async () => {
				await held.promise;
				return HttpResponse.json(
					{ status: 503, detail: "Role update unavailable." },
					{ status: 503 },
				);
			}),
		);
		try {
			renderRouteAt("/admin/users");
			const user = await chooseRowAction("Actions for Ada Lovelace", "Grant instance admin");
			const dialog = await screen.findByRole("alertdialog", { name: "Grant instance admin?" });
			await user.click(within(dialog).getByRole("button", { name: "Grant instance admin" }));
			await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

			held.resolve();
			await screen.findByText("Role update unavailable.");
		} finally {
			held.resolve();
		}
	});

	it("keeps loaded pages after a failed role update and refreshes them after a successful retry", async () => {
		const earlierAccounts = Array.from(
			{ length: 25 },
			(_, index) =>
				({
					id: 100 + index,
					displayName: `Other account ${index}`,
					primaryEmail: `other-${index}@example.com`,
					appRole: "USER",
					status: "ACTIVE",
				}) satisfies AdminAccountView,
		);
		const { reads, pages, changes } = serveRoleChanges(earlierAccounts, true);
		renderRouteAt("/admin/users");
		const user = userEvent.setup();
		await user.click(await screen.findByRole("button", { name: "Load more" }, ROUTE_RENDER_WAIT));
		await chooseRowAction("Actions for Ada Lovelace", "Grant instance admin");
		const dialog = await screen.findByRole("alertdialog", { name: "Grant instance admin?" });
		await user.click(within(dialog).getByRole("button", { name: "Grant instance admin" }));

		await within(dialog).findByText("Role update unavailable.");
		expect(reads).toStrictEqual(["USER", "USER"]);
		expect(pages).toStrictEqual([0, 1]);
		const table = screen.getByRole("table", { name: "Application users", hidden: true });
		expect(within(table).getAllByRole("row", { hidden: true })).toHaveLength(27);
		expect(within(table).getByText("ada@example.com").closest("tr")?.textContent).toContain("User");

		await user.click(within(dialog).getByRole("button", { name: "Grant instance admin" }));
		await screen.findByText("Role updated to Instance admin");
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		await within(table).findByText("Instance admin", { exact: true });
		expect(reads).toStrictEqual(["USER", "USER", "APP_ADMIN", "APP_ADMIN"]);
		expect(pages).toStrictEqual([0, 1, 0, 1]);
		expect(changes).toStrictEqual([
			{ id: "7", body: { appRole: "APP_ADMIN" } },
			{ id: "7", body: { appRole: "APP_ADMIN" } },
		]);
		expect(within(table).getAllByRole("row")).toHaveLength(27);
		within(table).getByText("other-0@example.com");
		within(table).getByText("other-24@example.com");
	});

	it("offers only the sign-in providers this account is linked to when a role change is refused", async () => {
		let roleChanges = 0;
		server.use(
			http.patch("*/admin/users/:id", () => {
				roleChanges += 1;
				return stepUpRefusal();
			}),
			// The instance also offers GitHub, but this account has only ever signed in with GitLab.
			http.get("*/user", () =>
				HttpResponse.json({
					...currentUser,
					linkedProviders: [{ type: "GITLAB", serverUrl: "https://gitlab.lrz.de" }],
				}),
			),
		);

		renderRouteAt("/admin/users");
		const user = await chooseRowAction("Actions for Ada Lovelace", "Grant instance admin");
		await user.click(await screen.findByRole("button", { name: "Grant instance admin" }));

		const dialog = await screen.findByRole("dialog", { name: "Confirm access" });
		expect(dialog.textContent).toContain("sign-in from the last 5 minutes");
		expect(dialog.textContent).toContain("not a second factor");
		expect(screen.getByRole("button", { name: "Continue with GitLab" })).not.toBeNull();
		// Signing in with GitHub here would resolve a different account and end this session.
		expect(screen.queryByRole("button", { name: "Continue with GitHub" })).toBeNull();

		// The ask replaces the confirmation it came from rather than stacking on top of it.
		expect(screen.queryByText("Grant instance admin?")).toBeNull();

		await user.click(screen.getByRole("button", { name: "Close" }));
		await waitFor(() =>
			expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull(),
		);
		// Dismissing the ask abandons the action; it must not be replayed against the same refusal.
		expect(roleChanges).toBe(1);
	});

	it("asks for a fresh sign-in instead of reporting a force sign-out failure", async () => {
		server.use(http.delete("*/admin/users/:id/sessions", () => stepUpRefusal()));

		renderRouteAt("/admin/users");
		const user = await chooseRowAction("Actions for Ada Lovelace", "Force sign out");
		await user.click(await screen.findByRole("button", { name: "Force sign out" }));

		await screen.findByRole("dialog", { name: "Confirm access" });
		expect(screen.queryByText(/could not sign the user out/u)).toBeNull();
	});

	it("says nothing about a recent sign-in when the refusal is an ordinary one", async () => {
		server.use(
			http.patch("*/admin/users/:id", () =>
				HttpResponse.json(
					{
						status: 409,
						detail: "You cannot revoke the last admin. Grant admin to another account first.",
					},
					{ status: 409 },
				),
			),
		);

		renderRouteAt("/admin/users");
		const user = await chooseRowAction("Actions for Ada Lovelace", "Grant instance admin");
		await user.click(await screen.findByRole("button", { name: "Grant instance admin" }));

		const refusal = await screen.findByRole("alert");
		expect(refusal.textContent).toBe(
			"You cannot revoke the last admin. Grant admin to another account first.",
		);
		expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull();
	});
});

function serveSearchContinuation(
	accounts: AdminAccountView[],
	nextAccount: AdminAccountView,
	continuation: Deferred<Response>,
	unexpectedContinuation: Deferred<Response>,
) {
	const reads: number[] = [];
	let explicitRetry = false;
	server.use(
		// A healthy delivery state keeps the admin layout's own Retry out of this list's.
		http.get("*/admin/settings", () =>
			HttpResponse.json({ etag: "1", silentModeEngaged: false } satisfies InstanceSettings),
		),
		http.get("*/admin/users", async ({ request }) => {
			const page = Number(new URL(request.url).searchParams.get("page") ?? 0);
			reads.push(page);
			if (page === 0) {
				return HttpResponse.json(accounts);
			}
			if (reads.length === 2) {
				return continuation.promise;
			}
			// Hold an unsolicited retry so this proof cannot cause a request storm.
			if (!explicitRetry) {
				return unexpectedContinuation.promise;
			}
			return HttpResponse.json([nextAccount]);
		}),
	);
	return {
		reads,
		allowRetry: () => {
			explicitRetry = true;
		},
	};
}

describe("instance users list lifecycle", () => {
	it("retains partial search results after a failed next page and waits for an explicit retry", async () => {
		const accounts = [
			{
				id: 700,
				displayName: "Lifecycle match Ada",
				primaryEmail: "lifecycle-0@example.com",
				appRole: "USER",
				status: "ACTIVE",
			} satisfies AdminAccountView,
			...Array.from(
				{ length: 24 },
				(_, index) =>
					({
						id: 701 + index,
						displayName: `Lifecycle account ${index + 1}`,
						primaryEmail: `lifecycle-${index + 1}@example.com`,
						appRole: "USER",
						status: "ACTIVE",
					}) satisfies AdminAccountView,
			),
		];
		const nextAccount = {
			id: 725,
			displayName: "Lifecycle match Grace",
			primaryEmail: "lifecycle-grace@example.com",
			appRole: "USER",
			status: "ACTIVE",
		} satisfies AdminAccountView;
		const continuation = deferred<Response>();
		const unexpectedContinuation = deferred<Response>();
		const { reads, allowRetry } = serveSearchContinuation(
			accounts,
			nextAccount,
			continuation,
			unexpectedContinuation,
		);

		try {
			const { router } = renderRouteAtWithRouter("/admin/users");
			await screen.findByText("lifecycle-0@example.com", {}, ROUTE_RENDER_WAIT);
			const user = userEvent.setup();
			const search = screen.getByRole("searchbox", { name: "Search users" });
			await user.type(search, "match");
			await waitFor(() => expect(reads).toStrictEqual([0, 1]));
			expect(router.state.location.search.q).toBe("match");
			screen.getByText("Showing 1 of 25 loaded users");
			expect(screen.queryByText("No users found")).toBeNull();

			await act(async () => {
				continuation.resolve(
					HttpResponse.json(
						{ status: 503, detail: "Account search continuation unavailable." },
						{ status: 503 },
					),
				);
			});
			await waitFor(() =>
				expect(
					[
						reads.length > 2,
						screen.queryByText("We could not load users") !== null,
						screen.queryByRole("button", { name: /retry/iu }) !== null,
					].some(Boolean),
				).toBe(true),
			);
			expect.soft(screen.queryByText("lifecycle-0@example.com")).not.toBeNull();
			expect.soft(screen.queryByText("Showing 1 of 25 loaded users")).not.toBeNull();
			expect.soft(screen.queryByText("No users found")).toBeNull();
			expect(reads, "A failed continuation must wait for deliberate retry").toStrictEqual([0, 1]);
			const retry = within(screen.getByRole("main")).getByRole("button", {
				name: "Retry",
			});
			allowRetry();
			await user.click(retry);
			await screen.findByText("lifecycle-grace@example.com");
			expect(reads).toStrictEqual([0, 1, 1]);
			screen.getByText("Showing 2 of 26 loaded users");
			expect(router.state.location.search.q).toBe("match");
			expect(screen.queryByRole("button", { name: "Load more" })).toBeNull();
			await user.clear(search);
			await screen.findByText("Showing 26 users");
			await user.type(search, "no-such-account");
			await screen.findByText("No users found");
			expect(reads).toStrictEqual([0, 1, 1]);
		} finally {
			cleanup();
			continuation.resolve(HttpResponse.json([]));
			unexpectedContinuation.resolve(HttpResponse.json([]));
		}
	});

	it("discards refused account data and pending action intent until a new authorized read succeeds", async () => {
		const oldAccount = {
			id: 800,
			displayName: "Lifecycle Ada",
			primaryEmail: "lifecycle-ada@example.com",
			appRole: "USER",
			status: "ACTIVE",
		} satisfies AdminAccountView;
		const freshAccount = {
			id: 801,
			displayName: "Lifecycle Grace",
			primaryEmail: "lifecycle-grace@example.com",
			appRole: "USER",
			status: "ACTIVE",
		} satisfies AdminAccountView;
		let response: Response = HttpResponse.json([oldAccount]);
		const reads: { page: number; status: number }[] = [];
		let mutations = 0;
		server.use(
			http.get("*/admin/users", ({ request }) => {
				reads.push({
					page: Number(new URL(request.url).searchParams.get("page")),
					status: response.status,
				});
				return response.clone();
			}),
			http.patch("*/admin/users/:id", () => {
				mutations += 1;
				return HttpResponse.json(oldAccount);
			}),
		);
		const queryClient = renderRouteAt("/admin/users");
		await screen.findByText("lifecycle-ada@example.com", {}, ROUTE_RENDER_WAIT);
		await chooseRowAction("Actions for Lifecycle Ada", "Grant instance admin");
		await screen.findByRole("alertdialog", { name: "Grant instance admin?" });
		const refresh = async () => {
			await act(async () => {
				await queryClient.invalidateQueries({
					queryKey: adminListUsersInfiniteQueryKey({ query: { size: 25 } }),
					exact: true,
				});
			});
		};
		response = HttpResponse.json(
			{ status: 403, detail: "Account read unavailable." },
			{ status: 403 },
		);
		await refresh();
		await screen.findByText("You do not have access to users");
		// A dialog whose target is cleared stays mounted until its exit transition ends.
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect.soft(screen.queryByText("Showing 1 user"), "No stale loaded count after 403").toBeNull();
		expect
			.soft(screen.queryByText("lifecycle-ada@example.com"), "No refused email after 403")
			.toBeNull();
		expect
			.soft(screen.queryByRole("alertdialog"), "No refused account action after 403")
			.toBeNull();
		expect
			.soft(screen.queryByText("Lifecycle Ada"), "No refused account identity after 403")
			.toBeNull();
		response = HttpResponse.json(
			{ status: 503, detail: "Account read unavailable." },
			{ status: 503 },
		);
		await refresh();
		await screen.findByText("We could not load users");
		expect.soft(screen.queryByText("Showing 1 user"), "No stale loaded count after 503").toBeNull();
		expect
			.soft(screen.queryByText("lifecycle-ada@example.com"), "No refused email after 503")
			.toBeNull();
		expect
			.soft(screen.queryByRole("alertdialog"), "No refused account action after 503")
			.toBeNull();
		expect
			.soft(screen.queryByText("Lifecycle Ada"), "No refused account identity after 503")
			.toBeNull();
		response = HttpResponse.json([freshAccount]);
		await refresh();
		await screen.findByText("lifecycle-grace@example.com");
		expect
			.soft(screen.queryByText("lifecycle-ada@example.com"), "No refused email after 200")
			.toBeNull();
		expect
			.soft(screen.queryByRole("alertdialog"), "No refused account action after 200")
			.toBeNull();
		expect
			.soft(screen.queryByText("Lifecycle Ada"), "No refused account identity after 200")
			.toBeNull();

		expect(reads).toStrictEqual([
			{ page: 0, status: 200 },
			{ page: 0, status: 403 },
			{ page: 0, status: 503 },
			{ page: 0, status: 200 },
		]);
		expect(mutations).toBe(0);
	});
});

/** Answers the first users read with each status in turn, after a healthy admin layout. */
function serveFirstReads(statuses: readonly number[], account: AdminAccountView) {
	const reads: number[] = [];
	server.use(
		http.get("*/admin/settings", () =>
			HttpResponse.json({ etag: "1", silentModeEngaged: false } satisfies InstanceSettings),
		),
		http.get("*/admin/users", () => {
			const status = statuses[reads.length] ?? 200;
			reads.push(status);
			return status === 200
				? HttpResponse.json([account])
				: HttpResponse.json({ status, detail: "Account read unavailable." }, { status });
		}),
	);
	return reads;
}

const coldAccount = {
	id: 900,
	displayName: "Cold Grace",
	primaryEmail: "cold-grace@example.com",
	appRole: "USER",
	status: "ACTIVE",
} satisfies AdminAccountView;

describe("instance users first read", () => {
	it("explains a refused first read without offering a retry or a table", async () => {
		const reads = serveFirstReads([403], coldAccount);
		renderRouteAt("/admin/users");

		await screen.findByText("You do not have access to users", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
		expect(screen.queryByText(/reload the page/iu)).toBeNull();
		expect(screen.queryByRole("table", { name: "Application users" })).toBeNull();
		expect(screen.queryByText(/^Showing /u)).toBeNull();
		expect(reads).toStrictEqual([403]);
	});

	it("offers Retry after an outage on the first read and lists the users it then reads", async () => {
		const reads = serveFirstReads([503], coldAccount);
		renderRouteAt("/admin/users");

		await screen.findByText("We could not load users", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByText(/reload the page/iu)).toBeNull();
		expect(screen.queryByText(/^Showing /u)).toBeNull();
		expect(reads).toStrictEqual([503]);
		await userEvent.setup().click(screen.getByRole("button", { name: "Retry" }));

		const table = await screen.findByRole("table", { name: "Application users" });
		await within(table).findByText("cold-grace@example.com");
		screen.getByText("Showing 1 user");
		expect(screen.queryByText("We could not load users")).toBeNull();
		expect(reads).toStrictEqual([503, 200]);
	});
});
