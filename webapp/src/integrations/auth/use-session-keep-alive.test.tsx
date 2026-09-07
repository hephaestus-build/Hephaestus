/* oxlint-disable no-restricted-properties -- Fixtures follow the fake session clock. */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { StrictMode, type ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { getCurrentUserQueryKey } from "@/api/@tanstack/react-query.gen";
import { server } from "@/mocks/server";
import { useSessionKeepAlive } from "./use-session-keep-alive";

function userPayload(expiresInSec: number) {
	return {
		id: 1,
		displayName: "Active Annie",
		appRole: "USER",
		status: "ACTIVE",
		impersonating: false,
		linkedProviders: [],
		roles: [],
		accessTokenExpiresAt: Math.floor(Date.now() / 1000) + expiresInSec,
	};
}

beforeEach(() => {
	vi.useFakeTimers({ toFake: ["Date", "setTimeout", "clearTimeout"] });
	vi.setSystemTime(new Date("2026-09-07T12:00:00Z"));
});
afterEach(() => vi.useRealTimers());

async function advance(ms: number) {
	await act(() => vi.advanceTimersByTimeAsync(ms));
}

function mountSession() {
	let refreshCalls = 0;
	let userCalls = 0;
	server.use(
		http.get("*/user", () => {
			userCalls++;
			return HttpResponse.json(userPayload(refreshCalls < 2 ? 61 : 3600));
		}),
		http.post("*/auth/refresh", () => {
			refreshCalls++;
			return new HttpResponse(null, { status: 204 });
		}),
	);
	const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	queryClient.setQueryData(getCurrentUserQueryKey(), userPayload(61));
	const { unmount } = renderHook(() => useSessionKeepAlive(), {
		wrapper: ({ children }: { children: ReactNode }) => (
			<StrictMode>
				<QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
			</StrictMode>
		),
	});
	return { refreshCalls: () => refreshCalls, userCalls: () => userCalls, unmount };
}

describe("useSessionKeepAlive", () => {
	it("renews once on mount even when Strict Mode replays the effect", async () => {
		const session = mountSession();
		await advance(1_000);
		await act(() => vi.waitFor(() => expect(session.refreshCalls()).toBe(1)));
		await act(() => vi.waitFor(() => expect(session.userCalls()).toBe(1)));
	});

	it("leaves a renewed session idle until activity resumes", async () => {
		const session = mountSession();
		await advance(1_000);
		await act(() => vi.waitFor(() => expect(session.userCalls()).toBe(1)));
		await advance(5_000);
		expect(session.refreshCalls()).toBe(1);
		act(() => {
			window.dispatchEvent(new Event("pointerdown"));
		});
		await act(() => vi.waitFor(() => expect(session.refreshCalls()).toBe(2)));
		await act(() => vi.waitFor(() => expect(session.userCalls()).toBe(2)));
		await advance(5_000);
		expect(session.refreshCalls()).toBe(2);
	});

	it("can renew after a transient failure without retrying an idle session", async () => {
		const session = mountSession();
		server.use(
			http.post("*/auth/refresh", () => new HttpResponse(null, { status: 503 }), { once: true }),
		);
		await advance(1_000);
		await act(() => vi.waitFor(() => expect(session.userCalls()).toBe(1)));
		await advance(5_000);
		expect(session.refreshCalls()).toBe(0);
		act(() => {
			window.dispatchEvent(new Event("keydown"));
		});
		await act(() => vi.waitFor(() => expect(session.userCalls()).toBe(2)));
		expect(session.refreshCalls()).toBe(1);
		await advance(5_000);
		expect(session.refreshCalls()).toBe(1);
	});

	it("removes the timer and activity listeners when unmounted", async () => {
		const session = mountSession();
		session.unmount();
		await advance(120_000);
		act(() => {
			window.dispatchEvent(new Event("pointerdown"));
		});
		expect(session.refreshCalls()).toBe(0);
	});
});
