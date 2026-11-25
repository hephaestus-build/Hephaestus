import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { deferred } from "@/test/async";

import { installPageIdle, PAGE_IDLE_SETTLE_MS } from "./page-idle";

const FIRST_KEY = ["first-load"];
const POLLED_KEY = ["polled-read"];
const OTHER_PAGE_KEY = ["other-page"];

/** The router's loading state and its events, driven by the test. */
function fakeRouter() {
	const listeners = new Set<() => void>();
	const router = {
		state: { status: "idle", isLoading: false },
		subscribe: (_event: string, listener: () => void) => {
			listeners.add(listener);
			return () => listeners.delete(listener);
		},
	};
	const load = (loading: boolean) => {
		router.state = { status: loading ? "pending" : "idle", isLoading: loading };
		for (const listener of listeners) {
			listener();
		}
	};
	return { router, load };
}

let stop: () => void = () => undefined;

beforeEach(() => {
	vi.useFakeTimers();
});

afterEach(() => {
	stop();
	vi.useRealTimers();
});

const mark = () => document.documentElement.dataset.pageIdle;

function install(client: QueryClient) {
	const { router, load } = fakeRouter();
	// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- the fake carries only what installPageIdle reads.
	stop = installPageIdle(router as unknown as Parameters<typeof installPageIdle>[0], client);
	return load;
}

/** A query that something on the page observes, as `useQuery` makes one. */
function observe<T>(client: QueryClient, queryKey: string[], queryFn: () => Promise<T>) {
	return new QueryObserver(client, { queryKey, queryFn, retry: false }).subscribe(() => undefined);
}

it("marks the page idle only after its first load answers, and only after the hold", async () => {
	const client = new QueryClient();
	install(client);
	const answer = deferred<string>();
	const unobserve = observe(client, FIRST_KEY, async () => answer.promise);
	expect(mark(), "the fetch takes the mark at once").toBeUndefined();
	await vi.advanceTimersByTimeAsync(PAGE_IDLE_SETTLE_MS * 4);
	expect(mark()).toBeUndefined();
	answer.resolve("ready");
	await vi.advanceTimersByTimeAsync(PAGE_IDLE_SETTLE_MS - 10);
	expect(mark(), "not before the hold").toBeUndefined();
	await vi.advanceTimersByTimeAsync(20);
	expect(mark()).toMatch(/^[\d.]+:\d+$/u);
	unobserve();
});

it("stays idle while a poll refetches data on screen and while another page is preloaded", async () => {
	const client = new QueryClient();
	client.setQueryData(POLLED_KEY, "shown");
	install(client);
	await vi.advanceTimersByTimeAsync(PAGE_IDLE_SETTLE_MS * 2);
	const idle = mark();
	expect(idle).toMatch(/^[\d.]+:\d+$/u);
	const refetch = deferred<string>();
	const unobserve = observe(client, POLLED_KEY, async () => refetch.promise);
	const preload = deferred<string>();
	void client
		.query({ queryKey: OTHER_PAGE_KEY, queryFn: async () => preload.promise })
		.catch(() => undefined);
	await vi.advanceTimersByTimeAsync(PAGE_IDLE_SETTLE_MS * 2);
	expect(client.isFetching({ queryKey: POLLED_KEY })).toBe(1);
	expect(client.isFetching({ queryKey: OTHER_PAGE_KEY })).toBe(1);
	expect(mark(), "the same idle period").toBe(idle);
	refetch.resolve("new");
	preload.resolve("later");
	unobserve();
});

it("is not idle while a route loads or a write is pending, and starts a new idle period after", async () => {
	const client = new QueryClient();
	const load = install(client);
	load(true);
	await vi.advanceTimersByTimeAsync(PAGE_IDLE_SETTLE_MS * 2);
	expect(mark(), "a route is loading").toBeUndefined();
	load(false);
	await vi.advanceTimersByTimeAsync(PAGE_IDLE_SETTLE_MS * 2);
	const first = mark();
	expect(first).toMatch(/^[\d.]+:\d+$/u);
	const write = deferred<undefined>();
	const mutation = client
		.getMutationCache()
		.build(client, { mutationFn: async () => write.promise })
		.execute(undefined);
	expect(mark(), "the write takes the mark at once").toBeUndefined();
	write.resolve(undefined);
	await mutation;
	await vi.advanceTimersByTimeAsync(PAGE_IDLE_SETTLE_MS * 2);
	expect(mark()).toMatch(/^[\d.]+:\d+$/u);
	expect(mark(), "a new idle period").not.toBe(first);
});

it("takes the mark away when it stops", async () => {
	install(new QueryClient());
	await vi.advanceTimersByTimeAsync(PAGE_IDLE_SETTLE_MS * 2);
	expect(mark()).toMatch(/^[\d.]+:\d+$/u);
	stop();
	expect(mark()).toBeUndefined();
});
