import { QueryClient, QueryClientProvider, useMutation, useQuery } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, expect, it } from "vitest";

import { deferred } from "@/test/async";

import { usePageIdle } from "./page-idle";

afterEach(() => {
	delete document.documentElement.dataset.pageIdle;
});

function harness() {
	const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	const wrapper = ({ children }: { children: ReactNode }) =>
		createElement(QueryClientProvider, { client }, children);
	return { client, wrapper };
}

const idle = () => document.documentElement.dataset.pageIdle;

const FIRST_KEY = ["first-load"];
const POLLED_KEY = ["polled-read"];

it("marks the page idle only after its first loads answer", async () => {
	const { wrapper } = harness();
	const answer = deferred<string>();
	renderHook(
		() => {
			useQuery({ queryKey: FIRST_KEY, queryFn: async () => answer.promise });
			usePageIdle(false);
		},
		{ wrapper },
	);
	expect(idle()).toBeUndefined();
	await act(async () => {
		answer.resolve("ready");
	});
	await waitFor(() => {
		expect(idle()).toBe("true");
	});
});

it("stays idle while a poll refetches data that is already shown", async () => {
	const { client, wrapper } = harness();
	client.setQueryData(POLLED_KEY, "shown");
	const refetch = deferred<string>();
	renderHook(
		() => {
			useQuery({ queryKey: POLLED_KEY, queryFn: async () => refetch.promise, staleTime: 0 });
			usePageIdle(false);
		},
		{ wrapper },
	);
	await waitFor(() => {
		expect(client.isFetching({ queryKey: POLLED_KEY })).toBe(1);
	});
	await waitFor(() => {
		expect(idle()).toBe("true");
	});
	refetch.resolve("new");
});

it("is not idle while a route loads or a write is pending", async () => {
	const { wrapper } = harness();
	const write = deferred<undefined>();
	const { result, rerender } = renderHook(
		({ routeLoading }: { routeLoading: boolean }) => {
			const mutation = useMutation({ mutationFn: async () => write.promise });
			usePageIdle(routeLoading);
			return mutation;
		},
		{ wrapper, initialProps: { routeLoading: true } },
	);
	expect(idle()).toBeUndefined();
	rerender({ routeLoading: false });
	await waitFor(() => {
		expect(idle()).toBe("true");
	});
	act(() => {
		result.current.mutate();
	});
	await waitFor(() => {
		expect(idle()).toBeUndefined();
	});
	await act(async () => {
		write.resolve(undefined);
	});
	await waitFor(() => {
		expect(idle()).toBe("true");
	});
});
