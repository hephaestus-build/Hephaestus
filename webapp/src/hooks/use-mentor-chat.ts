import { type UseChatHelpers, useChat } from "@ai-sdk/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { DefaultChatTransport } from "ai";
import { useRef, useState } from "react";
import { v4 as uuidv4 } from "uuid";

import {
	getThreadOptions,
	getThreadQueryKey,
	listThreadsQueryKey,
	voteMutation,
} from "@/api/@tanstack/react-query.gen";
import type { ChatMessageVote } from "@/api/types.gen";
import environment from "@/environment";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { isWarmingUp } from "@/lib/chat-validation";
import { hasText } from "@/lib/text";
import type { ChatMessage } from "@/lib/types";
import { csrfHeaders } from "@/runtime/auth/auth-client";
import { userViewHeaders } from "@/runtime/user-view/session";

interface UseMentorChatOptions {
	threadId?: string;
	initialMessages?: ChatMessage[];
	onFinish?: () => void;
	onError?: (error: Error) => void;
}

/** `addToolResult` is dropped because it is the SDK's deprecated alias for the forwarded `addToolOutput`. */
interface UseMentorChatReturn extends Omit<
	UseChatHelpers<ChatMessage>,
	"sendMessage" | "addToolResult"
> {
	sendMessage: (text: string) => void;
	/** Starts a separate floating conversation, leaving the previous stored thread available. */
	startNewChat: () => Promise<void>;
	/** Answers the latest prompt again, replacing the reply that failed. */
	retry: () => void;
	isLoading: boolean;
	currentThreadId: string | undefined;
	voteMessage: (messageId: string, isUpvoted: boolean) => void;
	votes: ChatMessageVote[];
	/** The turn in flight is waiting for Heph's sandbox to start, so its first words come late. */
	warmingUp: boolean;
}

/**
 * One stored conversation. A conversation is read once and then lives in `useChat`, so neither a
 * remount nor a focus reads it again.
 */
export function mentorThreadOptions(workspaceSlug: string, threadId: string) {
	return {
		...getThreadOptions({ path: { workspaceSlug, threadId } }),
		staleTime: 60_000,
		refetchOnMount: false,
		refetchOnWindowFocus: false,
		refetchOnReconnect: false,
	};
}

export function useMentorChat({
	threadId,
	initialMessages = [],
	onFinish,
	onError,
}: UseMentorChatOptions): UseMentorChatReturn {
	const queryClient = useQueryClient();
	const { workspaceSlug, isLoading: isWorkspaceLoading } = useActiveWorkspaceSlug();
	const slug = workspaceSlug ?? "";
	const hasWorkspace = Boolean(workspaceSlug);

	const [stableThreadId, setStableThreadId] = useState(() => threadId ?? uuidv4());

	const { data: threadDetail, isLoading: isThreadLoading } = useQuery({
		...mentorThreadOptions(slug, threadId ?? ""),
		enabled: Boolean(threadId) && hasWorkspace,
	});

	const voteMessageMut = useMutation(voteMutation());

	// Overlaid on the server's record: an entry wins while its mutation is in flight, so dropping it
	// on failure falls straight back to the server without a second request.
	const [castVotes, setCastVotes] = useState(() => new Map<string, boolean>());

	// Keyed by the id the votes were cast against rather than by `threadId`, so a brand-new thread
	// learning its id does not read as a thread switch and discard them.
	const voteThreadId = threadId ?? stableThreadId;
	const [votedThreadId, setVotedThreadId] = useState(voteThreadId);
	if (votedThreadId !== voteThreadId) {
		setVotedThreadId(voteThreadId);
		setCastVotes(new Map());
	}

	const voteState: Record<string, boolean> = { ...threadDetail?.votes };
	for (const [messageId, isUpvoted] of castVotes) {
		voteState[messageId] = isUpvoted;
	}

	// `updatedAt` stays unset: it is the server's stamp on a stored vote, and no surface renders it.
	const votes: ChatMessageVote[] = Object.entries(voteState).map(([messageId, isUpvoted]) => ({
		messageId,
		isUpvoted,
	}));

	// `useChat` reads the current transport through its refreshed ref and recreates the conversation
	// when its id changes.
	const transport = new DefaultChatTransport<ChatMessage>({
		api: `${environment.serverUrl}/workspaces/${slug}/mentor/chat`,
		prepareSendMessagesRequest: ({ id, messages, trigger, messageId, requestMetadata }) => {
			const effectiveId = id || stableThreadId;
			// Only the latest message travels: the server rebuilds context and parent linkage from the
			// thread id, so anything else in `messages` is bytes it ignores. A custom body replaces the
			// SDK's default one, so the trigger and the replaced reply have to be carried over by hand.
			const lastMessage = messages.at(-1);
			return {
				body: {
					id: effectiveId,
					message: lastMessage,
					trigger,
					messageId: messageId ?? retriedReplyOf(requestMetadata),
				},
				// Cookie-session auth (ADR 0017): session cookie rides credentials:include;
				// CSRF double-submit header for this state-changing POST.
				credentials: "include",
				headers: { ...csrfHeaders(), ...userViewHeaders() },
			};
		},
	});

	// The server says so on a transient `data-mentor-status` part, which `useChat` hands to `onData` and
	// never stores, so it is held here until the turn ends or the next one starts.
	const [warmingUp, setWarmingUp] = useState(false);

	// Unmemoised on purpose: `useChat` copies both handlers into a ref every render and calls through
	// it, so each only has to be the current closure — a stable reference would go stale.
	const handleFinish = () => {
		setWarmingUp(false);
		if (hasWorkspace) {
			void queryClient.invalidateQueries({
				queryKey: listThreadsQueryKey({ path: { workspaceSlug: slug } }),
			});
		}
		if (hasText(threadId) || stableThreadId) {
			void queryClient.invalidateQueries({
				queryKey: getThreadQueryKey({
					path: { workspaceSlug: slug, threadId: threadId ?? stableThreadId },
				}),
			});
		}
		onFinish?.();
	};

	const handleError = (error: Error) => {
		setWarmingUp(false);
		onError?.(error);
	};

	const {
		messages,
		sendMessage: originalSendMessage,
		status,
		stop,
		regenerate,
		error,
		clearError,
		setMessages,
		resumeStream,
		addToolOutput,
		addToolApprovalResponse,
		id,
	} = useChat<ChatMessage>({
		id: stableThreadId,
		// The stored conversation, read before the chat mounts: `useChat` takes it once, on creation.
		messages: initialMessages,
		generateId: () => uuidv4(),
		// No `experimental_throttle`: the mentor's delta cadence is already LLM-bound, so batching
		// re-renders on top of it makes tokens arrive in visible clumps instead of typing out. The
		// markdown renderer is cheap enough to take every delta.
		transport,
		onFinish: handleFinish,
		onError: handleError,
		onData: (part) => {
			if (isWarmingUp(part)) {
				setWarmingUp(true);
			}
		},
	});

	// `regenerate` drops the failed reply before it posts, and only accepts a reply still in the list, so
	// after a retry refused before a new reply started, the target travels as request metadata.
	const retryTarget = useRef<string | undefined>(undefined);

	const startNewChat = async () => {
		await stop();
		retryTarget.current = undefined;
		setWarmingUp(false);
		setStableThreadId(uuidv4());
	};

	const sendMessage = (text: string) => {
		if (!text.trim() || !hasWorkspace) {
			return;
		}

		retryTarget.current = undefined;
		setWarmingUp(false);
		void originalSendMessage({ text });
	};

	const retry = () => {
		setWarmingUp(false);
		const last = messages.at(-1);
		if (last?.role === "assistant") {
			retryTarget.current = last.id;
			void regenerate({ messageId: last.id });
			return;
		}
		const dropped = retryTarget.current;
		void regenerate(dropped === undefined ? undefined : { metadata: { retryOf: dropped } });
	};

	// No greeting request: the server has no greeting flag, so a POST asking for one comes back
	// "User message text is empty." `Chat` renders a static greeting instead.

	const voteMessage = (messageId: string, isUpvoted: boolean) => {
		if (!hasWorkspace) {
			return;
		}
		if (!voteThreadId) {
			return;
		}
		setCastVotes((prev) => new Map(prev).set(messageId, isUpvoted));
		voteMessageMut.mutate(
			{
				path: { workspaceSlug: slug, threadId: voteThreadId, messageId },
				body: { isUpvoted },
			},
			{
				onError: () => {
					setCastVotes((prev) => {
						const next = new Map(prev);
						next.delete(messageId);
						return next;
					});
				},
				onSettled: () => {
					void queryClient.invalidateQueries({
						queryKey: getThreadQueryKey({
							path: {
								workspaceSlug: slug,
								threadId: voteThreadId,
							},
						}),
					});
				},
			},
		);
	};

	const isLoading =
		isWorkspaceLoading ||
		status === "submitted" ||
		(status === "streaming" && messages.length === 0) ||
		(hasText(threadId) && isThreadLoading);

	const result: UseMentorChatReturn = {
		messages,
		status,
		error,
		stop,
		regenerate,
		setMessages,
		resumeStream,
		addToolOutput,
		addToolApprovalResponse,
		id,
		clearError,
		sendMessage,
		startNewChat,
		retry,
		currentThreadId: threadId ?? id,
		voteMessage,
		votes,
		isLoading,
		warmingUp,
	};

	return result;
}

function retriedReplyOf(requestMetadata: unknown): string | undefined {
	return typeof requestMetadata === "object" &&
		requestMetadata !== null &&
		"retryOf" in requestMetadata &&
		typeof requestMetadata.retryOf === "string"
		? requestMetadata.retryOf
		: undefined;
}
