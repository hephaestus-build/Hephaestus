import {
	onlineManager,
	type QueryClient,
	QueryClientProvider,
	useQuery,
} from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { problemStatusOf } from "@/lib/problem-detail";
import { USER_VIEW_STORAGE_KEY, type UserViewSession } from "@/runtime/user-view/session";

import { getContext } from "./root-provider";
import { useQueryReadRefusal } from "./use-query-read-refusal";

beforeEach(() => {
	getContext().queryClient.clear();
});

afterEach(() => {
	getContext().queryClient.clear();
	onlineManager.setOnline(true);
});

type Answer = { data: string } | { error: Error };

/** What the generated client throws for a non-2xx answer: the problem body, with its status. */
function failed(status: number): { error: Error } {
	return { error: Object.assign(new Error(`HTTP ${status}`), { status }) };
}

const outage = failed(503);

/** A read whose answers the test queues: every request, retries included, takes the next one. */
function queuedRead(...initial: Answer[]) {
	const answers = [...initial];
	const read = {
		answers,
		requests: 0,
		queryFn: async () => {
			read.requests += 1;
			const answer = answers.shift();
			if (answer === undefined) {
				throw new Error("No answer queued");
			}
			if ("error" in answer) {
				throw answer.error;
			}
			return answer.data;
		},
	};
	return read;
}

const READ_KEY = ["read-under-retry-policy"];

it("settles an offline read as an error without waiting for reconnect or retries", async () => {
	onlineManager.setOnline(false);
	const failure = new TypeError("Failed to fetch");
	const read = queuedRead({ error: failure });
	const { result } = renderRead(getContext().queryClient, read);
	await waitFor(() => expect(result.current.isError).toBe(true));
	expect(result.current.error).toBe(failure);
	expect(result.current.fetchStatus).toBe("idle");
	expect(read.requests).toBe(1);
});

it("rejects an offline write and never sends it again on reconnect", async () => {
	const { queryClient } = getContext();
	queryClient.mount();
	try {
		onlineManager.setOnline(false);
		const write = vi.fn().mockRejectedValue(new TypeError("Failed to fetch"));
		const mutation = queryClient.getMutationCache().build(queryClient, { mutationFn: write });
		await expect(mutation.execute(undefined)).rejects.toThrow("Failed to fetch");
		expect(mutation.state.status).toBe("error");
		expect(mutation.state.isPaused).toBe(false);
		onlineManager.setOnline(true);
		await queryClient.resumePausedMutations();
		expect(write).toHaveBeenCalledOnce();
	} finally {
		queryClient.unmount();
	}
});

/** The read as a screen holds it: the client's own retry policy, and its refusal eviction. */
function renderRead(queryClient: QueryClient, read: ReturnType<typeof queuedRead>) {
	const queryKey = READ_KEY;
	return renderHook(
		() => {
			// Only the wait between attempts is shortened; whether to retry is the client's.
			const query = useQuery({ queryKey, queryFn: read.queryFn, retryDelay: 0 });
			useQueryReadRefusal(queryKey, query.isError, query.error);
			return query;
		},
		{
			wrapper: ({ children }: { children: ReactNode }) =>
				createElement(QueryClientProvider, { client: queryClient }, children),
		},
	);
}

it("settles a refused read at its first answer, so later outages cannot bring the cached answer back", async () => {
	const read = queuedRead({ data: "cached" });
	const { result } = renderRead(getContext().queryClient, read);
	await waitFor(() => expect(result.current.data).toBe("cached"));

	read.answers.push(failed(403), outage, outage, outage);
	await act(async () => {
		await result.current.refetch();
	});
	expect(read.requests).toBe(2);
	await waitFor(() => expect(problemStatusOf(result.current.error)).toBe(403));
	expect(read.answers).toHaveLength(3);
	await waitFor(() => expect(result.current.data).toBeUndefined());

	read.answers.push(outage);
	await act(async () => {
		await result.current.refetch();
	});
	expect(read.requests).toBe(6);
	await waitFor(() => expect(problemStatusOf(result.current.error)).toBe(503));
	expect(result.current.data).toBeUndefined();
	expect(result.current.isRefetchError).toBe(false);

	read.answers.push({ data: "fresh" });
	await act(async () => {
		await result.current.refetch();
	});
	await waitFor(() => expect(result.current.data).toBe("fresh"));
	expect(read.requests).toBe(7);
});

it("retries a network error, a 429 and an outage before an answer, and gives up after three retries", async () => {
	const read = queuedRead({ error: new TypeError("Failed to fetch") }, failed(429), outage, {
		data: "answer",
	});
	const { result } = renderRead(getContext().queryClient, read);
	await waitFor(() => expect(result.current.data).toBe("answer"));
	expect(read.requests).toBe(4);

	read.answers.push(outage, outage, outage, outage, { data: "never asked" });
	await act(async () => {
		await result.current.refetch();
	});
	expect(read.requests).toBe(8);
	await waitFor(() => expect(problemStatusOf(result.current.error)).toBe(503));
	expect(result.current.data).toBe("answer");
	expect(read.answers).toStrictEqual([{ data: "never asked" }]);
});

it("never retries a read in a user view", async () => {
	sessionStorage.setItem(
		USER_VIEW_STORAGE_KEY,
		JSON.stringify({
			operatorAccountId: 1,
			workspaceSlug: "acme",
			workspaceName: "Acme",
			accountId: "2",
			providerLogin: "grace",
			name: "Grace Hopper",
			unclaimed: false,
			reason: "Support request",
		} satisfies UserViewSession),
	);
	try {
		// The client decides from the session it starts with, as a user view starts with a page load.
		vi.resetModules();
		const viewed = await import("./root-provider");
		const { queryClient } = viewed.getContext();
		const read = queuedRead(outage, { data: "never asked" });
		const query = queryClient
			.getQueryCache()
			.build(queryClient, { queryKey: READ_KEY, queryFn: read.queryFn, retryDelay: 0 });
		await expect(query.fetch()).rejects.toBe(outage.error);
		expect(read.requests).toBe(1);
	} finally {
		sessionStorage.removeItem(USER_VIEW_STORAGE_KEY);
	}
});
