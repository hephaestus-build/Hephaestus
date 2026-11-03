import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ScanReport, ScanSummary } from "@/api/types.gen";
import { SCAN_POLL_MS } from "@/components/admin/practice-scans/scan-display";
import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { reviewHandlers } from "@/routes/_authenticated/w/$workspaceSlug/admin/practices/reviews/-reviews-server";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

import {
	adminHandlers,
	wireScanIn,
	failingUntilRecovered,
	ITEMS_PAGE,
	PROBLEMS,
	SCAN_ID,
	wireDetail,
	wireReport,
	wireScan,
	type WireProblem,
} from "./-scan-server";

// Route mounting loads the admin layout and lazy modules; allow time for the first render.
vi.setConfig({ testTimeout: 20_000 });

const REPORT_URL = `/w/acme/admin/practices/scans/${SCAN_ID}`;

interface ReportServer {
	scan: () => Wire<ScanSummary>;
	report?: (scan: Wire<ScanSummary>) => Wire<ScanReport>;
	problems?: (url: URL) => WireProblem[];
}

function serveReport({ scan, report = (current) => wireReport(current), problems }: ReportServer) {
	const reads = { detail: 0, report: 0, items: [] as URL[], problems: [] as URL[] };
	server.use(
		...adminHandlers(),
		http.get("*/workspaces/:workspaceSlug/practices/scans/:scanId", () => {
			reads.detail += 1;
			return HttpResponse.json(wireDetail(scan()));
		}),
		http.get("*/workspaces/:workspaceSlug/practices/scans/:scanId/report", () => {
			reads.report += 1;
			return HttpResponse.json(report(scan()));
		}),
		http.get("*/workspaces/:workspaceSlug/practices/scans/:scanId/report/items", ({ request }) => {
			reads.items.push(new URL(request.url));
			return HttpResponse.json(ITEMS_PAGE);
		}),
		http.get(
			"*/workspaces/:workspaceSlug/practices/scans/:scanId/report/problems",
			({ request }) => {
				const url = new URL(request.url);
				reads.problems.push(url);
				const state = url.searchParams.get("baselineState");
				const all = problems?.(url) ?? PROBLEMS;
				return HttpResponse.json({
					content: state === null ? all : all.filter((entry) => entry.baselineState === state),
				});
			},
		),
	);
	return reads;
}

function figure(counts: HTMLElement, label: string): string | null | undefined {
	const term = within(counts).getByText(label, { selector: "dt" });
	return term.nextElementSibling?.textContent;
}

describe("scan report route", () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it("reads an active scan every five seconds and stops once it is terminal", async () => {
		vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
		try {
			let status: ScanSummary["status"] = "RUNNING";
			const reads = serveReport({
				scan: () => wireScanIn(status),
			});

			renderRouteAtWithRouter(REPORT_URL);
			await screen.findByRole("heading", { name: "ls1intum/hephaestus" }, ROUTE_RENDER_WAIT);
			screen.getByText(/1 practice and file pairs finished so far/u);
			expect(screen.queryByText(/%/u)).toBeNull();
			const first = reads.report;

			await vi.advanceTimersByTimeAsync(SCAN_POLL_MS);
			await vi.waitFor(() => expect(reads.report).toBeGreaterThan(first));
			screen.getByRole("heading", { name: "ls1intum/hephaestus" });

			status = "COMPLETE";
			const beforeTerminal = reads.report;
			await vi.advanceTimersByTimeAsync(SCAN_POLL_MS);
			await vi.waitFor(() => expect(reads.report).toBeGreaterThan(beforeTerminal));
			await screen.findByRole("button", { name: "Export SARIF" });
			expect(screen.queryByRole("button", { name: "Cancel scan" })).toBeNull();

			const settled = { detail: reads.detail, report: reads.report };
			await vi.advanceTimersByTimeAsync(SCAN_POLL_MS * 3);
			expect({ detail: reads.detail, report: reads.report }).toStrictEqual(settled);
		} finally {
			vi.useRealTimers();
		}
	});

	it("cancels the whole scan through its status", async () => {
		let status: ScanSummary["status"] = "RUNNING";
		serveReport({
			scan: () => wireScanIn(status, { stopReason: "CANCELLED_BY_ADMIN" }),
		});
		const patches: unknown[] = [];
		server.use(
			http.patch(
				"*/workspaces/:workspaceSlug/practices/scans/:scanId/status",
				async ({ request }) => {
					patches.push(await request.json());
					status = "CANCELLED";
					return HttpResponse.json(
						wireDetail(wireScan({ status: "CANCELLED", stopReason: "CANCELLED_BY_ADMIN" })),
					);
				},
			),
		);
		const user = userEvent.setup();

		renderRouteAtWithRouter(REPORT_URL);
		await user.click(await screen.findByRole("button", { name: "Cancel scan" }, ROUTE_RENDER_WAIT));
		const dialog = within(await screen.findByRole("alertdialog"));
		expect(patches).toStrictEqual([]);
		await user.click(dialog.getByRole("button", { name: "Keep scan running" }));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect(patches).toStrictEqual([]);
		await user.click(screen.getByRole("button", { name: "Cancel scan" }));
		await user.click(
			within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel scan" }),
		);

		await screen.findByRole("button", { name: "Export SARIF" });
		expect(patches).toStrictEqual([{ status: "CANCELLED" }]);
		screen.getByText(/These counts cover only the work it reached\./u);
		screen.getByText(/A workspace admin cancelled it\./u);
	});

	it("counts three files apart from six practice and file pairs, with new reviews separate from reuse", async () => {
		serveReport({ scan: () => wireScan() });
		const user = userEvent.setup();

		renderRouteAtWithRouter(REPORT_URL);
		const counts = await screen.findByRole("region", { name: "Counts" }, ROUTE_RENDER_WAIT);
		expect(figure(counts, "Files")).toBe("3");
		expect(figure(counts, "Practice and file pairs")).toBe("6");
		expect(figure(counts, "Reviewed")).toBe("3");
		expect(figure(counts, "Reused")).toBe("1");
		expect(figure(counts, "Withheld")).toBe("1");
		expect(figure(counts, "Not reviewed")).toBe("1");
		expect(counts.textContent).toContain(
			"Each file can be reviewed against more than one practice.",
		);

		await user.click(screen.getByRole("tab", { name: "Files" }));
		const files = await screen.findByRole("table", { name: "Files and practices" });
		const rows = within(files).getAllByRole("row").slice(1);
		const text = rows.map((row) => row.textContent);
		expect(text[2]).toContain("Withheld");
		expect(text[2]).toContain("The assigned AI model was unavailable.");
		expect(text[3]).toContain("Not reviewed");
		expect(text[3]).toContain("The scan ended before this file was reviewed.");
		expect(files.textContent).not.toContain("SOMETHING_THE_PAGE_DOES_NOT_KNOW");
		expect(files.textContent).not.toContain("MODEL_UNAVAILABLE");
		expect(text[3]).toContain("Earlier evidence, not from this scan");
		expect(text[4]).toContain("Removed");
	});

	it("filters problems by how they compare with the earlier scan and explains absence", async () => {
		const reads = serveReport({ scan: () => wireScan() });
		const user = userEvent.setup();

		renderRouteAtWithRouter(REPORT_URL);
		const table = await screen.findByRole(
			"table",
			{ name: "Problems in this scan" },
			ROUTE_RENDER_WAIT,
		);
		await within(table).findByText("A failed upload is logged and then ignored.");
		expect(reads.problems.at(-1)?.searchParams.get("baselineState")).toBeNull();

		await user.click(screen.getByRole("button", { name: "Absent (2)" }));
		await waitFor(() =>
			expect(reads.problems.at(-1)?.searchParams.get("baselineState")).toBe("ABSENT"),
		);
		const absent = await screen.findByRole("table", { name: "Problems in this scan" });
		await within(absent).findByText("The upload ignores the server refusal.");
		expect(within(absent).queryByText("A failed upload is logged and then ignored.")).toBeNull();
		within(absent).getByText("Now met");
		within(absent).getByText(/That alone does not prove a fix\./u);

		await user.click(screen.getByRole("button", { name: "Updated (0)" }));
		await screen.findByText("No problems match this filter");
		await user.click(screen.getByRole("button", { name: "New (1)" }));
		await screen.findByText("A failed upload is logged and then ignored.");
	});

	it("opens a problem's level over Practice reviews from its row", async () => {
		vi.stubGlobal("IntersectionObserver", ObserverStub);
		server.use(...reviewHandlers());
		serveReport({ scan: () => wireScan() });
		const user = userEvent.setup();

		const { router } = renderRouteAtWithRouter(REPORT_URL);
		const table = await screen.findByRole(
			"table",
			{ name: "Problems in this scan" },
			ROUTE_RENDER_WAIT,
		);
		await user.click(
			await within(table).findByRole("link", {
				name: /^Open problem A failed upload is logged and then ignored\./u,
			}),
		);

		await waitFor(() =>
			expect(router.state.location.pathname).toBe("/w/acme/admin/practices/reviews/problems"),
		);
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual(["problem:p-1"]);
	});

	it.each([
		[
			"a changed review method",
			{ comparison: "METHOD_CHANGED" },
			"The review method changed. This scan cannot show which problems were fixed.",
		],
		[
			"an incomplete listing",
			{
				comparison: "INCOMPLETE",
				listingComplete: false,
				removalGuard: { state: "INCOMPLETE_LISTING", previousFiles: 3, wouldRemove: 0 },
			},
			"Missing files are not counted as fixed.",
		],
	] as const)("never claims a fix after %s", async (_name, overrides, sentence) => {
		serveReport({
			scan: () => wireScan({ status: "PARTIAL", stopReason: "BUDGET" }),
			report: (scan) => ({
				...wireReport(scan, overrides),
				problemChanges: { new: 0, unchanged: 0, updated: 0, absent: 0 },
			}),
			problems: () =>
				PROBLEMS.slice(0, 1).map((entry) => ({
					...entry,
					baselineState: null,
					previousProblemId: null,
				})),
		});

		renderRouteAtWithRouter(REPORT_URL);
		await screen.findByText(sentence, { exact: false }, ROUTE_RENDER_WAIT);
		screen.getByText(/These counts cover only the work it reached\./u);
		const table = await screen.findByRole("table", { name: "Problems in this scan" });
		await within(table).findByText("Not compared");
		expect(screen.queryByText("Now met")).toBeNull();
	});

	it("says it could not load the scan and loads it on retry", async () => {
		serveReport({ scan: () => wireScan() });
		const report = failingUntilRecovered(wireReport(wireScan()));
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/scans/:scanId/report", report.respond),
		);
		const user = userEvent.setup();

		renderRouteAtWithRouter(REPORT_URL);
		await screen.findByText("We could not load this scan", undefined, ROUTE_RENDER_WAIT);
		report.recover();
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("heading", { name: "ls1intum/hephaestus" });
	});
});

function captureDownloads() {
	const created: Blob[] = [];
	const revoked: string[] = [];
	const downloads: { name: string; href: string }[] = [];
	URL.createObjectURL = (blob: Blob) => {
		created.push(blob);
		return `blob:sarif-${created.length}`;
	};
	URL.revokeObjectURL = (url: string) => {
		revoked.push(url);
	};
	vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function record(
		this: HTMLAnchorElement,
	) {
		downloads.push({ name: this.download, href: this.getAttribute("href") ?? "" });
	});
	return { created, revoked, downloads };
}

describe("scan report SARIF export", () => {
	// jsdom needs object-URL stubs; restore them after each case.
	const originals = {
		createObjectURL: Object.getOwnPropertyDescriptor(URL, "createObjectURL"),
		revokeObjectURL: Object.getOwnPropertyDescriptor(URL, "revokeObjectURL"),
	};
	afterEach(() => {
		vi.useRealTimers();
		vi.restoreAllMocks();
		for (const [name, descriptor] of Object.entries(originals)) {
			Reflect.deleteProperty(URL, name);
			if (descriptor !== undefined) {
				Object.defineProperty(URL, name, descriptor);
			}
		}
	});

	it("downloads the file the server names and releases its address", async () => {
		serveReport({ scan: () => wireScan() });
		const exports: string[] = [];
		server.use(
			http.get(
				"*/workspaces/:workspaceSlug/practices/scans/:scanId/report/sarif",
				({ request }) => {
					exports.push(request.url);
					return new HttpResponse('{"version":"2.1.0"}', {
						headers: {
							"Content-Type": "application/sarif+json",
							"Content-Disposition": `attachment; filename="hephaestus-scan-${SCAN_ID}.sarif"`,
						},
					});
				},
			),
		);
		const { created, revoked, downloads } = captureDownloads();
		const user = userEvent.setup();

		renderRouteAtWithRouter(REPORT_URL);
		const exportButton = await screen.findByRole(
			"button",
			{ name: "Export SARIF" },
			ROUTE_RENDER_WAIT,
		);
		vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"], shouldAdvanceTime: true });
		await user.click(exportButton);

		await waitFor(() => expect(downloads).toHaveLength(1));
		expect(exports).toHaveLength(1);
		expect(downloads[0]).toStrictEqual({
			name: `hephaestus-scan-${SCAN_ID}.sarif`,
			href: "blob:sarif-1",
		});
		await expect(created[0]?.text()).resolves.toBe('{"version":"2.1.0"}');
		expect(revoked).toStrictEqual([]);
		await vi.advanceTimersByTimeAsync(40_000);
		expect(revoked).toStrictEqual(["blob:sarif-1"]);
	});

	it("says in place that the export failed, and keeps the report", async () => {
		serveReport({ scan: () => wireScan() });
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/scans/:scanId/report/sarif", () =>
				HttpResponse.json(
					{ status: 410, detail: "This scan's details are past their retention." },
					{ status: 410 },
				),
			),
		);
		const { downloads } = captureDownloads();
		const user = userEvent.setup();

		renderRouteAtWithRouter(REPORT_URL);
		await user.click(
			await screen.findByRole("button", { name: "Export SARIF" }, ROUTE_RENDER_WAIT),
		);

		await screen.findByText("We could not export the SARIF file");
		screen.getByText("This scan's details are past their retention.");
		expect(downloads).toHaveLength(0);
		screen.getByRole("heading", { name: "ls1intum/hephaestus" });
		expect(screen.getByRole("button", { name: "Export SARIF" })).toHaveProperty("disabled", false);
	});
});
