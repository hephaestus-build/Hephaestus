import type { QueryClient, QueryKey } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, describe, expect, it } from "vitest";

import {
	getScanQueryKey,
	getScanReportQueryKey,
	listScanReportItemsInfiniteQueryKey,
	listScanReportProblemsInfiniteQueryKey,
	listScansQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

import {
	adminHandlers,
	ITEMS_PAGE,
	PROBLEMS,
	SCAN_ID,
	scanPage,
	wireDetail,
	wireReport,
	wireRepository,
	wireScan,
	wireScanIn,
} from "./-scan-server";

beforeAll(
	async () =>
		loadRouteCode(
			"/w/acme/admin/practices/scans",
			"/w/acme/admin/practices/scans/a0000000-0000-4000-8000-000000000001",
		),
	ROUTE_RENDER_WAIT.timeout,
);
const LIST = "/w/acme/admin/practices/scans";
const REPORT = `${LIST}/${SCAN_ID}`;
const path = { workspaceSlug: "acme", scanId: SCAN_ID };
const PROBLEM = "A failed upload is logged and then ignored.";
type Read = "detail" | "report" | "items" | "problems" | "list";
const keys: Record<Read, QueryKey> = {
	detail: getScanQueryKey({ path }),
	report: getScanReportQueryKey({ path }),
	items: listScanReportItemsInfiniteQueryKey({ path, query: { size: 50, status: undefined } }),
	problems: listScanReportProblemsInfiniteQueryKey({
		path,
		query: { size: 50, baselineState: undefined },
	}),
	list: listScansQueryKey({ path: { workspaceSlug: "acme" }, query: { page: 0, size: 20 } }),
};
function serve() {
	const status: Record<Read, number> = {
		detail: 200,
		report: 200,
		items: 200,
		problems: 200,
		list: 200,
	};
	const reads: Record<Read, number> = { detail: 0, report: 0, items: 0, problems: 0, list: 0 };
	const respond = (kind: Read, body: Parameters<typeof HttpResponse.json>[0]) => {
		reads[kind] += 1;
		return status[kind] === 200
			? HttpResponse.json(body)
			: HttpResponse.json({ status: status[kind] }, { status: status[kind] });
	};
	server.use(
		...adminHandlers(),
		http.get("*/workspaces/:workspaceSlug/practices/scans", () =>
			respond("list", scanPage([wireScan()])),
		),
		http.get("*/workspaces/:workspaceSlug/practices/scans/:scanId", () =>
			respond("detail", wireDetail(wireScanIn("RUNNING"))),
		),
		http.get("*/workspaces/:workspaceSlug/practices/scans/:scanId/report", () =>
			respond("report", wireReport(wireScanIn("RUNNING"))),
		),
		http.get("*/workspaces/:workspaceSlug/practices/scans/:scanId/report/items", () =>
			respond("items", ITEMS_PAGE),
		),
		http.get("*/workspaces/:workspaceSlug/practices/scans/:scanId/report/problems", () =>
			respond("problems", { content: PROBLEMS }),
		),
	);
	return { status, reads };
}
async function refresh(client: QueryClient, kind: Read) {
	await act(async () => {
		await client.refetchQueries({ queryKey: keys[kind], exact: true });
	});
}

describe("scan read ownership", () => {
	it.each(["detail", "report"] as const)(
		"retains a readable %s after 503, withdraws on refusal and requires a fresh permitted read",
		async (kind) => {
			const { status, reads } = serve();
			const user = userEvent.setup();
			const { queryClient } = renderRouteAtWithRouter(REPORT);
			await screen.findByText(PROBLEM, undefined, ROUTE_RENDER_WAIT);
			await user.click(screen.getByRole("button", { name: "Cancel scan" }));
			await screen.findByRole("alertdialog");
			status[kind] = 503;
			await refresh(queryClient, kind);
			await screen.findByText("Scan details may be out of date");
			screen.getByText(PROBLEM);
			screen.getByRole("alertdialog");
			await user.click(screen.getByRole("button", { name: "Keep scan running" }));
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
			status[kind] = 200;
			const previous = reads[kind];
			await user.click(await screen.findByRole("button", { name: "Retry" }));
			await waitFor(() => expect(screen.queryByText("Scan details may be out of date")).toBeNull());
			expect(reads[kind]).toBeGreaterThan(previous);
			await user.click(screen.getByRole("button", { name: "Cancel scan" }));
			status[kind] = 403;
			await refresh(queryClient, kind);
			await screen.findByText("You do not have access to this scan");
			expect(screen.queryByText(PROBLEM)).toBeNull();
			expect(screen.queryByRole("alertdialog")).toBeNull();
			expect(screen.queryByRole("button", { name: "Cancel scan" })).toBeNull();
			status[kind] = 503;
			await refresh(queryClient, kind);
			expect(screen.queryByText(PROBLEM)).toBeNull();
			expect(screen.queryByRole("button", { name: "Cancel scan" })).toBeNull();
			status[kind] = 200;
			await user.click(await screen.findByRole("button", { name: "Retry" }));
			await screen.findByText(PROBLEM);
			screen.getByRole("button", { name: "Cancel scan" });
		},
	);

	it.each([
		{
			kind: "problems",
			tab: "Problems",
			tableName: "Problems in this scan",
			warning: "Problems may be out of date",
		},
		{
			kind: "items",
			tab: "Files",
			tableName: "Files and practices",
			warning: "Files and practices may be out of date",
		},
	] as const)(
		"keeps the readable $kind list on 503 but never resurrects refused rows",
		async ({ kind, tab, tableName, warning }) => {
			const { status } = serve();
			const user = userEvent.setup();
			const { queryClient } = renderRouteAtWithRouter(REPORT);
			await screen.findByText(PROBLEM, undefined, ROUTE_RENDER_WAIT);
			await user.click(screen.getByRole("tab", { name: tab }));
			await screen.findByRole("table", { name: tableName });
			status[kind] = 503;
			await refresh(queryClient, kind);
			await screen.findByText(warning);
			screen.getByRole("table", { name: tableName });
			status[kind] = 403;
			await refresh(queryClient, kind);
			await waitFor(() => expect(screen.queryByRole("table", { name: tableName })).toBeNull());
			screen.getByRole("heading", { name: "ls1intum/hephaestus" });
			status[kind] = 503;
			await refresh(queryClient, kind);
			expect(screen.queryByRole("table", { name: tableName })).toBeNull();
			status[kind] = 200;
			await user.click(await screen.findByRole("button", { name: "Retry" }));
			await screen.findByRole("table", { name: tableName });
		},
	);

	it("retains the scans list on a failed refresh and clears refused rows until recovery", async () => {
		const { status } = serve();
		const user = userEvent.setup();
		const { queryClient } = renderRouteAtWithRouter(LIST);
		await screen.findByRole("link", { name: "ls1intum/hephaestus" }, ROUTE_RENDER_WAIT);
		status.list = 503;
		await refresh(queryClient, "list");
		await screen.findByText("Scans may be out of date");
		screen.getByRole("link", { name: "ls1intum/hephaestus" });
		status.list = 403;
		await refresh(queryClient, "list");
		await screen.findByText("You do not have access to scans");
		expect(screen.queryByRole("link", { name: "ls1intum/hephaestus" })).toBeNull();
		status.list = 503;
		await refresh(queryClient, "list");
		expect(screen.queryByRole("link", { name: "ls1intum/hephaestus" })).toBeNull();
		status.list = 200;
		await user.click(await screen.findByRole("button", { name: "Retry" }));
		await screen.findByRole("link", { name: "ls1intum/hephaestus" });
	});

	it("keeps a late cancellation on its submitted scan rather than overwriting the next report", async () => {
		serve();
		const held = deferred();
		const otherId = "a0000000-0000-4000-8000-000000000099";
		let patches = 0;
		server.use(
			http.patch("*/workspaces/:workspaceSlug/practices/scans/:scanId/status", async () => {
				patches += 1;
				await held.promise;
				return HttpResponse.json(wireDetail(wireScanIn("CANCELLED")));
			}),
			http.get(`*/workspaces/:workspaceSlug/practices/scans/${otherId}`, () =>
				HttpResponse.json(
					wireDetail(wireScan({ id: otherId, repository: wireRepository("301", "artemis") })),
				),
			),
			http.get(`*/workspaces/:workspaceSlug/practices/scans/${otherId}/report`, () =>
				HttpResponse.json(
					wireReport(wireScan({ id: otherId, repository: wireRepository("301", "artemis") })),
				),
			),
		);
		const user = userEvent.setup();
		const { router, queryClient } = renderRouteAtWithRouter(REPORT);
		try {
			await user.click(
				await screen.findByRole("button", { name: "Cancel scan" }, ROUTE_RENDER_WAIT),
			);
			await user.click(
				within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel scan" }),
			);
			await waitFor(() => expect(patches).toBe(1));
			await act(async () => {
				await router.navigate({
					to: "/w/$workspaceSlug/admin/practices/scans/$scanId",
					params: { workspaceSlug: "acme", scanId: otherId },
				});
			});
			await screen.findByRole("heading", { name: "ls1intum/artemis" });
			await act(async () => {
				held.resolve();
				await held.promise;
			});
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			screen.getByRole("heading", { name: "ls1intum/artemis" });
			screen.getByRole("button", { name: "Export SARIF" });
			expect(screen.queryByText("Cancelled", { exact: true })).toBeNull();
		} finally {
			held.resolve();
		}
	});
	it("does not restore refused evidence from a late cancellation response", async () => {
		const { status } = serve();
		const held = deferred();
		let patches = 0;
		server.use(
			http.patch("*/workspaces/:workspaceSlug/practices/scans/:scanId/status", async () => {
				patches += 1;
				await held.promise;
				return HttpResponse.json(wireDetail(wireScanIn("CANCELLED")));
			}),
		);
		const user = userEvent.setup();
		const { queryClient } = renderRouteAtWithRouter(REPORT);
		try {
			await user.click(
				await screen.findByRole("button", { name: "Cancel scan" }, ROUTE_RENDER_WAIT),
			);
			await user.click(
				within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel scan" }),
			);
			await waitFor(() => expect(patches).toBe(1));
			status.detail = 403;
			await refresh(queryClient, "detail");
			await screen.findByText("You do not have access to this scan");
			status.detail = 503;
			await act(async () => {
				held.resolve();
				await held.promise;
			});
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			await waitFor(() =>
				expect(queryClient.isFetching({ queryKey: keys.detail, exact: true })).toBe(0),
			);
			expect(screen.queryByRole("heading", { name: "ls1intum/hephaestus" })).toBeNull();
			expect(screen.queryByRole("button", { name: "Export SARIF" })).toBeNull();
			status.detail = 200;
			await user.click(await screen.findByRole("button", { name: "Retry" }));
			await screen.findByRole("heading", { name: "ls1intum/hephaestus" });
			screen.getByRole("button", { name: "Cancel scan" });
		} finally {
			held.resolve();
		}
	});
});
