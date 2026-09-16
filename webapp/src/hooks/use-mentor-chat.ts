import { type UseChatHelpers, useChat } from "@ai-sdk/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { DefaultChatTransport } from "ai";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { v4 as uuidv4 } from "uuid";

import {
	getThreadOptions,
	getThreadQueryKey,
	listThreadsOptions,
	listThreadsQueryKey,
	voteMutation,
} from "@/api/@tanstack/react-query.gen";
import type { ChatMessageVote, ChatThreadDetail, ChatThreadSummary } from "@/api/types.gen";
import environment from "@/environment";
import { useActiveWorkspaceSlug } from "@/hooks/use-active-workspace";
import { csrfHeaders } from "@/integrations/auth";
import { extractVotesFromThreadDetail, parseThreadMessages } from "@/lib/chat-validation";
import type { ChatMessage } from "@/lib/types";

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
	threadDetail: ChatThreadDetail | undefined;
	isThreadLoading: boolean;
	threadError: Error | null;
	threads: ChatThreadSummary[] | undefined;
	isThreadsLoading: boolean;
	isLoading: boolean;
	currentThreadId: string | undefined;
	voteMessage: (messageId: string, isUpvoted: boolean) => void;
	votes: ChatMessageVote[];
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

	const [stableThreadId] = useState(() => threadId ?? uuidv4());

	const threadQueryKey = getThreadQueryKey({
		path: { workspaceSlug: slug, threadId: threadId ?? "" },
	});
	const threadQuery = useQuery({
		...getThreadOptions({
			path: { workspaceSlug: slug, threadId: threadId ?? "" },
		}),
		enabled: Boolean(threadId) && hasWorkspace,
		initialData: () =>
			hasWorkspace ? queryClient.getQueryData<ChatThreadDetail>(threadQueryKey) : undefined,
		staleTime: 60_000,
		refetchOnMount: false,
		refetchOnWindowFocus: false,
		refetchOnReconnect: false,
	});

	const { data: threadDetail, isLoading: isThreadLoading, error: threadError } = threadQuery;

	const threadsKey = listThreadsQueryKey({ path: { workspaceSlug: slug } });
	const { data: threads, isLoading: isThreadsLoading } = useQuery({
		...listThreadsOptions({ path: { workspaceSlug: slug } }),
		enabled: hasWorkspace,
		initialData: () =>
			hasWorkspace ? queryClient.getQueryData<ChatThreadSummary[]>(threadsKey) : undefined,
		staleTime: 60_000,
		refetchOnMount: false,
		refetchOnWindowFocus: false,
		refetchOnReconnect: false,
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

	const voteState: Record<string, boolean | undefined> = {};
	for (const vote of extractVotesFromThreadDetail(threadDetail)) {
		if (vote.messageId) {
			voteState[vote.messageId] = vote.isUpvoted;
		}
	}
	for (const [messageId, isUpvoted] of castVotes) {
		voteState[messageId] = isUpvoted;
	}

	// `updatedAt` stays unset: it is the server's stamp on a stored vote, and no surface renders it.
	const votes: ChatMessageVote[] = Object.entries(voteState)
		.filter((entry): entry is [string, boolean] => entry[1] !== undefined)
		.map(([messageId, isUpvoted]) => ({ messageId, isUpvoted }));

	// Unmemoised on purpose: `useChat` builds its `Chat` from these options into a ref and rebuilds it
	// only when `id` changes, so the transport is read once and a later instance is never looked at.
	const transport = new DefaultChatTransport<ChatMessage>({
		api: `${environment.serverUrl}/workspaces/${slug}/mentor/chat`,
		prepareSendMessagesRequest: ({ id, messages }) => {
			const effectiveId = id || stableThreadId;
			// Only the latest message travels: the server rebuilds context and parent linkage from the
			// thread id, so anything else in `messages` is bytes it ignores.
			const lastMessage = messages.at(-1);
			return {
				body: { id: effectiveId, message: lastMessage },
				// Cookie-session auth (ADR 0017): session cookie rides credentials:include;
				// CSRF double-submit header for this state-changing POST.
				credentials: "include",
				headers: { ...csrfHeaders() },
			};
		},
	});

	// Unmemoised on purpose: `useChat` copies both handlers into a ref every render and calls through
	// it, so each only has to be the current closure — a stable reference would go stale.
	const handleFinish = () => {
		if (hasWorkspace) {
			void queryClient.invalidateQueries({
				queryKey: listThreadsQueryKey({ path: { workspaceSlug: slug } }),
			});
		}
		if (threadId || stableThreadId) {
			void queryClient.invalidateQueries({
				queryKey: getThreadQueryKey({
					path: { workspaceSlug: slug, threadId: threadId ?? stableThreadId },
				}),
			});
		}
		onFinish?.();
	};

	const handleError = (error: Error) => {
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
		// Only a seed: the server's stored transcript replaces this once the thread query lands.
		messages: initialMessages,
		generateId: () => uuidv4(),
		// No `experimental_throttle`: the mentor's delta cadence is already LLM-bound, so batching
		// re-renders on top of it makes tokens arrive in visible clumps instead of typing out. The
		// markdown renderer is cheap enough to take every delta.
		transport,
		onFinish: handleFinish,
		onError: handleError,
	});

	const hydratedRef = useRef<string | null>(null);
	useEffect(() => {
		if (!threadId) {
			return;
		}
		if (hydratedRef.current === threadId) {
			return;
		}
		if (status === "streaming" || status === "submitted") {
			return;
		}
		if (!threadDetail?.messages) {
			return;
		}

		// A transcript that will not parse would otherwise render as an empty conversation with nothing
		// to explain it. Keyed on the thread, so a re-run of this effect updates one toast, not stacks.
		const validatedMessages = parseThreadMessages(threadDetail.messages);
		if (!validatedMessages) {
			toast.error("Couldn't load this conversation's earlier messages.", {
				id: `mentor-thread-${threadId}`,
			});
			return;
		}

		setMessages(validatedMessages);
		hydratedRef.current = threadId;
	}, [threadId, threadDetail?.messages, status, setMessages]);

	const sendMessage = (text: string) => {
		if (!text.trim() || !hasWorkspace) {
			return;
		}

		void originalSendMessage({ text });
	};

	// No greeting request: the server has no greeting flag, so a POST asking for one comes back
	// "User message text is empty." The new-thread greeting is static, rendered by the route when
	// `messages.length === 0`.

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
		(!!threadId && isThreadLoading);

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
		threadDetail,
		isThreadLoading,
		threadError,
		threads,
		isThreadsLoading,
		currentThreadId: threadId ?? id,
		voteMessage,
		votes,
		isLoading,
	};

	return result;
}
