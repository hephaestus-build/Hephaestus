import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { adminListAuthEventsInfiniteQueryKey } from "@/api/@tanstack/react-query.gen";
import type { AuthEventView, ConfigAuditEntryView } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { apiPathOf } from "@/test/api-path";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// The real routes mount the shared admin chrome and lazily imported route modules.
vi.setConfig({ testTimeout: 40_000 });

function auditPage<T>(content: T[]) {
	return { content };
}

function settingsChange(workspaceId: number, operator: string, newStatus: string) {
	return {
		id: 60 + workspaceId,
		occurredAt: "2026-10-01T09:00:00Z",
		action: "UPDATED",
		entityType: "WORKSPACE_STATUS",
		entityId: String(workspaceId),
		actorKind: "USER",
		actorAccountId: workspaceId,
		actor: { id: workspaceId, displayName: operator },
		workspaceId,
		elevatedViaInstanceAdmin: false,
		changedKeys: ["status"],
		oldValue: '{"status":"ACTIVE"}',
		newValue: `{"status":"${newStatus}"}`,
	} satisfies Wire<ConfigAuditEntryView>;
}

function accessEvent(id: number, name: string, ipAddress: string) {
	return {
		id,
		occurredAt: "2026-10-01T09:00:00Z",
		eventType: "LOGIN",
		result: "SUCCESS",
		elevatedViaInstanceAdmin: false,
		accountId: id,
		account: { id, displayName: name },
		ipAddress,
	} satisfies Wire<AuthEventView>;
}

const ADA = accessEvent(7, "Identity Ada", "192.0.2.17");
const GRACE = accessEvent(8, "Identity Grace", "192.0.2.18");

/** Each Access filter has its own rows; `answer` is what the unfiltered log gives next. */
function serveAccessByAccount() {
	const served: { accountId: string | null; status: number }[] = [];
	let unfiltered: { status: number } | undefined;
	server.use(
		http.get("*/admin/audit", ({ request }) => {
			const accountId = new URL(request.url).searchParams.get("accountId");
			const status = accountId === null ? (unfiltered?.status ?? 200) : 200;
			served.push({ accountId, status });
			if (status !== 200) {
				return HttpResponse.json({ status, detail: "Audit read unavailable." }, { status });
			}
			return HttpResponse.json(auditPage(accountId === null ? [ADA] : [GRACE]));
		}),
	);
	return {
		served,
		failUnfiltered: (status: number) => {
			unfiltered = { status };
		},
	};
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

describe("audit detail follows the read it was opened from", () => {
	it("closes workspace A's detail when B's already loaded log returns", async () => {
		const user = userEvent.setup();
		const paths: string[] = [];
		server.use(
			http.get("*/workspaces/acme/config-audit", ({ request }) => {
				paths.push(apiPathOf(request.url));
				return HttpResponse.json(auditPage([settingsChange(1, "Acme operator", "SUSPENDED")]));
			}),
			http.get("*/workspaces/other/config-audit", ({ request }) => {
				paths.push(apiPathOf(request.url));
				return HttpResponse.json(auditPage([settingsChange(2, "Other operator", "ARCHIVED")]));
			}),
		);
		const { router } = renderRouteAtWithRouter("/w/other/admin/audit");
		await screen.findByText("Other operator", undefined, ROUTE_RENDER_WAIT);
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/admin/audit",
				params: { workspaceSlug: "acme" },
			});
		});
		await user.click(await screen.findByRole("button", { name: /^View details for/u }));
		within(await screen.findByRole("dialog")).getByText("SUSPENDED");

		await act(async () => {
			router.history.back();
		});
		await waitFor(() => expect(router.state.location.pathname).toBe("/w/other/admin/audit"));
		// B answers from its cache, so its rows are ready at once; A's detail must not stand over them.
		await screen.findByText("Other operator");
		expect.soft(screen.queryByRole("dialog")).toBeNull();
		expect.soft(screen.queryByText("SUSPENDED", { exact: true })).toBeNull();
		expect.soft(screen.queryByText("Acme operator")).toBeNull();
		expect(paths.slice(0, 2)).toStrictEqual([
			"/workspaces/other/config-audit",
			"/workspaces/acme/config-audit",
		]);
	});

	it("closes an Access detail when another loaded filter returns, and keeps it through a failed refresh", async () => {
		const user = userEvent.setup();
		const { served, failUnfiltered } = serveAccessByAccount();
		const { router, queryClient } = renderRouteAtWithRouter("/admin/audit");
		await screen.findByText("Identity Ada", undefined, ROUTE_RENDER_WAIT);
		await act(async () => {
			await router.navigate({ to: "/admin/audit", search: { tab: "signins", accountId: 8 } });
		});
		await screen.findByText("Account: Identity Grace");
		await user.click(screen.getByRole("button", { name: /^View details for/u }));
		within(await screen.findByRole("dialog")).getByText("192.0.2.18");

		await act(async () => {
			await router.navigate({ to: "/admin/audit", search: { tab: "signins" } });
		});
		// The unfiltered log answers from its cache; Grace's detail belongs to the other filter.
		await screen.findByText("Identity Ada");
		expect.soft(screen.queryByRole("dialog")).toBeNull();
		expect.soft(screen.queryByText("192.0.2.18")).toBeNull();

		// The same read failing to refresh keeps its rows and the detail opened from them.
		await user.click(screen.getByRole("button", { name: /^View details for/u }));
		within(await screen.findByRole("dialog")).getByText("192.0.2.17");
		failUnfiltered(503);
		await act(async () => {
			await queryClient.invalidateQueries({
				queryKey: adminListAuthEventsInfiniteQueryKey({ query: { size: 50 } }),
				exact: true,
			});
		});
		await screen.findByText("We could not refresh the audit log");
		within(screen.getByRole("dialog")).getByText("192.0.2.17");
		// The open detail is modal, so the retained rows sit behind it, out of the accessibility tree.
		within(screen.getByRole("table", { hidden: true })).getByText("Identity Ada");
		expect(served.map(({ accountId }) => accountId)).toStrictEqual([null, "8", null, null]);
		expect(served.at(-1)?.status).toBe(503);
	});
});
