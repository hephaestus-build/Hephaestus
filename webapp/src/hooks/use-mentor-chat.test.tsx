import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { type ReactNode, useState } from "react";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@ai-sdk/react", () => ({
	useChat: vi.fn(),
}));

vi.mock("@/hooks/use-active-workspace", () => ({
	useActiveWorkspaceSlug: vi.fn(),
}));

vi.mock("@/integrations/auth", () => ({
	csrfHeaders: vi.fn(() => ({ "X-XSRF-TOKEN": "mock-csrf" })),
}));

vi.mock("@/environment", () => ({
	default: {
		serverUrl: "http://localhost:8080",
	},
}));

vi.mock("uuid", () => ({
	v4: vi.fn(() => "mock-uuid-123"),
}));

import { type UseChatHelpers, useChat } from "@ai-sdk/react";
import type { ChatInit } from "ai";

import { getThreadQueryKey, listThreadsQueryKey } from "@/api/@tanstack/react-query.gen";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import type { ChatMessage } from "@/lib/types";

import { useMentorChat } from "./use-mentor-chat";

// The instantiation expression pins the message type the hook uses, so the fake below is
// checked against the real `useChat` contract rather than a loosened one.
const mockUseChat = vi.mocked(useChat<ChatMessage>);
const mockUseActiveWorkspaceSlug = vi.mocked(useActiveWorkspaceSlug);

/** Every field of the hook's workspace context, so a scenario only states what it varies. */
function activeWorkspace(
	overrides: Partial<ReturnType<typeof useActiveWorkspaceSlug>> = {},
): ReturnType<typeof useActiveWorkspaceSlug> {
	return {
		workspaceSlug: "test-workspace",
		chromeWorkspaceSlug: "test-workspace",
		chromeWorkspace: undefined,
		workspaces: [],
		providerType: "GITHUB",
		isLoading: false,
		error: null,
		...overrides,
	};
}

type ChatStatus = UseChatHelpers<ChatMessage>["status"];

const SELF_GENERATED_THREAD_ID = "mock-uuid-123";

function createMockMessage(role: "user" | "assistant", text: string, id?: string): ChatMessage {
	return {
		id: id ?? `msg-${Math.random().toString(36).slice(2)}`,
		role,
		parts: [{ type: "text", text, state: role === "assistant" ? "done" : undefined }],
	};
}

function textOf(message: ChatMessage): string {
	return message.parts
		.map((part) => (part.type === "text" ? part.text : ""))
		.join("")
		.trim();
}

interface FakeChat {
	/** The options `useChat` received on its most recent render. */
	readonly lastOptions: ChatInit<ChatMessage>;
	raiseError: (error: Error) => void;
	finishTurn: () => void;
}

/** Stateful, because the hook does not own its transcript: a frozen `messages: []` proves nothing. */
function installFakeChat(initialStatus: ChatStatus = "ready"): FakeChat {
	let lastOptions: ChatInit<ChatMessage> | undefined;
	const fake: FakeChat = {
		get lastOptions() {
			if (!lastOptions) throw new Error("useChat has not rendered yet");
			return lastOptions;
		},
		raiseError: () => {},
		finishTurn: () => {},
	};

	mockUseChat.mockImplementation((options) => {
		if (!options || "chat" in options) {
			throw new Error("useMentorChat is expected to configure its own chat, not adopt one");
		}
		lastOptions = options;
		const [messages, setMessages] = useState<ChatMessage[]>(options.messages ?? []);
		const [status, setStatus] = useState<ChatStatus>(initialStatus);
		const [error, setError] = useState<Error | undefined>(undefined);
		const addToolOutput = vi.fn();

		fake.raiseError = (raised: Error) => {
			setStatus("error");
			setError(raised);
			options.onError?.(raised);
		};
		fake.finishTurn = () => {
			options.onFinish?.({
				message: createMockMessage("assistant", "Done"),
				messages,
				isAbort: false,
				isDisconnect: false,
				isError: false,
			});
		};

		return {
			id: options.id ?? "",
			messages,
			status,
			error,
			sendMessage: async (message) => {
				const text = message && "text" in message ? message.text : undefined;
				if (typeof text !== "string") return;
				setMessages((current) => [
					...current,
					createMockMessage("user", text, options.generateId?.()),
				]);
				setStatus("submitted");
			},
			setMessages,
			stop: vi.fn(),
			regenerate: vi.fn(),
			clearError: vi.fn(),
			resumeStream: vi.fn(),
			addToolOutput,
			// Still a required member of `UseChatHelpers`, aliasing `addToolOutput`, so one spy backs both.
			// oxlint-disable-next-line typescript/no-deprecated -- a fake has to implement the interface it stands in for
			addToolResult: addToolOutput,
			addToolApprovalResponse: vi.fn(),
		};
	});

	return fake;
}

function createQueryClient() {
	return new QueryClient({
		defaultOptions: {
			queries: {
				retry: false,
				staleTime: Number.POSITIVE_INFINITY,
			},
			mutations: {
				retry: false,
			},
		},
	});
}

function createWrapper(queryClient: QueryClient) {
	return function Wrapper({ children }: { children: ReactNode }) {
		return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
	};
}

describe("useMentorChat", () => {
	let queryClient: QueryClient;
	let chat: FakeChat;

	beforeEach(() => {
		vi.clearAllMocks();

		queryClient = createQueryClient();

		mockUseActiveWorkspaceSlug.mockReturnValue(activeWorkspace());

		chat = installFakeChat();

		global.fetch = vi.fn();
	});

	afterEach(() => {
		vi.restoreAllMocks();
	});

	describe("initialization", () => {
		it("should initialize with default values when no threadId provided", () => {
			const { result } = renderHook(() => useMentorChat({}), {
				wrapper: createWrapper(queryClient),
			});

			expect(result.current.messages).toStrictEqual([]);
			expect(result.current.status).toBe("ready");
			expect(result.current.error).toBeUndefined();
			expect(result.current.currentThreadId).toBe(SELF_GENERATED_THREAD_ID);
		});

		it("should use provided threadId when available", () => {
			const { result } = renderHook(() => useMentorChat({ threadId: "existing-thread-123" }), {
				wrapper: createWrapper(queryClient),
			});

			expect(result.current.currentThreadId).toBe("existing-thread-123");
		});

		it("opens on the messages it was handed, so a resumed thread renders straight away", () => {
			const initialMessages = [
				createMockMessage("user", "Hello"),
				createMockMessage("assistant", "Hi there!"),
			];

			const { result } = renderHook(() => useMentorChat({ initialMessages }), {
				wrapper: createWrapper(queryClient),
			});

			expect(result.current.messages).toStrictEqual(initialMessages);
		});

		it("should not enable thread query when workspace is loading", () => {
			mockUseActiveWorkspaceSlug.mockReturnValue(
				activeWorkspace({ workspaceSlug: undefined, isLoading: true }),
			);

			const { result } = renderHook(() => useMentorChat({ threadId: "thread-123" }), {
				wrapper: createWrapper(queryClient),
			});

			expect(result.current.isLoading).toBe(true);
		});
	});

	describe("message sending", () => {
		it("puts what the user typed into the transcript", () => {
			const { result } = renderHook(() => useMentorChat({}), {
				wrapper: createWrapper(queryClient),
			});

			act(() => {
				result.current.sendMessage("Hello AI!");
			});

			expect(result.current.messages).toHaveLength(1);
			const [sent] = result.current.messages;
			assert(sent);
			expect(sent.role).toBe("user");
			expect(textOf(sent)).toBe("Hello AI!");
			expect(result.current.status).toBe("submitted");
		});

		it.each<[string, string]>([
			["an empty message", ""],
			["a message that is only whitespace", "   "],
		])("leaves the transcript untouched for %s", (_name, text) => {
			const { result } = renderHook(() => useMentorChat({}), {
				wrapper: createWrapper(queryClient),
			});

			act(() => {
				result.current.sendMessage(text);
			});

			expect(result.current.messages).toStrictEqual([]);
			expect(result.current.status).toBe("ready");
		});

		it("sends nothing while no workspace is active, because there is no endpoint to send to", () => {
			mockUseActiveWorkspaceSlug.mockReturnValue(activeWorkspace({ workspaceSlug: undefined }));

			const { result } = renderHook(() => useMentorChat({}), {
				wrapper: createWrapper(queryClient),
			});

			act(() => {
				result.current.sendMessage("Hello");
			});

			expect(result.current.messages).toStrictEqual([]);
			expect(result.current.status).toBe("ready");
		});
	});

	describe("error handling", () => {
		it("surfaces a failed stream as state and tells the caller about it once", async () => {
			const onError = vi.fn();
			const streamingError = new Error("Streaming error");
			const { result } = renderHook(() => useMentorChat({ onError }), {
				wrapper: createWrapper(queryClient),
			});

			act(() => {
				chat.raiseError(streamingError);
			});

			expect(result.current.error).toBe(streamingError);
			expect(result.current.status).toBe("error");
			await waitFor(() => expect(onError).toHaveBeenCalledExactlyOnceWith(streamingError));
		});
	});

	describe("vote functionality", () => {
		it("records an upvote optimistically, before the server has answered", async () => {
			const { result } = renderHook(() => useMentorChat({}), {
				wrapper: createWrapper(queryClient),
			});

			act(() => {
				result.current.voteMessage("msg-123", true);
			});

			expect(result.current.votes).toContainEqual(
				expect.objectContaining({
					messageId: "msg-123",
					isUpvoted: true,
				}),
			);
		});

		it("records a downvote the same way", () => {
			const { result } = renderHook(() => useMentorChat({}), {
				wrapper: createWrapper(queryClient),
			});

			act(() => {
				result.current.voteMessage("msg-456", false);
			});

			expect(result.current.votes).toContainEqual(
				expect.objectContaining({
					messageId: "msg-456",
					isUpvoted: false,
				}),
			);
		});

		it("keeps one entry per voted message", () => {
			const { result } = renderHook(() => useMentorChat({}), {
				wrapper: createWrapper(queryClient),
			});

			act(() => {
				result.current.voteMessage("msg-1", true);
				result.current.voteMessage("msg-2", false);
				result.current.voteMessage("msg-3", true);
			});

			expect(result.current.votes).toHaveLength(3);
		});

		it("should not vote when workspace is not available", () => {
			mockUseActiveWorkspaceSlug.mockReturnValue(activeWorkspace({ workspaceSlug: undefined }));

			const { result } = renderHook(() => useMentorChat({}), {
				wrapper: createWrapper(queryClient),
			});

			act(() => {
				result.current.voteMessage("msg-123", true);
			});

			expect(result.current.votes).toHaveLength(0);
		});
	});

	describe("thread hydration", () => {
		// UUIDs: `parseThreadMessages` rejects the whole thread if any id is another shape.
		const threadMessages = [
			createMockMessage("user", "Previous message", "f47ac10b-58cc-4372-a567-0e02b2c3d479"),
			createMockMessage("assistant", "Previous response", "c9bf9e57-1685-4c89-bafb-ff5af830be8a"),
		];

		function seedThread() {
			queryClient.setQueryData(
				getThreadQueryKey({ path: { workspaceSlug: "test-workspace", threadId: "thread-123" } }),
				{
					id: "thread-123",
					title: "Test Thread",
					messages: threadMessages,
				},
			);
		}

		it("renders the stored thread once its detail resolves", async () => {
			seedThread();

			const { result } = renderHook(() => useMentorChat({ threadId: "thread-123" }), {
				wrapper: createWrapper(queryClient),
			});

			expect(result.current.currentThreadId).toBe("thread-123");
			await waitFor(() =>
				expect(result.current.messages.map((message) => message.id)).toStrictEqual(
					threadMessages.map((message) => message.id),
				),
			);
			expect(result.current.messages.map(textOf)).toStrictEqual([
				"Previous message",
				"Previous response",
			]);
		});

		it("does not overwrite an answer that is still streaming", async () => {
			chat = installFakeChat("streaming");
			seedThread();

			const { result } = renderHook(() => useMentorChat({ threadId: "thread-123" }), {
				wrapper: createWrapper(queryClient),
			});

			// The hydration effect keys on the thread detail, so once that is readable it has had its run.
			await waitFor(() => expect(result.current.threadDetail?.messages).toBeDefined());
			expect(result.current.messages).toStrictEqual([]);
			expect(result.current.status).toBe("streaming");
		});
	});

	describe("callback invocation", () => {
		it("marks the thread and the thread list stale when a turn finishes", async () => {
			const threadsKey = listThreadsQueryKey({ path: { workspaceSlug: "test-workspace" } });
			const threadKey = getThreadQueryKey({
				path: { workspaceSlug: "test-workspace", threadId: SELF_GENERATED_THREAD_ID },
			});
			queryClient.setQueryData(threadsKey, []);
			queryClient.setQueryData(threadKey, { id: SELF_GENERATED_THREAD_ID, messages: [] });

			renderHook(() => useMentorChat({}), { wrapper: createWrapper(queryClient) });
			expect(queryClient.getQueryState(threadsKey)?.isInvalidated).toBe(false);

			act(() => {
				chat.finishTurn();
			});

			expect(queryClient.getQueryState(threadsKey)?.isInvalidated).toBe(true);
			expect(queryClient.getQueryState(threadKey)?.isInvalidated).toBe(true);
		});
	});

	describe("transport configuration", () => {
		it("posts the latest message to the active workspace's mentor endpoint, cookie + CSRF", async () => {
			renderHook(() => useMentorChat({}), {
				wrapper: createWrapper(queryClient),
			});

			const { transport } = chat.lastOptions;
			assert(transport, "The hook must configure a transport");

			// A fresh Response per call: the transport consumes the body stream, so a shared one
			// would already be locked by the queries the render kicked off.
			const fetchMock = vi.mocked(globalThis.fetch);
			fetchMock.mockImplementation(async () => new Response("data: [DONE]\n\n", { status: 200 }));
			fetchMock.mockClear();

			const latest = createMockMessage("user", "second", "m2");
			await transport.sendMessages({
				trigger: "submit-message",
				chatId: "thread-1",
				messageId: undefined,
				messages: [createMockMessage("user", "first", "m1"), latest],
				abortSignal: undefined,
			});

			// The render also issues the thread GETs, so pick the one write out of the traffic.
			const posted = fetchMock.mock.calls.find(([, init]) => init?.method === "POST");
			assert(posted, "The hook sent no message");
			const [url, init] = posted;
			expect(url).toBe("http://localhost:8080/workspaces/test-workspace/mentor/chat");
			// Only the newest message travels; the server rebuilds context from the thread id.
			expect(init?.body).toBe(JSON.stringify({ id: "thread-1", message: latest }));
			expect(init?.credentials).toBe("include");
			expect(new Headers(init?.headers).get("X-XSRF-TOKEN")).toBe("mock-csrf");
		});
	});
});
