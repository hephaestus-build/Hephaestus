import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";

import { useMentorChat } from "./use-mentor-chat";

vi.mock("@/hooks/use-active-workspace", () => ({
	useActiveWorkspaceSlug: () => ({ workspaceSlug: "test-workspace", isLoading: false }),
}));

vi.mock("@/runtime/auth/auth-client", () => ({ csrfHeaders: () => ({}) }));

vi.mock("@/environment", () => ({ default: { serverUrl: "http://localhost:8080" } }));

const FIRST_REPLY = "5b0f4f64-0c6f-4d2f-9f1a-3a0a0d4b9a11";
const SECOND_REPLY = "0d6c2b1e-7f3a-4f7e-9a53-8e2b4c1f6d20";
const TIMED_OUT = {
	type: "error",
	errorText: "Heph took too long to reply and stopped. Try again.",
};

interface PostedTurn {
	message: { id: string };
	trigger: string;
	messageId?: string;
}

function stream(...chunks: object[]): HttpResponse<string> {
	const body = [...chunks.map((chunk) => `data: ${JSON.stringify(chunk)}\n\n`), "data: [DONE]\n\n"];
	return new HttpResponse(body.join(""), {
		headers: { "Content-Type": "text/event-stream", "x-vercel-ai-ui-message-stream": "v1" },
	});
}

function wrapper({ children }: { children: ReactNode }) {
	const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

// The real `useChat` and transport: the regeneration intent is what the server needs, and a mocked SDK
// cannot show whether it survives the custom request body.
describe("useMentorChat retry", () => {
	it("keeps naming the failed reply until a new one starts, then names that one", async () => {
		const responses = [
			stream({ type: "start", messageId: FIRST_REPLY }, TIMED_OUT),
			// Refused before a new reply started, e.g. the model was unavailable.
			stream({ type: "error", errorText: "The mentor is unavailable right now." }),
			stream({ type: "start", messageId: SECOND_REPLY }, TIMED_OUT),
			stream({ type: "start", messageId: "a-new-attempt" }, { type: "finish" }),
		];
		const posted: PostedTurn[] = [];
		server.use(
			http.get("*/workspaces/:workspaceSlug/mentor/threads", () => HttpResponse.json([])),
			http.post<{ workspaceSlug: string }, PostedTurn>(
				"*/workspaces/:workspaceSlug/mentor/chat",
				async ({ request }) => {
					posted.push(await request.json());
					return responses[posted.length - 1];
				},
			),
		);
		const { result } = renderHook(() => useMentorChat({}), { wrapper });
		const retryAfterFailure = async (posts: number) => {
			await waitFor(() => expect(result.current.turn.kind).toBe("error"));
			act(() => result.current.retry());
			await waitFor(() => expect(posted).toHaveLength(posts));
		};

		act(() => result.current.sendMessage("Plan issue 12"));
		await retryAfterFailure(2);
		await retryAfterFailure(3);
		await retryAfterFailure(4);
		await waitFor(() => expect(result.current.turn.kind).toBe("ready"));

		const prompt = posted[0]?.message.id;
		expect(
			posted.map(({ trigger, messageId, message }) => ({ trigger, messageId, prompt: message.id })),
		).toStrictEqual([
			{ trigger: "submit-message", messageId: undefined, prompt },
			{ trigger: "regenerate-message", messageId: FIRST_REPLY, prompt },
			{ trigger: "regenerate-message", messageId: FIRST_REPLY, prompt },
			{ trigger: "regenerate-message", messageId: SECOND_REPLY, prompt },
		]);
	});
});
