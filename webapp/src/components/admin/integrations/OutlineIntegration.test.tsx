import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { toast } from "@/lib/toast";

import { listOutlineCollectionsQueryKey } from "@/api/@tanstack/react-query.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Skeleton } from "@/components/ui/skeleton";
import { useOutlineIntegration } from "@/hooks/use-outline-integration";
import { SyncLivenessProvider } from "@/hooks/use-sync-liveness";
import { server } from "@/mocks/server";
import { type Deferred, deferred } from "@/test/async";

import { ConnectionStateNotice } from "./ConnectionStateNotice";
import { OutlineCollectionsSection } from "./outline/OutlineCollectionsSection";
import { OutlineConnectCard } from "./outline/OutlineConnectCard";
import { SyncResourcesTable } from "./SyncResourcesTable";
import { SyncStatusHeader } from "./SyncStatusHeader";

vi.mock("@/lib/toast", () => ({
	toast: { success: vi.fn(), message: vi.fn(), error: vi.fn() },
}));

function renderContainer({ livePushUnavailable = false } = {}) {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	render(
		<QueryClientProvider client={queryClient}>
			<SyncLivenessProvider livePushUnavailable={livePushUnavailable}>
				<OutlineIntegrationTestContainer />
			</SyncLivenessProvider>
		</QueryClientProvider>,
	);
	return { queryClient };
}

function OutlineIntegrationTestContainer() {
	const outline = useOutlineIntegration("demo");
	if (outline.isLoading) {
		return <Skeleton className="h-48 w-full" />;
	}
	if (outline.connectionsError) {
		return (
			<QueryErrorAlert
				error={outline.connectionsError}
				title="We could not load the Outline connection"
				onRetry={outline.retryConnections}
			/>
		);
	}
	return (
		<>
			{outline.tokenStatusError && (
				<QueryErrorAlert
					error={outline.tokenStatusError}
					title="We could not verify the Outline token"
					onRetry={outline.retryTokenStatus}
				/>
			)}
			{outline.hasConnection && !outline.isConnectionActive && (
				<ConnectionStateNotice connectionState={outline.connectionState} displayName="Outline" />
			)}
			{outline.hasConnection && (
				<SyncStatusHeader label="Outline" {...outline.syncStatusHeaderProps} />
			)}
			<OutlineConnectCard {...outline.connectCardProps} />
			{outline.collectionsProps && <OutlineCollectionsSection {...outline.collectionsProps} />}
		</>
	);
}

const activeOutlineConnection = {
	id: 7,
	kind: "OUTLINE",
	state: "ACTIVE",
	family: "DOCUMENTATION",
	displayName: "Acme Wiki",
	instanceKey: "9a1b2c3d",
};

const engineering = {
	id: 1,
	collectionId: "col-eng",
	name: "Engineering",
	urlId: "engineering-4nZ3x",
	color: "#4E5C6E",
	state: "ENABLED",
	syncStatus: "COMPLETE",
	documentCount: 12,
	lastSyncedAt: "2026-07-01T00:00:00Z",
	createdAt: "2026-06-01T00:00:00Z",
};

/**
 * The same collection as the sync API reports it: one Documents class with its own watermark. This is
 * the only place the document count and freshness are rendered — the management row prints neither, so
 * the two surfaces cannot show different numbers for one fact.
 */
const engineeringResource = [
	{
		id: 1,
		externalId: "col-eng",
		name: "Engineering",
		type: "COLLECTION",
		state: "COMPLETE",
		lastSyncedAt: "2026-07-01T00:00:00Z",
		itemCount: 12,
		counts: [
			{
				key: "documents",
				label: "Documents",
				count: 12,
				lastSyncedAt: "2026-07-01T00:00:00Z",
			},
		],
	},
];

const healthyStatus = {
	connectionId: 7,
	connectionState: "ACTIVE",
	kind: "OUTLINE",
	health: "HEALTHY",
	webhookRegistered: true,
	lastSuccessfulSyncAt: "2026-07-01T00:00:00Z",
	resourceCounts: { total: 1, errored: 0 },
};

const runningJob = {
	id: 1,
	type: "RECONCILIATION",
	trigger: "MANUAL",
	status: "RUNNING",
	cancelRequested: false,
	createdAt: "2026-07-01T00:00:00Z",
};

const healthyToken = {
	accepted: true,
	name: "Hephaestus mirror",
	last4: "9f2c",
	lastActiveAt: "2026-07-01T00:00:00Z",
};

function useConnectedHandlers(collectionsRef: { current: unknown[] }) {
	server.use(
		http.get("*/workspaces/demo/connections", () => HttpResponse.json([activeOutlineConnection])),
		http.get("*/workspaces/demo/connections/7/sync", () => HttpResponse.json(healthyStatus)),
		http.get("*/workspaces/demo/connections/outline/token", () => HttpResponse.json(healthyToken)),
		http.get("*/workspaces/demo/outline/collections", () =>
			HttpResponse.json(collectionsRef.current),
		),
		http.get("*/workspaces/demo/connections/7/sync/resources", () =>
			HttpResponse.json(engineeringResource),
		),
	);
}

beforeEach(() => {
	vi.clearAllMocks();
});

describe("Outline integration — connect happy path", () => {
	it("connects with server URL + token only (no allow-list field) and flips to the connected state", async () => {
		let connections: unknown[] = [];
		let connectBody: unknown;
		server.use(
			http.get("*/workspaces/demo/connections", () => HttpResponse.json(connections)),
			http.post("*/workspaces/demo/connections", async ({ request }) => {
				connectBody = await request.json();
				connections = [activeOutlineConnection];
				return HttpResponse.json({ connectionId: activeOutlineConnection.id, mode: "LINKED" });
			}),
			http.get("*/workspaces/demo/connections/7/sync", () =>
				HttpResponse.json({
					connectionId: 7,
					connectionState: "ACTIVE",
					kind: "OUTLINE",
					health: "HEALTHY",
					webhookRegistered: true,
					resourceCounts: { total: 0, errored: 0 },
				}),
			),
			http.get("*/workspaces/demo/connections/outline/token", () =>
				HttpResponse.json(healthyToken),
			),
			http.get("*/workspaces/demo/outline/collections", () => HttpResponse.json([])),
		);

		renderContainer();

		await screen.findByLabelText(/api token/iu);
		expect(screen.queryByLabelText(/allow-list/iu)).toBeNull();
		expect(screen.queryByLabelText(/collections/iu)).toBeNull();

		// The server URL starts EMPTY: a prefilled cloud URL would send a self-hoster's token to
		// Outline Cloud if they only pasted the token. Connect stays disabled until it is typed.
		const serverUrl = screen.getByLabelText<HTMLInputElement>(/server url/iu);
		expect(serverUrl.value).toBe("");
		fireEvent.change(screen.getByLabelText(/api token/iu), {
			target: { value: "ol_api_secret" },
		});
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: /connect outline/iu }).disabled,
		).toBe(true);

		fireEvent.change(serverUrl, { target: { value: "https://wiki.acme.dev" } });
		fireEvent.click(screen.getByRole("button", { name: /connect outline/iu }));

		await waitFor(() =>
			expect(connectBody).toStrictEqual({
				kind: "OUTLINE",
				userInput: {
					server_url: "https://wiki.acme.dev",
					token: "ol_api_secret",
				},
			}),
		);

		// The invalidated connections query refetches → the card flips to connected, showing written
		// copy ("Outline connected") and the Sync control, not a raw status token.
		await screen.findByText(/outline connected/iu);
		await screen.findByRole("button", { name: /sync now/iu });
		expect(toast.success).toHaveBeenCalledWith("Outline connected");
	});
});

describe("Outline integration — add-collection round trip", () => {
	it("registers a picked candidate, disables already-mirrored entries, and shows the new row", async () => {
		const architecture = {
			...engineering,
			id: 2,
			collectionId: "col-arch",
			name: "Architecture Decisions",
			urlId: "adr-9kQ2p",
			syncStatus: "PENDING",
			documentCount: 0,
			lastSyncedAt: undefined,
		};
		const collectionsRef = { current: [engineering] as unknown[] };
		let registerBody: unknown;
		useConnectedHandlers(collectionsRef);
		server.use(
			http.get("*/workspaces/demo/outline/collections/candidates", () =>
				HttpResponse.json([
					{ collectionId: "col-eng", name: "Engineering", alreadyMirrored: true },
					{ collectionId: "col-arch", name: "Architecture Decisions", alreadyMirrored: false },
				]),
			),
			http.post("*/workspaces/demo/outline/collections", async ({ request }) => {
				registerBody = await request.json();
				collectionsRef.current = [engineering, architecture];
				return HttpResponse.json(architecture, { status: 201 });
			}),
		);

		renderContainer();
		await screen.findByText("Engineering");

		fireEvent.click(screen.getByRole("button", { name: /add collection/iu }));
		const dialog = await screen.findByRole("dialog");

		const mirrored = await within(dialog).findByRole("option", { name: /engineering/iu });
		expect(mirrored.getAttribute("aria-disabled")).toBe("true");

		fireEvent.click(within(dialog).getByRole("option", { name: /architecture decisions/iu }));
		fireEvent.click(within(dialog).getByRole("button", { name: /add 1 collection/iu }));

		await waitFor(() => expect(registerBody).toStrictEqual({ collectionId: "col-arch" }));

		await screen.findByText("Architecture Decisions");
	});
});

describe("Outline integration — pause / resume", () => {
	it("pauses via the row menu and reflects the refetched PAUSED state, then resumes", async () => {
		const collectionsRef = { current: [engineering] as unknown[] };
		const patchBodies: unknown[] = [];
		useConnectedHandlers(collectionsRef);
		server.use(
			http.patch<PathParams, { state: string }>(
				"*/workspaces/demo/outline/collections/:collectionId",
				async ({ request, params }) => {
					const body = await request.json();
					patchBodies.push({ collectionId: params.collectionId, ...body });
					// The handler echoes the row's new state, so only a real refetch can move the UI.
					const next = { ...engineering, state: body.state };
					collectionsRef.current = [next];
					return HttpResponse.json(next);
				},
			),
		);

		renderContainer();
		await screen.findByText("Engineering");

		fireEvent.click(screen.getByRole("button", { name: /actions for engineering/iu }));
		fireEvent.click(await screen.findByRole("menuitem", { name: /^pause$/iu }));

		await waitFor(() =>
			expect(patchBodies).toContainEqual({ collectionId: "col-eng", state: "PAUSED" }),
		);
		await screen.findByText("Paused");

		fireEvent.click(screen.getByRole("button", { name: /actions for engineering/iu }));
		fireEvent.click(await screen.findByRole("menuitem", { name: /^resume$/iu }));

		await waitFor(() =>
			expect(patchBodies).toContainEqual({ collectionId: "col-eng", state: "ENABLED" }),
		);
		await screen.findByText("Mirroring");
	});
});

describe("Outline integration — remove with confirm", () => {
	it("states the erase in the confirm copy, deletes, and refetches to the empty state", async () => {
		const collectionsRef = { current: [engineering] as unknown[] };
		let deletedId: string | undefined;
		useConnectedHandlers(collectionsRef);
		server.use(
			http.delete("*/workspaces/demo/outline/collections/:collectionId", ({ params }) => {
				deletedId = String(params.collectionId);
				collectionsRef.current = [];
				return new HttpResponse(null, { status: 204 });
			}),
		);

		renderContainer();
		await screen.findByText("Engineering");

		fireEvent.click(screen.getByRole("button", { name: /actions for engineering/iu }));
		fireEvent.click(await screen.findByRole("menuitem", { name: /remove and erase/iu }));

		const dialog = await screen.findByRole("alertdialog");
		// Nothing is deleted until the confirm — and the copy must state the mirror erase.
		expect(deletedId).toBeUndefined();
		expect(dialog.textContent).toMatch(
			/permanently erases all 12 mirrored documents\s+from Hephaestus/iu,
		);

		fireEvent.click(within(dialog).getByRole("button", { name: /remove and erase/iu }));

		await waitFor(() => expect(deletedId).toBe("col-eng"));
		await screen.findByText(/no collections mirrored yet/iu);
		expect(toast.success).toHaveBeenCalledWith(
			"Collection removed and its mirrored documents erased",
		);
	});
});

describe("Outline integration — sync now", () => {
	it("fires the reconcile trigger and confirms with a toast", async () => {
		const collectionsRef = { current: [engineering] as unknown[] };
		let syncRequestBody: unknown;
		useConnectedHandlers(collectionsRef);
		server.use(
			http.post("*/workspaces/demo/connections/7/sync/jobs", async ({ request }) => {
				syncRequestBody = await request.json();
				return HttpResponse.json(runningJob);
			}),
		);

		renderContainer();
		const syncButton = await screen.findByRole<HTMLButtonElement>("button", { name: /sync now/iu });
		await waitFor(() => expect(syncButton.disabled).toBe(false));
		fireEvent.click(syncButton);

		await waitFor(() => expect(syncRequestBody).toStrictEqual({ type: "RECONCILIATION" }));
		await waitFor(() => expect(toast.message).toHaveBeenCalledWith("Sync in progress"));
	});
});

describe("Outline integration — token lifecycle", () => {
	it("does not expose an opaque connection identifier when no display name is available", async () => {
		const collectionsRef = { current: [engineering] as unknown[] };
		useConnectedHandlers(collectionsRef);
		server.use(
			http.get("*/workspaces/demo/connections", () =>
				HttpResponse.json([{ ...activeOutlineConnection, displayName: undefined }]),
			),
		);

		renderContainer();

		await screen.findByText(/^outline connected$/iu);
		expect(screen.queryByText(activeOutlineConnection.instanceKey)).toBeNull();
	});

	it("surfaces token status failures and offers a retry", async () => {
		const collectionsRef = { current: [engineering] as unknown[] };
		useConnectedHandlers(collectionsRef);
		server.use(
			http.get("*/workspaces/demo/connections/outline/token", () =>
				HttpResponse.json(
					{ title: "Bad Gateway", status: 502, detail: "Outline did not respond" },
					{ status: 502 },
				),
			),
		);

		renderContainer();

		await screen.findByText(/we could not verify the outline token/iu);
		screen.getByRole("button", { name: /retry/iu });
	});

	it("surfaces a rejected token as a destructive alert instead of a healthy-looking card", async () => {
		const collectionsRef = { current: [engineering] as unknown[] };
		useConnectedHandlers(collectionsRef);
		server.use(
			http.get("*/workspaces/demo/connections/outline/token", () =>
				HttpResponse.json({ accepted: false }),
			),
		);

		renderContainer();

		await screen.findByText(/outline no longer accepts this token/iu);
		expect(screen.queryByText(/outline accepts this token/iu)).toBeNull();
	});

	it("names the token and its last4 when Outline lets the key list itself", async () => {
		const collectionsRef = { current: [engineering] as unknown[] };
		useConnectedHandlers(collectionsRef);

		renderContainer();

		await screen.findByText(/outline accepts this token/iu);
		screen.getByText(/Hephaestus mirror/u);
		screen.getByText(/…9f2c/u);
	});
});

describe("Outline integration — with live push down, polling keeps a running sync honest", () => {
	beforeEach(() => {
		vi.useFakeTimers();
	});
	afterEach(() => {
		vi.useRealTimers();
	});

	// With the SSE stream down, polling is the freshness channel and must be fast: the 5s poll clears
	// a running job that has since settled. Polling doesn't halt at settle — it drops to the 60s idle
	// cadence — so this asserts the running state clears, not that refetching stops. Fake timers keep
	// it deterministic and off the wall clock.
	it("clears a settled job on the fast 5s poll while the live stream is down", async () => {
		const collectionsRef = { current: [engineering] as unknown[] };
		let statusReads = 0;
		useConnectedHandlers(collectionsRef);
		const SYNC_STATUS = "*/workspaces/demo/connections/7/sync";
		server.use(
			http.get(
				SYNC_STATUS,
				() => {
					statusReads += 1;
					return HttpResponse.json({ ...healthyStatus, activeJob: runningJob });
				},
				{ once: true },
			),
			http.get(SYNC_STATUS, () => {
				statusReads += 1;
				return HttpResponse.json({ ...healthyStatus, activeJob: undefined });
			}),
		);

		renderContainer({ livePushUnavailable: true });

		// Flush the mount fetches: the shared header's ActiveJobProgress narrates the running reconcile.
		await act(async () => {
			await vi.advanceTimersByTimeAsync(50);
		});
		screen.getByText(/reconciliation running/iu);

		// No user interaction, no invalidation — only the 5s refetchInterval can clear this.
		await act(async () => {
			await vi.advanceTimersByTimeAsync(5000);
		});
		expect(screen.queryByText(/reconciliation running/iu)).toBeNull();
		expect(statusReads).toBeGreaterThanOrEqual(2);
	});
});

describe("Outline integration — Outline not enabled on this instance", () => {
	it("turns the initiate 400 (no strategy for the kind) into a clear 'not available here' hint", async () => {
		// A deployment with HEPHAESTUS_INTEGRATION_OUTLINE_ENABLED off has no OutlineConnectionStrategy
		// bean, so ConnectionController.initiate rejects the kind with exactly this 400. The card must
		// explain that instead of only echoing the developer-facing message, so an admin does not keep
		// retrying a connect that can never succeed.
		server.use(
			http.get("*/workspaces/demo/connections", () => HttpResponse.json([])),
			http.post("*/workspaces/demo/connections", () =>
				HttpResponse.json(
					{
						type: "about:blank",
						title: "Invalid request",
						status: 400,
						detail: "No ConnectionStrategy registered for kind=OUTLINE",
					},
					{ status: 400 },
				),
			),
		);

		renderContainer();

		fireEvent.change(await screen.findByLabelText(/server url/iu), {
			target: { value: "https://wiki.acme.dev" },
		});
		fireEvent.change(screen.getByLabelText(/api token/iu), { target: { value: "ol_api_secret" } });
		fireEvent.click(screen.getByRole("button", { name: /connect outline/iu }));

		// The raw ProblemDetail is still shown; the hint is derived from it and added below.
		await screen.findByText(/outline may not be enabled on this instance/iu);
		screen.getByText(/no connectionstrategy registered for kind=outline/iu);
	});
});

/**
 * The shared `SyncResourcesTable`, driven by the one hook. Rendered on its own here because it and
 * the management card name the same collection — the count and freshness are printed once, by
 * this one, so they cannot disagree.
 */
function LedgerContainer() {
	const outline = useOutlineIntegration("demo");
	return outline.hasConnection ? <SyncResourcesTable {...outline.syncResourcesProps} /> : null;
}

function renderLedger() {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	return render(
		<QueryClientProvider client={queryClient}>
			<SyncLivenessProvider livePushUnavailable={false}>
				<LedgerContainer />
			</SyncLivenessProvider>
		</QueryClientProvider>,
	);
}

describe("Outline integration — per-collection sync ledger", () => {
	it("mounts the shared ledger for the Outline connection with its Documents column and freshness", async () => {
		const collectionsRef = { current: [engineering] as unknown[] };
		useConnectedHandlers(collectionsRef);

		renderLedger();

		// The class column is Outline's own, and the count comes from the sync API — not from the
		// collections list the management card reads.
		await screen.findByRole("columnheader", { name: "Documents" });
		expect(screen.queryByRole("columnheader", { name: "Issues" })).toBeNull();
		await screen.findByText("Engineering");
		screen.getByText("12");
		// The freshness column exists here and nowhere else on the page.
		screen.getByRole("columnheader", { name: "Last synced" });
	});
});

const COLLECTIONS_FAILURE = {
	403: "You cannot manage this workspace's Outline collections.",
	503: "Outline is temporarily unavailable.",
} as const;

/**
 * Serves the managed collections. Each GET answers with the status the test last chose, after any
 * hold. A DELETE waits for its own answer, and only a completed DELETE removes the row it names.
 */
function serveManagedCollections(
	rows: (typeof engineering)[],
	removalAnswers: Deferred<void>[] = [],
) {
	const served = {
		rows,
		status: 200 as 200 | 403 | 503,
		hold: undefined as Promise<void> | undefined,
		reads: 0,
		removals: [] as { workspaceSlug: string; collectionId: string }[],
	};
	server.use(
		http.get("*/workspaces/demo/outline/collections", async () => {
			served.reads += 1;
			await served.hold;
			const { status } = served;
			return status === 200
				? HttpResponse.json(served.rows)
				: HttpResponse.json(
						{ status, title: "Outline read failed", detail: COLLECTIONS_FAILURE[status] },
						{ status },
					);
		}),
		http.delete(
			"*/workspaces/:workspaceSlug/outline/collections/:collectionId",
			async ({ params }) => {
				const answer = removalAnswers[served.removals.length];
				const collectionId = String(params.collectionId);
				served.removals.push({ workspaceSlug: String(params.workspaceSlug), collectionId });
				await answer?.promise;
				served.rows = served.rows.filter((row) => row.collectionId !== collectionId);
				return new HttpResponse(null, { status: 204 });
			},
		),
	);
	return served;
}

/** Reads the managed collections again through their own key, as a finished sync run does. */
async function rereadCollections(queryClient: QueryClient) {
	await act(async () => {
		await queryClient.invalidateQueries({
			queryKey: listOutlineCollectionsQueryKey({ path: { workspaceSlug: "demo" } }),
		});
	});
}

async function openRemove(user: ReturnType<typeof userEvent.setup>, name: string) {
	await user.click(await screen.findByRole("button", { name: `Actions for ${name}` }));
	await user.click(await screen.findByRole("menuitem", { name: "Remove and erase…" }));
	return screen.findByRole("alertdialog", { name: `Remove “${name}” and erase its mirror?` });
}

describe("Outline integration — managed collections read and Remove", () => {
	const decisions = {
		...engineering,
		id: 2,
		collectionId: "col-arch",
		name: "Architecture Decisions",
		urlId: "adr-9kQ2p",
		documentCount: 4,
	};

	it("closes a refused collection's Remove and does not reopen it when the collections read again", async () => {
		useConnectedHandlers({ current: [engineering] });
		const collections = serveManagedCollections([engineering]);
		const { queryClient } = renderContainer();
		const user = userEvent.setup();
		const removal = await openRemove(user, "Engineering");
		expect(removal.textContent).toMatch(/all 12 mirrored documents/u);
		const readsBefore = collections.reads;

		collections.status = 403;
		await rereadCollections(queryClient);
		expect(collections.reads).toBe(readsBefore + 1);
		await screen.findByText("You do not have access to the mirrored collections");
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect(screen.queryByText("Engineering")).toBeNull();
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();

		collections.status = 503;
		await rereadCollections(queryClient);
		await screen.findByText("We could not load the mirrored collections");
		expect(screen.queryByText("Engineering")).toBeNull();
		expect(screen.queryByText("The mirrored collections may be out of date")).toBeNull();
		expect(screen.queryByRole("alertdialog")).toBeNull();

		collections.status = 200;
		collections.rows = [{ ...engineering, documentCount: 40 }];
		await user.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByText("Engineering");
		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(collections.reads).toBe(readsBefore + 3);

		const reopened = await openRemove(user, "Engineering");
		expect(reopened.textContent).toMatch(/all 40 mirrored documents/u);
		expect(collections.removals).toStrictEqual([]);
	});

	it("keeps the listed collections and an open Remove through a failed refresh, then retries", async () => {
		useConnectedHandlers({ current: [engineering] });
		const collections = serveManagedCollections([engineering]);
		const { queryClient } = renderContainer();
		const user = userEvent.setup();
		const retry = deferred();
		try {
			await openRemove(user, "Engineering");
			const readsBefore = collections.reads;

			collections.status = 503;
			await rereadCollections(queryClient);
			expect(collections.reads).toBe(readsBefore + 1);
			await screen.findByText("The mirrored collections may be out of date");
			screen.getByText("Engineering");
			expect(screen.queryByText("We could not load the mirrored collections")).toBeNull();
			expect(screen.queryByText("No collections mirrored yet")).toBeNull();
			const kept = screen.getByRole("alertdialog", {
				name: "Remove “Engineering” and erase its mirror?",
			});
			expect(kept.textContent).toMatch(/all 12 mirrored documents/u);
			await user.click(within(kept).getByRole("button", { name: "Cancel" }));
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

			collections.hold = retry.promise;
			await user.click(screen.getByRole("button", { name: "Retry" }));
			const retrying = await screen.findByRole("button", { name: "Retrying…" });
			expect(retrying).toHaveProperty("disabled", true);
			await waitFor(() => expect(collections.reads).toBe(readsBefore + 2));
			screen.getByText("Engineering");

			collections.status = 200;
			collections.rows = [{ ...engineering, documentCount: 30 }];
			retry.resolve();
			await waitFor(() =>
				expect(screen.queryByText("The mirrored collections may be out of date")).toBeNull(),
			);
			collections.hold = undefined;
			const reopened = await openRemove(user, "Engineering");
			expect(reopened.textContent).toMatch(/all 30 mirrored documents/u);

			collections.rows = [{ ...engineering, name: "Engineering Handbook", documentCount: 1 }];
			await rereadCollections(queryClient);
			const renamed = await screen.findByRole("alertdialog", {
				name: "Remove “Engineering Handbook” and erase its mirror?",
			});
			expect(renamed.textContent).toMatch(/its 1 mirrored document\s/u);
			expect(collections.reads).toBe(readsBefore + 3);
			expect(collections.removals).toStrictEqual([]);
		} finally {
			retry.resolve();
		}
	});

	it("leaves a Remove opened after a refusal to its own answer when the refused Remove completes", async () => {
		useConnectedHandlers({ current: [engineering, decisions] });
		const refusedRemoval = deferred();
		const collections = serveManagedCollections([engineering, decisions], [refusedRemoval]);
		const { queryClient } = renderContainer();
		const user = userEvent.setup();
		try {
			const refused = await openRemove(user, "Engineering");
			await user.click(within(refused).getByRole("button", { name: "Remove and erase" }));
			await waitFor(() =>
				expect(collections.removals).toStrictEqual([
					{ workspaceSlug: "demo", collectionId: "col-eng" },
				]),
			);
			await user.keyboard("{Escape}");
			const pending = screen.getByRole("alertdialog", {
				name: "Remove “Engineering” and erase its mirror?",
			});
			expect(within(pending).getByRole("button", { name: "Removing…" })).toHaveProperty(
				"disabled",
				true,
			);
			const readsBefore = collections.reads;

			collections.status = 403;
			await rereadCollections(queryClient);
			await screen.findByText("You do not have access to the mirrored collections");
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

			collections.status = 200;
			await rereadCollections(queryClient);
			await openRemove(user, "Architecture Decisions");

			refusedRemoval.resolve();
			await waitFor(() => expect(screen.queryByText("Engineering")).toBeNull());
			expect(collections.reads).toBe(readsBefore + 3);
			expect(toast.success).toHaveBeenCalledWith(
				"Collection removed and its mirrored documents erased",
			);
			const stillOpen = screen.getByRole("alertdialog", {
				name: "Remove “Architecture Decisions” and erase its mirror?",
			});
			expect(within(stillOpen).getByRole("button", { name: "Remove and erase" })).toHaveProperty(
				"disabled",
				false,
			);

			await user.click(within(stillOpen).getByRole("button", { name: "Cancel" }));
			await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
			expect(collections.removals).toStrictEqual([
				{ workspaceSlug: "demo", collectionId: "col-eng" },
			]);
		} finally {
			refusedRemoval.resolve();
		}
	});
});

describe("Outline integration — collections plane is gated on the connection", () => {
	it("does not render the collections section while disconnected", async () => {
		server.use(http.get("*/workspaces/demo/connections", () => HttpResponse.json([])));

		renderContainer();
		await screen.findByRole("button", { name: /connect outline/iu });
		expect(screen.queryByText(/mirrored collections/iu)).toBeNull();
	});
});
