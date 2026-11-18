import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";

import { prefetchPageRead } from "./prefetch-page-read";

// As the app's client, with a retry that takes no time: reads retry, and a datum stays fresh a while.
const client = () =>
	new QueryClient({
		defaultOptions: { queries: { retry: 1, retryDelay: 0, staleTime: 30_000 } },
	});

const KEY = ["teams", { workspaceSlug: "acme" }];

const unreachable = () =>
	vi.fn(async (): Promise<string[]> => {
		throw new Error("unreachable");
	});

describe("prefetchPageRead", () => {
	it("puts the page's data in the cache before the page mounts", async () => {
		const queryClient = client();
		const queryFn = vi.fn(async () => ["Core"]);

		prefetchPageRead(queryClient, { queryKey: KEY, queryFn });

		await vi.waitFor(() => expect(queryClient.getQueryData(KEY)).toStrictEqual(["Core"]));
		expect(queryFn).toHaveBeenCalledOnce();
	});

	it("leaves a failed read to the page, after the client's own retries", async () => {
		const queryClient = client();
		const queryFn = unreachable();

		prefetchPageRead(queryClient, { queryKey: KEY, queryFn });

		// The page finds no entry, so it reads with its own retries and shows its own error.
		await vi.waitFor(() =>
			expect(queryClient.getQueryCache().find({ queryKey: KEY, exact: true })).toBeUndefined(),
		);
		expect(queryFn).toHaveBeenCalledTimes(2);
	});

	it("keeps data that a failed refresh would otherwise replace", async () => {
		const queryClient = client();
		queryClient.setQueryData(KEY, ["Core"], { updatedAt: 0 });
		const queryFn = unreachable();

		prefetchPageRead(queryClient, { queryKey: KEY, queryFn });

		await vi.waitFor(() => expect(queryClient.getQueryState(KEY)?.status).toBe("error"));
		expect(queryClient.getQueryData(KEY)).toStrictEqual(["Core"]);
	});

	it("keeps a failed entry that a mounted page observes", async () => {
		const queryClient = client();
		const queryFn = unreachable();
		const unsubscribe = new QueryObserver(queryClient, {
			queryKey: KEY,
			queryFn,
			enabled: false,
		}).subscribe(() => undefined);

		prefetchPageRead(queryClient, { queryKey: KEY, queryFn });

		await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
		await vi.waitFor(() => expect(queryClient.getQueryState(KEY)?.status).toBe("error"));
		unsubscribe();
	});

	it("does not read fresh data again", async () => {
		const queryClient = client();
		queryClient.setQueryData(KEY, ["Core"]);
		const queryFn = vi.fn(async () => ["Platform"]);

		prefetchPageRead(queryClient, { queryKey: KEY, queryFn });
		await Promise.resolve();

		expect(queryFn).not.toHaveBeenCalled();
		expect(queryClient.getQueryData(KEY)).toStrictEqual(["Core"]);
	});
});
