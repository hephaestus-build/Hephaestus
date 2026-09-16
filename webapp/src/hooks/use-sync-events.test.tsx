import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

import {
	getConnectionSyncStatusQueryKey,
	getIntegrationCatalogQueryKey,
	getUserProfileQueryKey,
	getWorkspaceQueryKey,
	listConnectionSyncJobsQueryKey,
	listConnectionSyncResourcesQueryKey,
	listOutlineCollectionsQueryKey,
	listSlackChannelsQueryKey,
} from "@/api/@tanstack/react-query.gen";

import { useSyncEvents } from "./use-sync-events";

const WORKSPACE = "test-workspace";
const CONNECTION_ID = 42;
const HINT_DEBOUNCE_MS = 300;

/**
 * Extends `EventTarget`, so listener bookkeeping — including `{ signal }` removal, which the hook
 * relies on to detach a connection — is the platform's rather than this double's.
 */
class FakeEventSource extends EventTarget {
	static readonly CONNECTING = 0;
	static readonly OPEN = 1;
	static readonly CLOSED = 2;
	static instances: FakeEventSource[] = [];

	readyState = 1;
	closed = false;

	readonly url: string;
	readonly options?: EventSourceInit;

	constructor(url: string, options?: EventSourceInit) {
		super();
		this.url = url;
		this.options = options;
		FakeEventSource.instances.push(this);
	}

	close() {
		this.closed = true;
	}

	open() {
		this.dispatchEvent(new Event("open"));
	}

	emit(scope: string, connectionId = CONNECTION_ID) {
		this.emitRaw(JSON.stringify({ scope, connectionId }));
	}

	/** Deliver a raw (possibly non-JSON) payload, to exercise the malformed-hint guard. */
	emitRaw(data: string) {
		this.dispatchEvent(new MessageEvent("sync", { data }));
	}

	/** An `error` event without moving `readyState` — a CONNECTING stream the browser is retrying. */
	error() {
		this.dispatchEvent(new Event("error"));
	}

	/** The spec's "fail the connection" path: any non-200 or wrong Content-Type. No browser retry. */
	fail() {
		this.readyState = FakeEventSource.CLOSED;
		this.error();
	}
}

function wrapper(queryClient: QueryClient) {
	return function QueryWrapper({ children }: { children: ReactNode }) {
		return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
	};
}

/** Latest source the hook opened — reconnects push a new instance. */
function latestSource(): FakeEventSource {
	const source = FakeEventSource.instances.at(-1);
	if (!source) {
		throw new Error("Expected the hook to have opened a stream");
	}
	return source;
}

/**
 * Let the pending reconnect fire. The ladder is 1s·2ⁿ⁻¹ scaled by 0.5–1.0× jitter, so 5s clears any of the
 * first three attempts — the only depth these tests reach. Fake timers move `Date.now()` too, and
 * that is deliberate here: an early flap really does reconnect seconds later, which is what makes
 * the resync throttle bite.
 */
function runReconnectBackoff() {
	act(() => {
		vi.advanceTimersByTime(5000);
	});
}

function flushHintDebounce() {
	act(() => {
		vi.advanceTimersByTime(HINT_DEBOUNCE_MS);
	});
}

describe("useSyncEvents", () => {
	beforeEach(() => {
		FakeEventSource.instances = [];
		vi.stubGlobal("EventSource", FakeEventSource);
		vi.useFakeTimers();
	});

	afterEach(() => {
		vi.useRealTimers();
		vi.unstubAllGlobals();
	});

	it("opens one credentialed workspace stream", () => {
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(new QueryClient()) });

		expect(FakeEventSource.instances).toHaveLength(1);
		expect(latestSource().url).toBe(`http://localhost:8080/workspaces/${WORKSPACE}/sync/events`);
		expect(latestSource().options).toStrictEqual({ withCredentials: true });
	});

	it("does not resync on the first open, because that would cancel the page's own mount fetches", () => {
		const queryClient = new QueryClient();
		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });

		act(() => latestSource().open());

		expect(invalidate).not.toHaveBeenCalled();
	});

	it("resyncs on re-open, scoped to this workspace's integration queries and nothing else", () => {
		const queryClient = new QueryClient();
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });
		act(() => latestSource().open());

		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		act(() => latestSource().fail());
		runReconnectBackoff();
		// A re-open more than the throttle window after the first one must catch up on whatever the
		// stream dropped while it was down.
		// oxlint-disable-next-line no-restricted-properties -- Reads the faked clock to move it forward: `setSystemTime` takes an absolute instant, so the jump has to be measured from the current one.
		vi.setSystemTime(Date.now() + 60_000);
		act(() => latestSource().open());

		const filter = invalidate.mock.calls[0]?.[0]?.predicate;
		expect(filter).toBeTypeOf("function");
		assert(filter, "Expected a scoped predicate");

		const included = listConnectionSyncJobsQueryKey({
			path: { workspaceSlug: WORKSPACE, connectionId: CONNECTION_ID },
		});
		const otherWorkspace = listConnectionSyncJobsQueryKey({
			path: { workspaceSlug: "another-workspace", connectionId: CONNECTION_ID },
		});
		// Not an integration query: a workspace-wide predicate would sweep this in; the scoped predicate must not.
		const unrelated = getUserProfileQueryKey({ path: { workspaceSlug: WORKSPACE, login: "ada" } });
		for (const key of [included, otherWorkspace, unrelated]) {
			queryClient.setQueryData(key, []);
		}

		const find = (key: readonly unknown[]) => {
			const query = queryClient.getQueryCache().find({ queryKey: key, exact: true });
			assert(query, "Expected a cached query");
			return query;
		};
		expect(filter(find(included))).toBe(true);
		expect(filter(find(otherWorkspace))).toBe(false);
		expect(filter(find(unrelated))).toBe(false);
	});

	it("throttles catch-up resyncs so a flapping stream cannot storm the cache", () => {
		const queryClient = new QueryClient();
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });
		act(() => latestSource().open());

		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		// Two reconnects back to back, both well inside the 30s throttle window.
		for (let i = 0; i < 2; i += 1) {
			act(() => latestSource().fail());
			runReconnectBackoff();
			act(() => latestSource().open());
		}

		expect(invalidate).not.toHaveBeenCalled();
	});

	it("reconnects itself after a failed connection, which the browser never retries", () => {
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(new QueryClient()) });
		act(() => latestSource().open());
		const first = latestSource();

		act(() => first.fail());
		expect(first.closed).toBe(true);
		runReconnectBackoff();

		expect(FakeEventSource.instances).toHaveLength(2);
		expect(latestSource()).not.toBe(first);
	});

	it("stops listening to a superseded stream, so a late event from it cannot move the UI", () => {
		const { result } = renderHook(() => useSyncEvents(WORKSPACE), {
			wrapper: wrapper(new QueryClient()),
		});
		act(() => latestSource().open());
		const first = latestSource();

		// Fail twice to reach the degraded state, then let the replacement connect.
		act(() => first.fail());
		runReconnectBackoff();
		act(() => latestSource().fail());
		runReconnectBackoff();
		expect(result.current).toBe(true);

		// The dead stream reporting success must not clear the flag its replacement still owns.
		act(() => first.open());

		expect(result.current).toBe(true);
	});

	it("waits out an exponential, jittered backoff that grows after each successive failure", async () => {
		// Pin the jitter so the rungs are deterministic: delay = rung * (0.5 + 0.5·random) = rung · 0.75.
		const randomSpy = vi.spyOn(Math, "random").mockReturnValue(0.5);
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(new QueryClient()) });
		act(() => latestSource().open());

		// First failure sits on the 1s rung → 750ms; the 500ms checkpoint proves the delay is a real
		// backoff, not a flat setTimeout(connect, 0) that would reopen after any advance at all.
		act(() => latestSource().fail());
		await act(() => vi.advanceTimersByTime(500));
		expect(FakeEventSource.instances).toHaveLength(1);
		await act(() => vi.advanceTimersByTime(250));
		expect(FakeEventSource.instances).toHaveLength(2);

		// The reconnect fails again before it can open, so the exponent climbs: 2s rung → 1500ms, which
		// is strictly longer than the first delay — 750ms is no longer enough.
		act(() => latestSource().fail());
		await act(() => vi.advanceTimersByTime(750));
		expect(FakeEventSource.instances).toHaveLength(2);
		await act(() => vi.advanceTimersByTime(750));
		expect(FakeEventSource.instances).toHaveLength(3);

		randomSpy.mockRestore();
	});

	it("leaves a CONNECTING stream alone — the browser is already retrying that one", () => {
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(new QueryClient()) });
		const source = latestSource();
		act(() => source.open());

		source.readyState = FakeEventSource.CONNECTING;
		act(() => source.error());
		runReconnectBackoff();

		expect(FakeEventSource.instances).toHaveLength(1);
		expect(source.closed).toBe(false);
	});

	it("reports live push as unavailable only once a retry has also failed, and clears it on recovery", () => {
		const { result } = renderHook(() => useSyncEvents(WORKSPACE), {
			wrapper: wrapper(new QueryClient()),
		});
		act(() => latestSource().open());

		// One blip is not worth telling the admin about — the reconnect usually wins within a second.
		act(() => latestSource().fail());
		expect(result.current).toBe(false);

		runReconnectBackoff();
		act(() => latestSource().fail());
		expect(result.current).toBe(true);

		runReconnectBackoff();
		act(() => latestSource().open());
		expect(result.current).toBe(false);
	});

	it("maps a job hint to the status and history of that connection", () => {
		const queryClient = new QueryClient();
		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });

		act(() => latestSource().emit("job"));
		flushHintDebounce();

		expect(invalidate).toHaveBeenCalledWith({
			queryKey: getConnectionSyncStatusQueryKey({
				path: { workspaceSlug: WORKSPACE, connectionId: CONNECTION_ID },
			}),
		});
		expect(invalidate).toHaveBeenCalledWith({
			queryKey: listConnectionSyncJobsQueryKey({
				path: { workspaceSlug: WORKSPACE, connectionId: CONNECTION_ID },
			}),
		});
	});

	it("collapses a burst of hints from a chatty job into one refetch per scope", () => {
		const queryClient = new QueryClient();
		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });

		act(() => {
			for (let i = 0; i < 5; i += 1) {
				latestSource().emit("job");
			}
		});
		flushHintDebounce();

		// One status + one jobs invalidation, not five of each.
		expect(invalidate).toHaveBeenCalledTimes(2);
	});

	it("keeps a resources hint inside its own integration instead of sweeping the siblings", () => {
		const queryClient = new QueryClient();
		queryClient.setQueryData(
			getIntegrationCatalogQueryKey({ path: { workspaceSlug: WORKSPACE } }),
			[
				{ kind: "OUTLINE", connected: true, connectionId: CONNECTION_ID, displayName: "Outline" },
				{ kind: "SLACK", connected: true, connectionId: 99, displayName: "Slack" },
			],
		);
		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });

		act(() => latestSource().emit("resources"));
		flushHintDebounce();

		expect(invalidate).toHaveBeenCalledWith({
			queryKey: listConnectionSyncResourcesQueryKey({
				path: { workspaceSlug: WORKSPACE, connectionId: CONNECTION_ID },
			}),
		});
		expect(invalidate).toHaveBeenCalledWith({
			queryKey: listOutlineCollectionsQueryKey({ path: { workspaceSlug: WORKSPACE } }),
		});
		// The hint belongs to the Outline connection; Slack's channel list has nothing to do with it.
		expect(invalidate).not.toHaveBeenCalledWith({
			queryKey: listSlackChannelsQueryKey({ path: { workspaceSlug: WORKSPACE } }),
		});
	});

	it("falls back to both catalogs when the connection's kind is not cached yet", () => {
		const queryClient = new QueryClient();
		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });

		act(() => latestSource().emit("resources"));
		flushHintDebounce();

		expect(invalidate).toHaveBeenCalledWith({
			queryKey: listOutlineCollectionsQueryKey({ path: { workspaceSlug: WORKSPACE } }),
		});
		expect(invalidate).toHaveBeenCalledWith({
			queryKey: listSlackChannelsQueryKey({ path: { workspaceSlug: WORKSPACE } }),
		});
	});

	it("treats a connection hint as a whole-section resync", () => {
		const queryClient = new QueryClient();
		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });

		// A connect/disconnect moves the catalog, the workspace record and the connection list together,
		// so this hint invalidates the whole integration section via the scoped predicate.
		act(() => latestSource().emit("connection"));
		flushHintDebounce();

		const predicate = invalidate.mock.calls[0]?.[0]?.predicate;
		expect(predicate).toBeTypeOf("function");
		assert(predicate, "Expected a scoped predicate");

		const workspaceKey = getWorkspaceQueryKey({ path: { workspaceSlug: WORKSPACE } });
		queryClient.setQueryData(workspaceKey, {});
		const query = queryClient.getQueryCache().find({ queryKey: workspaceKey, exact: true });
		assert(query, "Expected a cached query");
		expect(predicate(query)).toBe(true);
	});

	it("maps an activity hint to just the connection's status, not its whole history", () => {
		const queryClient = new QueryClient();
		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });

		act(() => latestSource().emit("activity"));
		flushHintDebounce();

		expect(invalidate).toHaveBeenCalledWith({
			queryKey: getConnectionSyncStatusQueryKey({
				path: { workspaceSlug: WORKSPACE, connectionId: CONNECTION_ID },
			}),
		});
		// An activity ping is lightweight — it must not sweep the jobs history the way a job hint does.
		expect(invalidate).not.toHaveBeenCalledWith({
			queryKey: listConnectionSyncJobsQueryKey({
				path: { workspaceSlug: WORKSPACE, connectionId: CONNECTION_ID },
			}),
		});
	});

	// The dangerous shape of contract drift: the scope alone picks a branch, so an unchecked hint
	// would refetch under a connection id that identifies nothing.
	it("drops a hint whose connection id came over as a string instead of acting on half of it", () => {
		const queryClient = new QueryClient();
		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });

		act(() => latestSource().emitRaw(JSON.stringify({ scope: "job", connectionId: "42" })));
		flushHintDebounce();

		expect(invalidate).not.toHaveBeenCalled();
	});

	it("swallows a malformed hint payload and keeps acting on the next readable one", () => {
		const queryClient = new QueryClient();
		const invalidate = vi.spyOn(queryClient, "invalidateQueries");
		renderHook(() => useSyncEvents(WORKSPACE), { wrapper: wrapper(queryClient) });

		act(() => latestSource().emitRaw("}{ not json"));
		flushHintDebounce();

		expect(invalidate).not.toHaveBeenCalled();

		act(() => latestSource().emit("activity"));
		flushHintDebounce();

		expect(invalidate).toHaveBeenCalledWith({
			queryKey: getConnectionSyncStatusQueryKey({
				path: { workspaceSlug: WORKSPACE, connectionId: CONNECTION_ID },
			}),
		});
	});

	it("closes the stream and stops reconnecting on unmount", () => {
		const { unmount } = renderHook(() => useSyncEvents(WORKSPACE), {
			wrapper: wrapper(new QueryClient()),
		});
		const source = latestSource();
		act(() => source.open());
		act(() => source.fail());

		unmount();
		runReconnectBackoff();

		expect(source.closed).toBe(true);
		expect(FakeEventSource.instances).toHaveLength(1);
	});
});
