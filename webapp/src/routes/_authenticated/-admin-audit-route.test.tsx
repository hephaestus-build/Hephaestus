import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, assert, beforeEach, describe, expect, it, vi } from "vitest";

import { adminListAuthEventsInfiniteQueryKey } from "@/api/@tanstack/react-query.gen";
import type { AuthEventView, ConfigAuditEntryView } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import * as download from "@/lib/download";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

beforeAll(
	async () => loadRouteCode("/admin/audit", "/w/acme/admin/audit"),
	ROUTE_RENDER_WAIT.timeout,
);

const authEvent = {
	id: 31,
	occurredAt: "2026-10-01T09:00:00Z",
	eventType: "LOGIN",
	result: "SUCCESS",
	elevatedViaInstanceAdmin: false,
	accountId: 7,
	account: { id: 7, displayName: "Audit-only Ada", email: "audit-ada@example.com" },
	ipAddress: "192.0.2.137",
} satisfies Wire<AuthEventView>;

const recoveredEvent = {
	...authEvent,
	id: 32,
	account: { ...authEvent.account, displayName: "Audit-only Grace" },
	ipAddress: "192.0.2.138",
} satisfies Wire<AuthEventView>;

const configEntry = {
	id: 41,
	occurredAt: "2026-10-01T09:00:00Z",
	action: "UPDATED",
	entityType: "WORKSPACE_STATUS",
	entityId: "1",
	actorKind: "USER",
	actorAccountId: 7,
	actor: { id: 7, displayName: "Acme audit operator", email: "acme-audit@example.com" },
	workspaceId: 1,
	elevatedViaInstanceAdmin: false,
	changedKeys: ["status"],
	oldValue: '{"status":"ACTIVE"}',
	newValue: '{"status":"SUSPENDED"}',
} satisfies Wire<ConfigAuditEntryView>;

function auditPage<T>(content: T[]) {
	return { content };
}

function serveAuthEvents(answers: readonly (Wire<AuthEventView>[] | { status: number })[]) {
	const served: { path: string; accountId: string | null; status: number }[] = [];
	server.use(
		http.get("*/admin/audit/count", () => HttpResponse.json({ total: 1 })),
		http.get("*/admin/audit", ({ request }) => {
			const answer = answers[served.length];
			assert(answer, "Auth audit read exceeded the prepared responses");
			const url = new URL(request.url);
			const status = "status" in answer ? answer.status : 200;
			served.push({ path: url.pathname, accountId: url.searchParams.get("accountId"), status });
			return "status" in answer
				? HttpResponse.json({ status, detail: "Audit read refused or unavailable." }, { status })
				: HttpResponse.json(auditPage(answer));
		}),
	);
	return served;
}

beforeEach(() => {
	server.use(
		http.get("*/admin/settings", () =>
			HttpResponse.json({ silentModeEngaged: false, etag: '"0"' }),
		),
		http.get("*/admin/workspaces", () => HttpResponse.json([])),
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other", { id: 2 })]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
	);
});

describe("audit read lifecycle", () => {
	it("closes workspace A's selected settings detail before workspace B answers", async () => {
		const user = userEvent.setup();
		const nextWorkspace = deferred<Response>();
		const paths: string[] = [];
		server.use(
			http.get("*/workspaces/acme/config-audit", ({ request }) => {
				paths.push(new URL(request.url).pathname);
				return HttpResponse.json(auditPage([configEntry]));
			}),
			http.get("*/workspaces/other/config-audit", async ({ request }) => {
				paths.push(new URL(request.url).pathname);
				return nextWorkspace.promise;
			}),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/admin/audit");
		await user.click(
			await screen.findByRole("button", { name: /^View details for/u }, ROUTE_RENDER_WAIT),
		);
		const detail = await screen.findByRole("dialog");
		within(detail).getByText("Acme audit operator");
		within(detail).getByText("SUSPENDED");

		try {
			await act(async () => {
				await router.navigate({
					to: "/w/$workspaceSlug/admin/audit",
					params: { workspaceSlug: "other" },
				});
			});
			await waitFor(() =>
				expect(paths).toStrictEqual([
					"/workspaces/acme/config-audit",
					"/workspaces/other/config-audit",
				]),
			);
			expect(router.state.location.pathname).toBe("/w/other/admin/audit");
			// Soft assertions let the same run also verify that B's real response arrives independently.
			expect.soft(screen.queryByRole("dialog")).toBeNull();
			expect.soft(screen.queryByText("SUSPENDED", { exact: true })).toBeNull();

			nextWorkspace.resolve(
				HttpResponse.json(
					auditPage([
						{
							...configEntry,
							id: 42,
							workspaceId: 2,
							entityId: "2",
							actor: { ...configEntry.actor, displayName: "Other audit operator" },
							oldValue: '{"status":"SUSPENDED"}',
							newValue: '{"status":"ACTIVE"}',
						},
					]),
				),
			);
			await screen.findByText("Other audit operator");
			expect.soft(screen.queryByRole("dialog")).toBeNull();
			expect.soft(screen.queryByText("Acme audit operator")).toBeNull();
		} finally {
			nextWorkspace.resolve(HttpResponse.json(auditPage([])));
		}
	});

	it("keeps authorized Access rows during a failed refresh and recovers through Retry", async () => {
		const user = userEvent.setup();
		const served = serveAuthEvents([[authEvent], { status: 503 }, [recoveredEvent]]);
		const { queryClient } = renderRouteAtWithRouter("/admin/audit");
		await screen.findByText("Audit-only Ada", undefined, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("table")).not.toBeNull();
		await act(async () => {
			await queryClient.invalidateQueries({
				queryKey: adminListAuthEventsInfiniteQueryKey({ query: { size: 50 } }),
				exact: true,
			});
		});

		expect(served.map(({ status }) => status)).toStrictEqual([200, 503]);
		await screen.findByRole("button", { name: "Retry" });
		expect.soft(screen.queryByRole("table")).not.toBeNull();
		expect.soft(screen.queryByText("Audit-only Ada")).not.toBeNull();
		expect.soft(screen.queryByRole("alert")).not.toBeNull();
		await user.click(screen.getByRole("button", { name: "Retry" }));

		await screen.findByText("Audit-only Grace");
		expect(screen.queryByText("Audit-only Ada")).toBeNull();
		expect(screen.getByRole("table")).not.toBeNull();
		expect(served).toStrictEqual([
			{ path: "/admin/audit", accountId: null, status: 200 },
			{ path: "/admin/audit", accountId: null, status: 503 },
			{ path: "/admin/audit", accountId: null, status: 200 },
		]);
	});

	it("does not revive refused Access identity, counts, export or detail after a later outage", async () => {
		const user = userEvent.setup();
		const served = serveAuthEvents([
			[authEvent],
			{ status: 403 },
			{ status: 503 },
			[recoveredEvent],
		]);
		const { queryClient } = renderRouteAtWithRouter("/admin/audit?accountId=7");
		await user.click(
			await screen.findByRole("button", { name: /^View details for/u }, ROUTE_RENDER_WAIT),
		);
		within(await screen.findByRole("dialog")).getByText("192.0.2.137");
		const refresh = async () => {
			await act(async () => {
				await queryClient.invalidateQueries({
					queryKey: adminListAuthEventsInfiniteQueryKey({ query: { size: 50, accountId: 7 } }),
					exact: true,
				});
			});
		};

		await refresh();
		expect(served.map(({ status }) => status)).toStrictEqual([200, 403]);
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		expect(screen.queryByRole("table")).toBeNull();
		expect.soft(screen.queryByText("Account: Audit-only Ada")).toBeNull();
		expect.soft(screen.queryByText("1 event matches your filters.")).toBeNull();
		expect
			.soft(screen.getByRole<HTMLButtonElement>("button", { name: "Export CSV" }).disabled)
			.toBe(true);

		await refresh();
		expect(served.map(({ status }) => status)).toStrictEqual([200, 403, 503]);
		expect(screen.queryByRole("table")).toBeNull();
		expect(screen.queryByRole("dialog")).toBeNull();
		expect.soft(screen.queryByText("Account: Audit-only Ada")).toBeNull();
		expect.soft(screen.queryByText("1 event matches your filters.")).toBeNull();
		expect
			.soft(screen.getByRole<HTMLButtonElement>("button", { name: "Export CSV" }).disabled)
			.toBe(true);

		await refresh();
		await screen.findByText("Account: Audit-only Grace");
		expect.soft(screen.queryByRole("dialog")).toBeNull();
		expect.soft(screen.queryByText("192.0.2.137")).toBeNull();
		expect(served).toStrictEqual(
			[200, 403, 503, 200].map((status) => ({
				path: "/admin/audit",
				accountId: "7",
				status,
			})),
		);
	});
});

/** The Access tab filtered to one account, once its events have loaded and the export can start. */
async function exportButtonForAccount7() {
	const { queryClient } = renderRouteAtWithRouter("/admin/audit?accountId=7");
	const button = await screen.findByRole<HTMLButtonElement>(
		"button",
		{ name: "Export CSV" },
		ROUTE_RENDER_WAIT,
	);
	await waitFor(() => expect(button.disabled).toBe(false));
	return { button, queryClient };
}

describe("Access export", () => {
	it("reads the filtered export only on request, and saves the CSV once the server answers", async () => {
		const user = userEvent.setup();
		serveAuthEvents([[authEvent]]);
		const exportAnswer = deferred<Response>();
		const exported: (string | null)[] = [];
		server.use(
			http.get("*/admin/audit/export", async ({ request }) => {
				exported.push(new URL(request.url).searchParams.get("accountId"));
				return exportAnswer.promise;
			}),
		);
		const { button, queryClient } = await exportButtonForAccount7();
		// The browser download itself is `download.test.ts`'s; here only what the page hands it.
		const save = vi.spyOn(download, "saveTextFile").mockReturnValue(undefined);
		const csv = "id,eventType,result\n31,LOGIN,SUCCESS\n";
		try {
			expect(exported).toStrictEqual([]);
			expect(save).not.toHaveBeenCalled();

			await user.click(button);
			await waitFor(() => expect(exported).toStrictEqual(["7"]));
			expect(save).not.toHaveBeenCalled();

			exportAnswer.resolve(HttpResponse.text(csv, { headers: { "Content-Type": "text/csv" } }));
			await waitFor(() => expect(save).toHaveBeenCalledOnce());
			expect(save).toHaveBeenCalledWith(
				csv,
				expect.stringMatching(/^audit-log-\d{4}-\d{2}-\d{2}\.csv$/u),
				"text/csv;charset=utf-8;",
			);
		} finally {
			exportAnswer.resolve(
				HttpResponse.json({ status: 503, detail: "Export unavailable." }, { status: 503 }),
			);
			try {
				await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			} finally {
				save.mockRestore();
			}
		}
	});

	it("says the export failed and saves nothing when the server refuses it", async () => {
		const user = userEvent.setup();
		serveAuthEvents([[authEvent]]);
		server.use(
			http.get("*/admin/audit/export", () =>
				HttpResponse.json({ status: 503, detail: "Export unavailable." }, { status: 503 }),
			),
		);
		const save = vi.spyOn(download, "saveTextFile").mockReturnValue(undefined);
		try {
			const { button } = await exportButtonForAccount7();
			await user.click(button);

			await screen.findByText("We could not export the audit log. Try again.");
			expect(save).not.toHaveBeenCalled();
		} finally {
			save.mockRestore();
		}
	});
});
