import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { http, HttpResponse, type PathParams } from "msw";
import type { ReactNode } from "react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { getMentorConversationQueryKey } from "@/api/@tanstack/react-query.gen";
import type { MentorConversation } from "@/api/types.gen";
import { server } from "@/mocks/server";

import { applyRead } from "./heph-cache";
import { useHephUnread, useMarkHephRead } from "./use-heph-conversation";

const SLUG = "acme";
const READ_BEFORE = "2026-10-13T09:00:00.000Z";
const ANSWERED = "2026-10-13T10:00:00.000Z";
const LATER = "2026-10-13T10:30:00.000Z";

let queryClient: QueryClient;
let puts: string[];

function wrapper({ children }: { children: ReactNode }) {
	return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

/** The conversation as this device holds it: one answer, read before it landed. */
function seed() {
	queryClient.setQueryData<MentorConversation>(
		getMentorConversationQueryKey({ path: { workspaceSlug: SLUG } }),
		{
			id: "c0000000-0000-4000-8000-000000000000",
			latestSeq: 2,
			activeTurns: [],
			threads: [],
			lastAnswerAt: new Date(ANSWERED),
			lastReadAt: new Date(READ_BEFORE),
		},
	);
}

/** The server answers the read with these statuses in turn, then with the marker it keeps. */
function serveRead(failures: number[], keeps?: string) {
	server.use(
		http.get(
			`*/workspaces/${SLUG}/mentor/conversation`,
			() => new HttpResponse(null, { status: 500 }),
		),
		http.put<PathParams, { at: string }>(
			`*/workspaces/${SLUG}/mentor/conversation/read`,
			async ({ request }) => {
				const body = await request.json();
				puts.push(body.at);
				const status = failures.shift();
				return status === undefined
					? HttpResponse.json({ lastReadAt: keeps ?? body.at })
					: new HttpResponse(null, { status });
			},
		),
	);
}

/** The server's marker as this device holds it. */
const confirmed = () =>
	queryClient
		.getQueryData<MentorConversation>(
			getMentorConversationQueryKey({ path: { workspaceSlug: SLUG } }),
		)
		?.lastReadAt?.toISOString();

/** Marks the conversation read, and says whether this device shows it unread. */
function renderRead() {
	return renderHook(
		() => {
			useMarkHephRead(SLUG, true);
			return useHephUnread(SLUG);
		},
		{ wrapper },
	);
}

const settle = async (ms: number) => {
	await act(async () => {
		await vi.advanceTimersByTimeAsync(ms);
	});
};

beforeEach(() => {
	queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false, staleTime: Infinity } },
	});
	puts = [];
	vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
});

afterEach(() => {
	vi.useRealTimers();
});

it("shows the answer read while the request runs, and keeps the marker the server answers with", async () => {
	seed();
	// Another device had read further, so the server answers with its later marker.
	serveRead([503], LATER);
	const { result } = renderRead();
	await settle(0);
	expect(result.current).toBe(false);
	expect(confirmed()).toBe(READ_BEFORE);

	await settle(10_000);
	expect(puts).toStrictEqual([ANSWERED, ANSWERED]);
	expect(confirmed()).toBe(LATER);
	expect(result.current).toBe(false);
});

it("shows the answer unread again when every attempt failed, and does not send the same read again", async () => {
	seed();
	serveRead([503, 503, 503, 503]);
	const { result } = renderRead();
	await settle(60_000);
	expect(puts).toHaveLength(4);
	expect(confirmed()).toBe(READ_BEFORE);
	expect(result.current).toBe(true);

	await settle(60_000);
	expect(puts).toHaveLength(4);
});

it("stays read when another device confirmed the read while this one's attempts failed", async () => {
	seed();
	serveRead([503, 503, 503, 503]);
	const { result } = renderRead();
	await settle(1000);
	// The other device's read reaches this one as the server's `read` event.
	act(() => {
		applyRead(queryClient, SLUG, new Date(ANSWERED));
	});
	await settle(60_000);
	expect(puts).toHaveLength(4);
	expect(confirmed()).toBe(ANSWERED);
	expect(result.current).toBe(false);
});
