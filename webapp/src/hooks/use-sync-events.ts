import { type QueryClient, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import {
	getConnectionSyncStatusQueryKey,
	getIntegrationCatalogQueryKey,
	getOutlineTokenStatusQueryKey,
	getRepositoriesToMonitorQueryKey,
	getWorkspaceQueryKey,
	listConnectionSyncJobsQueryKey,
	listConnectionSyncResourcesQueryKey,
	listOutlineCollectionsQueryKey,
	listQueryKey,
	listSlackChannelCandidatesQueryKey,
	listSlackChannelsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { ConnectionSummary, IntegrationCatalogEntry } from "@/api/types.gen";
import environment from "@/environment";
import { isRecord } from "@/lib/is-record";
import { queryOperationId } from "@/lib/query-operation-id";

type SyncEventScope = "job" | "resources" | "connection" | "activity";

interface SyncEventHint {
	scope: SyncEventScope;
	connectionId: number;
}

const SYNC_EVENT_SCOPES = {
	job: true,
	resources: true,
	connection: true,
	activity: true,
} satisfies Record<SyncEventScope, true>;

function isSyncEventScope(value: unknown): value is SyncEventScope {
	return typeof value === "string" && Object.hasOwn(SYNC_EVENT_SCOPES, value);
}

/**
 * Drops a hint whose shape does not match. Dropping is not free: `syncPollInterval` turns polling
 * off entirely while the stream is healthy and no job is running, so nothing refetches the queries
 * that hint would have refreshed until the next hint, or a navigation.
 */
function parseHint(payload: string): SyncEventHint | undefined {
	let decoded: unknown;
	try {
		decoded = JSON.parse(payload);
	} catch {
		return undefined;
	}
	if (!isRecord(decoded)) {
		return undefined;
	}
	const { scope, connectionId } = decoded;
	if (!isSyncEventScope(scope) || typeof connectionId !== "number") {
		return undefined;
	}
	return { scope, connectionId };
}

/** Backoff ladder for manual reconnects: 1s, 2s, 4s … capped, each scaled by 0.5–1.0× jitter. */
const RECONNECT_BASE_MS = 1000;
const RECONNECT_CAP_MS = 30_000;

/**
 * Consecutive failed connects before the surface reports live updates gone. One is a blip — a proxy
 * restart reconnects within a second — so tolerate it silently.
 */
const FAILURES_BEFORE_DEGRADED = 2;

/** Floor between catch-up resyncs, so a flapping stream cannot storm the cache. */
const RESYNC_THROTTLE_MS = 30_000;

/** Trailing window used to coalesce hint bursts from a chatty job. */
const HINT_DEBOUNCE_MS = 300;

/**
 * The query families this section owns, identified by the generated `_id` rather than a hand-typed
 * string so a renamed operation breaks the build instead of silently un-scoping the resync.
 */
function integrationQueryFamilyIds(workspaceSlug: string): ReadonlySet<string> {
	const path = { workspaceSlug };
	const connectionPath = { workspaceSlug, connectionId: 0 };
	return new Set(
		[
			getIntegrationCatalogQueryKey({ path }),
			getWorkspaceQueryKey({ path }),
			listQueryKey({ path }),
			listOutlineCollectionsQueryKey({ path }),
			getOutlineTokenStatusQueryKey({ path }),
			listSlackChannelsQueryKey({ path }),
			listSlackChannelCandidatesQueryKey({ path }),
			getRepositoriesToMonitorQueryKey({ path }),
			getConnectionSyncStatusQueryKey({ path: connectionPath }),
			listConnectionSyncJobsQueryKey({ path: connectionPath }),
			listConnectionSyncResourcesQueryKey({ path: connectionPath }),
		].map(([key]) => key._id),
	);
}

/** Resolve a hint's connection to its integration kind from whatever catalog the page has cached. */
function connectionKindOf(
	queryClient: QueryClient,
	workspaceSlug: string,
	connectionId: number,
): IntegrationCatalogEntry["kind"] | undefined {
	const catalog = queryClient.getQueryData<IntegrationCatalogEntry[]>(
		getIntegrationCatalogQueryKey({ path: { workspaceSlug } }),
	);
	const entry = catalog?.find((candidate) => candidate.connectionId === connectionId);
	if (entry) {
		return entry.kind;
	}

	const connections = queryClient.getQueryData<ConnectionSummary[]>(
		listQueryKey({ path: { workspaceSlug } }),
	);
	return connections?.find((candidate) => candidate.id === connectionId)?.kind;
}

/** Invalidates integration queries from workspace-scoped SSE hints. REST remains authoritative. */
export function useSyncEvents(workspaceSlug: string | undefined): boolean {
	const queryClient = useQueryClient();
	const [livePushUnavailable, setLivePushUnavailable] = useState(false);

	// Each workspace gets its own stream, so the previous one's failures say nothing about this one.
	// Reset during render rather than in the effect below, which would report the old stream degraded
	// for one paint after the switch.
	const [streamedSlug, setStreamedSlug] = useState(workspaceSlug);
	if (streamedSlug !== workspaceSlug) {
		setStreamedSlug(workspaceSlug);
		setLivePushUnavailable(false);
	}

	useEffect(() => {
		if (!workspaceSlug) {
			return;
		}

		let source: EventSource | null = null;
		let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
		let consecutiveFailures = 0;
		let hasEverOpened = false;
		let lastResyncAt = 0;
		let disposed = false;
		const hintTimers = new Map<string, ReturnType<typeof setTimeout>>();

		const invalidate = (queryKey: readonly unknown[]) =>
			queryClient.invalidateQueries({ queryKey });

		/**
		 * Catch-up after a stream gap. Hints carry no ids, so the browser cannot replay them with
		 * `Last-Event-ID`; anything missed while disconnected is lost, and marking this section's
		 * queries stale is the substitute. Scoped to the families we own, never the whole workspace,
		 * because `invalidateQueries` cancels in-flight fetches.
		 */
		const resyncIntegrationQueries = () => {
			const familyIds = integrationQueryFamilyIds(workspaceSlug);
			void queryClient.invalidateQueries({
				predicate: ({ queryKey }) => {
					const id = queryOperationId(queryKey);
					if (id === undefined || !familyIds.has(id)) {
						return false;
					}
					const [key] = queryKey;
					if (!isRecord(key) || !isRecord(key.path)) {
						return false;
					}
					return key.path.workspaceSlug === workspaceSlug;
				},
			});
		};

		const applyHint = ({ scope, connectionId }: SyncEventHint) => {
			switch (scope) {
				case "job": {
					void invalidate(
						getConnectionSyncStatusQueryKey({ path: { workspaceSlug, connectionId } }),
					);
					void invalidate(
						listConnectionSyncJobsQueryKey({ path: { workspaceSlug, connectionId } }),
					);
					break;
				}
				case "resources": {
					void invalidate(
						listConnectionSyncResourcesQueryKey({ path: { workspaceSlug, connectionId } }),
					);
					void invalidate(
						getConnectionSyncStatusQueryKey({ path: { workspaceSlug, connectionId } }),
					);
					// Only the catalog for this connection's integration changed; a GitHub repo-sync
					// hint says nothing about Slack channels or Outline collections.
					const kind = connectionKindOf(queryClient, workspaceSlug, connectionId);
					if (kind === "OUTLINE" || kind === undefined) {
						void invalidate(listOutlineCollectionsQueryKey({ path: { workspaceSlug } }));
					}
					if (kind === "SLACK" || kind === undefined) {
						void invalidate(listSlackChannelsQueryKey({ path: { workspaceSlug } }));
					}
					break;
				}
				case "connection": {
					// A connect/disconnect moves the catalog, the workspace record and the connection list
					// together, so this is the one hint that touches the whole section.
					resyncIntegrationQueries();
					break;
				}
				case "activity": {
					void invalidate(
						getConnectionSyncStatusQueryKey({ path: { workspaceSlug, connectionId } }),
					);
					break;
				}
				default: {
					break;
				}
			}
		};

		const handleHint = (event: MessageEvent<string>) => {
			const hint = parseHint(event.data);
			if (!hint) {
				return;
			}

			// A running job emits progress hints far faster than a human can read them; collapse each
			// burst to one refetch per scope per connection.
			const timerKey = `${hint.scope}:${hint.connectionId}`;
			const pending = hintTimers.get(timerKey);
			if (pending) {
				clearTimeout(pending);
			}
			hintTimers.set(
				timerKey,
				setTimeout(() => {
					hintTimers.delete(timerKey);
					applyHint(hint);
				}, HINT_DEBOUNCE_MS),
			);
		};

		/**
		 * One controller per connection, not one per hook: an `AbortSignal` is one-shot, and
		 * `addEventListener(…, { signal })` with an already-aborted signal attaches nothing at all,
		 * silently. Reusing a controller across reconnects leaves every stream after the first deaf.
		 */
		const controllers = new WeakMap<EventSource, AbortController>();

		const detach = (target: EventSource) => {
			controllers.get(target)?.abort();
			controllers.delete(target);
			target.close();
		};

		const connect = () => {
			const current = new EventSource(
				`${environment.serverUrl}/workspaces/${workspaceSlug}/sync/events`,
				{ withCredentials: true },
			);
			source = current;
			const controller = new AbortController();
			controllers.set(current, controller);
			const { signal } = controller;

			current.addEventListener(
				"open",
				() => {
					consecutiveFailures = 0;
					setLivePushUnavailable(false);

					// oxlint-disable-next-line no-restricted-properties -- Measures RESYNC_THROTTLE_MS from the previous open, inside a DOM listener; `useNow`'s shared tick is the same order as that window and could not resolve it.
					const now = Date.now();
					// The first open races the page's own mount fetches, which are already loading this
					// data — resyncing here would cancel and restart them. Record the timestamp anyway so
					// an immediate re-open is throttled against the mount.
					const isFirstOpen = !hasEverOpened;
					hasEverOpened = true;
					if (isFirstOpen || now - lastResyncAt < RESYNC_THROTTLE_MS) {
						if (isFirstOpen) {
							lastResyncAt = now;
						}
						return;
					}
					lastResyncAt = now;
					resyncIntegrationQueries();
				},
				{ signal },
			);

			current.addEventListener("sync", handleHint, { signal });

			current.addEventListener(
				"error",
				() => {
					// Only act on CLOSED. CONNECTING means the browser is already auto-retrying a network
					// error (the HTML spec makes that automatic), so stay out of it. CLOSED means the
					// connection failed — the spec reaches it for any non-200 or wrong Content-Type — and
					// the browser never retries that: one 502 during a deploy or one 401 on an expired
					// session ends live updates for the session unless we reconnect ourselves.
					if (current.readyState !== EventSource.CLOSED) {
						return;
					}

					consecutiveFailures += 1;
					if (consecutiveFailures >= FAILURES_BEFORE_DEGRADED) {
						setLivePushUnavailable(true);
					}

					detach(current);
					if (disposed) {
						return;
					}

					const backoff = Math.min(
						RECONNECT_CAP_MS,
						RECONNECT_BASE_MS * 2 ** (consecutiveFailures - 1),
					);
					// Jitter keeps every admin tab from re-storming the server on the same tick after a
					// shared outage.
					reconnectTimer = setTimeout(connect, backoff * (0.5 + Math.random() * 0.5));
				},
				{ signal },
			);
		};

		connect();

		return () => {
			disposed = true;
			if (reconnectTimer) {
				clearTimeout(reconnectTimer);
			}
			for (const timer of hintTimers.values()) {
				clearTimeout(timer);
			}
			hintTimers.clear();
			if (source) {
				detach(source);
			}
		};
	}, [workspaceSlug, queryClient]);

	return livePushUnavailable;
}
