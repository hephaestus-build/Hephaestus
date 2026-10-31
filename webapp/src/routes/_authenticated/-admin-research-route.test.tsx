import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, assert, describe, expect, it, vi } from "vitest";

import { getPasskeyStatusOptions } from "@/api/@tanstack/react-query.gen";
import type {
	AdminWorkspaceView,
	InstanceSettings,
	PasskeyStatus,
	ResearchRemovalDisclosure,
} from "@/api/types.gen";
import { WORDING_VERSION } from "@/components/auth/consent-wording";
import type { Wire } from "@/lib/dates";
import { problemType } from "@/lib/problem-detail";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 30_000 });

const DATASET = "6f1c2b8e-4d3a-4f6b-9a1e-2c7d8e9f0a1b";
const KEY = "p_abcdefghijklmnopqrstuvwxyz";

const course: Wire<AdminWorkspaceView> = {
	id: 2,
	workspaceSlug: "intro-course",
	displayName: "Intro Course",
	status: "ACTIVE",
	accountLogin: "ase/intro",
	providerType: "GITLAB",
	memberCount: 180,
	createdAt: "2026-03-01T00:00:00Z",
};

const fileHeader = {
	schema: "hephaestus.research-dataset",
	schemaVersion: 1,
	datasetId: DATASET,
	classification: "PSEUDONYMOUS_PERSONAL_DATA",
	purpose: "RESEARCH_PARTICIPATION",
	window: { from: "2026-04-01", to: "2026-10-01" },
	useUntil: "2027-10-01",
	counts: {
		participants: 12,
		results: 340,
		problems: 81,
		feedback: 57,
		feedbackExcludedIncompleteBasis: 0,
	},
	participants: [{ key: KEY }],
};

function openDataset(
	removals: Wire<ResearchRemovalDisclosure>["removals"],
	datasetId: string = DATASET,
	createdAt = "2026-10-20T09:00:00Z",
): Wire<ResearchRemovalDisclosure> {
	return {
		datasetId,
		workspaceId: "2",
		createdAt,
		useUntil: "2027-10-01T00:00:00Z",
		useExpired: false,
		removals,
	};
}

/** Serves the research question, the workspaces and the open datasets the test last chose. */
function serveResearch(options: {
	organization?: string;
	open: Wire<ResearchRemovalDisclosure>[];
}) {
	const state = { open: options.open, checks: 0 };
	server.use(
		http.get("*/user/consent", () =>
			HttpResponse.json({
				completed: true,
				noticeVersion: WORDING_VERSION,
				participateInResearch: false,
				...(options.organization === undefined
					? {}
					: { researchOrganization: options.organization }),
			}),
		),
		http.get("*/admin/settings", () =>
			HttpResponse.json({ silentModeEngaged: false, etag: '"1"' } satisfies Wire<InstanceSettings>),
		),
		http.get("*/admin/workspaces", () => HttpResponse.json([course])),
		http.post("*/admin/research-disclosures/removal-checks", () => {
			state.checks += 1;
			return HttpResponse.json(state.open);
		}),
	);
	return state;
}

/** The account's passkey status with no passkey, under the instance's confirmation policy. */
function passkeyStatus(assuranceEnforced: boolean): Wire<PasskeyStatus> {
	return {
		assuranceEnforced,
		protectionEnabled: false,
		recoveryRequired: false,
		instanceAdminRequired: true,
		workspaceAdminRequired: false,
		verified: false,
		credentials: [],
	};
}

function servePasskeys(assuranceEnforced: boolean) {
	server.use(
		http.get("*/user/passkeys", () => HttpResponse.json(passkeyStatus(assuranceEnforced))),
	);
}

function captureDownloads() {
	const downloads: { name: string; size: number; attached: boolean }[] = [];
	const revoked: string[] = [];
	let blob: Blob | undefined;
	URL.createObjectURL = (created: Blob) => {
		blob = created;
		return "blob:research-1";
	};
	URL.revokeObjectURL = (url: string) => {
		revoked.push(url);
	};
	vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function record(
		this: HTMLAnchorElement,
	) {
		downloads.push({ name: this.download, size: blob?.size ?? 0, attached: this.isConnected });
	});
	return { downloads, revoked };
}

/** Every key and value the browser holds in local and session storage, as one string. */
function storedText(): string {
	return [localStorage, sessionStorage]
		.flatMap((storage) =>
			Array.from({ length: storage.length }, (_, index) => {
				const key = storage.key(index) ?? "";
				return `${key}=${storage.getItem(key) ?? ""}`;
			}),
		)
		.join("\n");
}

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

async function fillRelease(user: ReturnType<typeof userEvent.setup>) {
	await user.click(await screen.findByRole("combobox", { name: "Workspace" }, ROUTE_RENDER_WAIT));
	await user.click(await screen.findByRole("option", { name: "Intro Course (intro-course)" }));
	fireEvent.change(screen.getByLabelText("First day (UTC)"), { target: { value: "2026-04-01" } });
	fireEvent.change(screen.getByLabelText(/^Last day \(UTC\)/u), {
		target: { value: "2026-09-30" },
	});
	fireEvent.change(screen.getByLabelText(/^Use allowed through \(UTC\)/u), {
		target: { value: "2027-09-30" },
	});
}

describe("research datasets route", () => {
	it("releases one window of one workspace, downloads the file once and reads the open datasets again", async () => {
		const research = serveResearch({ organization: "TUM AET", open: [openDataset([])] });
		const bodies: unknown[] = [];
		server.use(
			http.post("*/admin/research-disclosures", async ({ request }) => {
				bodies.push(await request.json());
				return new HttpResponse(JSON.stringify(fileHeader), {
					headers: {
						"Content-Type": "application/json",
						"Content-Disposition": `attachment; filename="hephaestus-research-${DATASET}.json"`,
						"Cache-Control": "no-store, private",
					},
				});
			}),
		);
		const { downloads, revoked } = captureDownloads();
		const user = userEvent.setup();
		renderRouteAt("/admin/research");

		await screen.findByText("TUM AET", undefined, ROUTE_RENDER_WAIT);
		await fillRelease(user);
		await user.click(screen.getByRole("button", { name: "Export and download" }));
		// The acknowledgement is required; nothing is sent without it.
		await screen.findByText("Confirm this before you export.");
		expect(bodies).toStrictEqual([]);

		await user.click(
			screen.getByRole("checkbox", { name: /I give this file only to the research team/u }),
		);
		// The file's address is let go on a timer; the clock still runs, and the test can move it on.
		vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"], shouldAdvanceTime: true });
		await user.click(screen.getByRole("button", { name: "Export and download" }));

		await screen.findByRole("heading", { name: "Dataset exported" });
		expect(bodies).toStrictEqual([
			{
				// `format: date` in the contract: calendar days, not timestamps. The use end stays an instant.
				workspaceId: "2",
				firstDay: "2026-04-01",
				lastDay: "2026-09-30",
				useUntil: "2027-10-01T00:00:00.000Z",
			},
		]);
		expect(downloads[0]?.attached, "Download anchor is connected at click").toBe(true);
		expect(downloads).toStrictEqual([
			{
				name: `hephaestus-research-${DATASET}.json`,
				size: JSON.stringify(fileHeader).length,
				attached: true,
			},
		]);
		// The browser reads the file after the click, so its address is still there.
		expect(revoked).toStrictEqual([]);
		screen.getByText("2026-04-01 to 2026-09-30 (UTC)");
		screen.getByText("2027-09-30 (UTC)");
		// The release proved a fresh passkey, so the open datasets are read again from the server.
		await waitFor(() => expect(research.checks).toBe(1));
		await screen.findByRole("heading", { name: /Intro Course, exported 20 October 2026/u });
		// Neither the file nor its participant keys are kept by the browser.
		expect(storedText()).not.toContain(DATASET);
		expect(storedText()).not.toContain(KEY);
		// The page lets go of the file's address once, when its timer runs.
		await vi.advanceTimersByTimeAsync(40_000);
		expect(revoked).toStrictEqual(["blob:research-1"]);
	});

	it("never retries a failed release and points to the open datasets instead", async () => {
		serveResearch({ organization: "TUM AET", open: [] });
		let releases = 0;
		server.use(
			http.post("*/admin/research-disclosures", () => {
				releases += 1;
				return HttpResponse.json({ status: 502, title: "Bad gateway" }, { status: 502 });
			}),
		);
		const { downloads } = captureDownloads();
		const user = userEvent.setup();
		renderRouteAt("/admin/research");

		await screen.findByText("TUM AET", undefined, ROUTE_RENDER_WAIT);
		await fillRelease(user);
		await user.click(
			screen.getByRole("checkbox", { name: /I give this file only to the research team/u }),
		);
		await user.click(screen.getByRole("button", { name: "Export and download" }));

		await screen.findByText("The dataset was not delivered to this browser");
		screen.getByText(/Check open datasets before you export again/u);
		expect(releases).toBe(1);
		expect(downloads).toStrictEqual([]);
		expect(screen.queryByRole("heading", { name: "Dataset exported" })).toBeNull();
	});

	it("keeps receipts and closure available when the instance no longer runs a research program", async () => {
		const research = serveResearch({
			open: [
				openDataset([
					{ participantKey: KEY, reason: "CONSENT_WITHDRAWN", requiredAt: "2026-11-02T08:00:00Z" },
				]),
			],
		});
		const receipts: unknown[] = [];
		const closures: unknown[] = [];
		server.use(
			http.post(
				"*/admin/research-disclosures/:datasetId/removal-receipts",
				async ({ request, params }) => {
					receipts.push({ dataset: params.datasetId, body: await request.json() });
					research.open = [openDataset([])];
					return HttpResponse.json({ recorded: 1 });
				},
			),
			http.post("*/admin/research-disclosures/:datasetId/closure", async ({ request, params }) => {
				closures.push({ dataset: params.datasetId, body: await request.json() });
				research.open = [];
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const user = userEvent.setup();
		renderRouteAt("/admin/research");

		await screen.findByText("This instance runs no research program", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("button", { name: "Export and download" })).toBeNull();

		await user.click(screen.getByRole("button", { name: "Check open datasets" }));
		await screen.findByText(KEY);
		screen.getByText("Withdrew consent");

		await user.click(screen.getByRole("button", { name: "Record removal…" }));
		const removalDialog = await screen.findByRole("alertdialog");
		await user.click(within(removalDialog).getByRole("button", { name: "Record removal" }));
		await within(removalDialog).findByText("Confirm this before you record it.");
		expect(receipts).toStrictEqual([]);
		await user.click(
			within(removalDialog).getByRole("checkbox", { name: /The research team confirmed/u }),
		);
		await user.click(within(removalDialog).getByRole("button", { name: "Record removal" }));

		await waitFor(() =>
			expect(receipts).toStrictEqual([{ dataset: DATASET, body: { participantKeys: [KEY] } }]),
		);
		await waitFor(() => expect(research.checks).toBe(2));
		await screen.findByText("No removal is owed.");

		await user.click(screen.getByRole("button", { name: "Close dataset…" }));
		const closeDialog = await screen.findByRole("alertdialog");
		await user.click(within(closeDialog).getByRole("radio", { name: "Every copy was deleted" }));
		await user.click(
			within(closeDialog).getByRole("checkbox", { name: /The research team confirmed/u }),
		);
		await user.click(within(closeDialog).getByRole("button", { name: "Close dataset" }));

		await waitFor(() =>
			expect(closures).toStrictEqual([{ dataset: DATASET, body: { closure: "DESTROYED" } }]),
		);
		await waitFor(() => expect(research.checks).toBe(3));
		await screen.findByText("No open datasets");
	});

	it("reads the current state after a receipt whose answer was lost, and never sends it again", async () => {
		const owed = [
			{
				participantKey: KEY,
				reason: "CONSENT_WITHDRAWN" as const,
				requiredAt: "2026-11-02T08:00:00Z",
			},
		];
		const research = serveResearch({ organization: "TUM AET", open: [openDataset(owed)] });
		let receipts = 0;
		server.use(
			http.post("*/admin/research-disclosures/:datasetId/removal-receipts", () => {
				receipts += 1;
				// The server records the removal; only its answer fails to arrive.
				research.open = [openDataset([])];
				return HttpResponse.json({ status: 502, title: "Bad gateway" }, { status: 502 });
			}),
		);
		const user = userEvent.setup();
		renderRouteAt("/admin/research");

		await user.click(
			await screen.findByRole("button", { name: "Check open datasets" }, ROUTE_RENDER_WAIT),
		);
		await user.click(await screen.findByRole("button", { name: "Record removal…" }));
		const dialog = await screen.findByRole("alertdialog");
		await user.click(
			within(dialog).getByRole("checkbox", { name: /The research team confirmed/u }),
		);
		await user.click(within(dialog).getByRole("button", { name: "Record removal" }));

		await screen.findByText("We could not confirm whether this was recorded");
		expect(screen.queryByText(/did not record/u)).toBeNull();
		await waitFor(() => expect(research.checks).toBe(2));
		await screen.findByText("No removal is owed.");
		expect(receipts).toBe(1);
		expect(screen.queryByRole("button", { name: "Record removal…" })).toBeNull();
	});

	it("closes a dialog opened from an older answer when a newer one arrives", async () => {
		const OTHER = "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d";
		// Released a day later, so its section has its own name.
		const OTHER_RELEASED = "2026-10-21T09:00:00Z";
		const owed = [
			{
				participantKey: KEY,
				reason: "ACCOUNT_ERASED" as const,
				requiredAt: "2026-11-02T08:00:00Z",
			},
		];
		const research = serveResearch({
			organization: "TUM AET",
			open: [openDataset(owed), openDataset([], OTHER, OTHER_RELEASED)],
		});
		const held = deferred();
		const closures: unknown[] = [];
		server.use(
			http.post("*/admin/research-disclosures/:datasetId/removal-receipts", async () => {
				await held.promise;
				research.open = [openDataset([]), openDataset([], OTHER, OTHER_RELEASED)];
				return HttpResponse.json({ recorded: 1 });
			}),
			http.post("*/admin/research-disclosures/:datasetId/closure", async ({ request }) => {
				closures.push(await request.json());
				return new HttpResponse(null, { status: 204 });
			}),
		);
		const user = userEvent.setup();
		renderRouteAt("/admin/research");
		try {
			await user.click(
				await screen.findByRole("button", { name: "Check open datasets" }, ROUTE_RENDER_WAIT),
			);
			await user.click(await screen.findByRole("button", { name: "Record removal…" }));
			const removalDialog = await screen.findByRole("alertdialog");
			await user.click(
				within(removalDialog).getByRole("checkbox", { name: /The research team confirmed/u }),
			);
			await user.click(within(removalDialog).getByRole("button", { name: "Record removal" }));
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

			// While the first receipt is in flight, a closure dialog opens for the other dataset.
			const otherDataset = screen.getByRole("region", {
				name: "Intro Course, exported 21 October 2026",
			});
			await user.click(within(otherDataset).getByRole("button", { name: "Close dataset…" }));
			await screen.findByRole("alertdialog", { name: "Close this dataset?" });

			held.resolve();
			await waitFor(() => expect(research.checks).toBe(2));
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
			expect(closures).toStrictEqual([]);
		} finally {
			held.resolve();
		}
	});

	it("shows no dataset when the server refuses the check", async () => {
		serveResearch({ organization: "TUM AET", open: [] });
		server.use(
			http.post("*/admin/research-disclosures/removal-checks", () =>
				HttpResponse.json(
					{ status: 403, type: problemType("passkey-required"), title: "Passkey required" },
					{ status: 403 },
				),
			),
		);
		const user = userEvent.setup();
		renderRouteAt("/admin/research");

		await user.click(
			await screen.findByRole("button", { name: "Check open datasets" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByText("We could not check the open datasets");
		expect(screen.queryByText("No open datasets")).toBeNull();
		expect(screen.queryByRole("button", { name: "Close dataset…" })).toBeNull();
	});

	it("does not offer a passkey confirmation without a usable passkey", async () => {
		serveResearch({ organization: "TUM AET", open: [] });
		servePasskeys(true);
		renderRouteAt("/admin/research");

		await screen.findByText(
			/Without one, you cannot export, check or update datasets here/u,
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("button", { name: "Confirm with passkey" })).toBeNull();
	});

	it("asks for no passkey where the instance turned additional confirmation off, and still checks", async () => {
		const research = serveResearch({ organization: "TUM AET", open: [] });
		servePasskeys(false);
		const user = userEvent.setup();
		renderRouteAt("/admin/research");

		await screen.findByText(
			/Extra authentication checks are disabled on this instance/u,
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("button", { name: "Confirm with passkey" })).toBeNull();
		expect(screen.queryByText(/you cannot export, check or update datasets/u)).toBeNull();

		await user.click(screen.getByRole("button", { name: "Check open datasets" }));
		await screen.findByText("No open datasets");
		expect(research.checks).toBe(1);
	});

	it("neither asks for a passkey nor waives one while the passkey status loads", async () => {
		serveResearch({ organization: "TUM AET", open: [] });
		const held = deferred();
		server.use(
			http.get("*/user/passkeys", async () => {
				await held.promise;
				return HttpResponse.json(passkeyStatus(true));
			}),
		);
		renderRouteAt("/admin/research");
		try {
			await screen.findByText("Loading your passkey status", undefined, ROUTE_RENDER_WAIT);
			expect(screen.queryByText(/checks are disabled/u)).toBeNull();
			expect(screen.queryByText(/you cannot export, check or update datasets/u)).toBeNull();
			expect(screen.queryByRole("button", { name: "Confirm with passkey" })).toBeNull();

			held.resolve();
			await screen.findByText(/Without one, you cannot export, check or update datasets here/u);
		} finally {
			held.resolve();
		}
	});

	it("keeps the export when the passkey status cannot be read, and reads it again on retry", async () => {
		serveResearch({ organization: "TUM AET", open: [] });
		let reads = 0;
		let deliveryReads = 0;
		server.use(
			http.get("*/admin/settings", () => {
				deliveryReads += 1;
				return HttpResponse.json({ status: 503, title: "Unavailable" }, { status: 503 });
			}),
			http.get(
				"*/user/passkeys",
				() => {
					reads += 1;
					return HttpResponse.json({ status: 503, title: "Unavailable" }, { status: 503 });
				},
				{ once: true },
			),
			http.get("*/user/passkeys", () => {
				reads += 1;
				return HttpResponse.json(passkeyStatus(false));
			}),
		);
		const user = userEvent.setup();
		renderRouteAt("/admin/research");

		const passkeyTitle = await screen.findByText(
			"We could not load your passkey status",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		const deliveryTitle = await screen.findByText(
			"We could not load the instance delivery state",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		const passkeyAlert = passkeyTitle.closest<HTMLElement>('[role="alert"]');
		const deliveryAlert = deliveryTitle.closest<HTMLElement>('[role="alert"]');
		assert(passkeyAlert !== null);
		assert(deliveryAlert !== null);
		expect(passkeyAlert).not.toBe(deliveryAlert);
		expect(
			within(passkeyAlert).getByRole("button", { name: "Retry" }).hasAttribute("disabled"),
		).toBe(false);
		expect(
			within(deliveryAlert).getByRole("button", { name: "Retry" }).hasAttribute("disabled"),
		).toBe(false);
		expect(screen.getAllByRole("button", { name: "Retry" })).toHaveLength(2);
		expect(reads).toBe(1);
		expect(deliveryReads).toBe(1);
		// The server's answer is classified, not read as a lost connection.
		within(passkeyAlert).getByText(/Try again in a moment/u);
		expect(screen.queryByText(/Check your connection/u)).toBeNull();
		expect(screen.queryByText(/checks are disabled/u)).toBeNull();
		expect(screen.queryByText(/you cannot export, check or update datasets/u)).toBeNull();
		const exportButton = await screen.findByRole("button", { name: "Export and download" });
		expect(exportButton.hasAttribute("disabled")).toBe(false);

		const retry = within(passkeyAlert).getByRole("button", { name: "Retry" });
		expect(retry.hasAttribute("disabled")).toBe(false);
		await user.click(retry);
		await screen.findByText(/Extra authentication checks are disabled on this instance/u);
		expect(reads).toBe(2);
		expect(deliveryReads).toBe(1);
		expect(screen.queryByText("We could not load your passkey status")).toBeNull();
		expect(
			within(deliveryAlert).getByRole("button", { name: "Retry" }).hasAttribute("disabled"),
		).toBe(false);
	});

	it("does not present cached policy as current after a failed refresh, and recovers on retry", async () => {
		serveResearch({ organization: "TUM AET", open: [] });
		servePasskeys(false);
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin/research");
		await screen.findByText(
			/Extra authentication checks are disabled on this instance/u,
			undefined,
			ROUTE_RENDER_WAIT,
		);

		server.use(
			http.get("*/user/passkeys", () =>
				HttpResponse.json({ status: 503, title: "Unavailable" }, { status: 503 }),
			),
		);
		await act(async () => {
			await queryClient.refetchQueries({ queryKey: getPasskeyStatusOptions().queryKey });
		});
		await screen.findByText("We could not load your passkey status");
		expect(screen.queryByText(/checks are disabled/u)).toBeNull();
		expect(
			screen.getByRole("button", { name: "Export and download" }).hasAttribute("disabled"),
		).toBe(false);

		servePasskeys(true);
		const policyError = screen
			.getAllByRole("alert")
			.find((alert) => within(alert).queryByText("We could not load your passkey status"));
		assert.isDefined(policyError, "The research policy error is shown");
		await user.click(within(policyError).getByRole("button", { name: "Retry" }));
		await screen.findByText(/Without one, you cannot export, check or update datasets here/u);
		expect(screen.queryByText("We could not load your passkey status")).toBeNull();
		expect(screen.queryByText(/checks are disabled/u)).toBeNull();
	});

	it("names a refused passkey status read as missing access, and offers no retry", async () => {
		serveResearch({ organization: "TUM AET", open: [] });
		server.use(
			http.get("*/user/passkeys", () =>
				HttpResponse.json(
					{
						status: 403,
						title: "Forbidden",
						detail: "This account cannot read its passkey status.",
					},
					{ status: 403 },
				),
			),
		);
		renderRouteAt("/admin/research");

		await screen.findByText(
			"You do not have access to your passkey status",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		screen.getByText(/Ask a workspace admin or instance admin for access/u);
		expect(screen.queryByText(/Check your connection/u)).toBeNull();
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
		// An unknown policy is neither waived nor enforced on the page.
		expect(screen.queryByText(/checks are disabled/u)).toBeNull();
		expect(screen.queryByText(/you cannot export, check or update datasets/u)).toBeNull();
		expect(screen.queryByRole("button", { name: "Confirm with passkey" })).toBeNull();
	});
});
