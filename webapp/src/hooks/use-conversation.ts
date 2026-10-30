import {
	useInfiniteQuery,
	useMutation,
	useMutationState,
	useQuery,
	useQueryClient,
	type UseInfiniteQueryResult,
} from "@tanstack/react-query";
import { toast } from "sonner";

import { useEffect } from "react";
import {
	clearMentorVoteMutation,
	deleteMentorEntryMutation,
	markMentorConversationReadMutation,
	setMentorVoteMutation,
	submitMentorTurnMutation,
	updateMentorTurnStatusMutation,
} from "@/api/@tanstack/react-query.gen";
import type { MentorEntry, MentorTurnRequest } from "@/api/types.gen";
import type {
	EarlierMessages,
	ConversationThreadState,
	ConversationTimelineState,
	MessageVote,
	TimelineActions,
} from "@/components/conversation/ConversationTimeline";
import {
	applyRead,
	applySubmission,
	applyTurn,
	applyVote,
	cancelScope,
	entriesOf,
	hasUnreadAnswer,
	inOrder,
	conversationOptions,
	conversationEntriesOptions,
	conversationThreadOptions,
	patchShownEntry,
} from "@/hooks/conversation-cache";
import { usePageVisible } from "@/hooks/use-page-visible";

import { type PendingMessage, readAtOf, turnRequestOf } from "@/lib/conversation-entry";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

/** Attempts after the first for a read marker the server did not take. */
const READ_RETRIES = 3;

/** A user view has no live stream, so it reads the viewed conversation again at this pace. */
const VIEW_POLL_MS = 15_000;

const NOT_SENT = "Hephaestus did not get this message.";

const submitKey = (workspaceSlug: string) => ["submitMentorTurn", workspaceSlug] as const;

function earlierOf<TData, TError>(
	query: Pick<
		UseInfiniteQueryResult<TData, TError>,
		"fetchNextPage" | "hasNextPage" | "isFetchingNextPage" | "isFetchNextPageError"
	>,
): EarlierMessages {
	const load = () => {
		void query.fetchNextPage();
	};
	// Loading first: a query that holds pages stays in error while Retry fetches the older page
	// again, so the error flag alone would still offer Retry while the page loads.
	if (query.isFetchingNextPage) {
		return { status: "loading" };
	}
	if (query.isFetchNextPageError) {
		return { status: "error", onRetry: load };
	}
	return query.hasNextPage ? { status: "available", onLoad: load } : { status: "none" };
}

/**
 * The messages the developer sent in this scope that the server has not stored yet, read from the
 * mutation cache, so the Hephaestus panel and the conversation page show the same ones. A send the server stored
 * leaves this list as its stored message arrives in the pages, not when its request ends: the stored
 * copy is written in order and can wait behind a read, and the question must not vanish meanwhile.
 */
function usePendingMessages(
	workspaceSlug: string,
	parentEntryId: string | undefined,
	stored: MentorEntry[],
): PendingMessage[] {
	const sends = useMutationState({
		filters: { mutationKey: submitKey(workspaceSlug) },
		select: ({ state }) => ({
			request: turnRequestOf(state.variables),
			status: state.status,
			error: state.error,
			submittedAt: state.submittedAt,
		}),
	});
	const storedRequests = new Set(stored.map(({ requestId }) => requestId));
	const latest = new Map<string, PendingMessage>();
	for (const { request, status, error, submittedAt } of sends) {
		if (request === undefined || request.parentEntryId !== parentEntryId) {
			continue;
		}
		const { requestId, text } = request;
		if (status === "idle" || storedRequests.has(requestId)) {
			latest.delete(requestId);
			continue;
		}
		const base = { requestId, text, parentEntryId, createdAt: new Date(submittedAt) };
		latest.set(
			requestId,
			status === "error"
				? { ...base, status: "failed", reason: problemDetailOf(error, NOT_SENT) }
				: { ...base, status: "sending" },
		);
	}
	return [...latest.values()];
}

function useConversationQuery(workspaceSlug: string, readOnly: boolean) {
	return useQuery({
		...conversationOptions(workspaceSlug),
		refetchInterval: readOnly ? VIEW_POLL_MS : false,
	});
}

/** The developer's top-level messages, newest page first read, older pages above it on demand. */
export function useConversationTimeline(
	workspaceSlug: string,
	readOnly: boolean,
): ConversationTimelineState {
	const conversation = useConversationQuery(workspaceSlug, readOnly);
	const pages = useInfiniteQuery({
		...conversationEntriesOptions(workspaceSlug),
		refetchInterval: readOnly ? VIEW_POLL_MS : false,
	});
	const entries = entriesOf(pages.data?.pages ?? []);
	const turns = (conversation.data?.activeTurns ?? []).filter(
		(turn) => turn.parentEntryId === undefined,
	);
	const pending = usePendingMessages(workspaceSlug, undefined, entries);

	// Only a first read that failed replaces the timeline: a failed older page or a failed refetch
	// keeps the messages already shown.
	if (
		(pages.isError && pages.data === undefined) ||
		(conversation.isError && conversation.data === undefined)
	) {
		return {
			status: "error",
			onRetry: () => {
				void pages.refetch();
				void conversation.refetch();
			},
		};
	}
	if (pages.data === undefined || conversation.data === undefined) {
		return { status: "loading" };
	}
	return { status: "ready", entries, pending, turns, earlier: earlierOf(pages) };
}

/** One thread: the message it replies to and its replies. */
export function useConversationThread(
	workspaceSlug: string,
	parentEntryId: string,
	readOnly: boolean,
): ConversationThreadState {
	const conversation = useConversationQuery(workspaceSlug, readOnly);
	const pages = useInfiniteQuery({
		...conversationThreadOptions(workspaceSlug, parentEntryId),
		refetchInterval: readOnly ? VIEW_POLL_MS : false,
	});
	const entries = entriesOf(pages.data?.pages ?? []);
	const turns = (conversation.data?.activeTurns ?? []).filter(
		(turn) => turn.parentEntryId === parentEntryId,
	);
	const pending = usePendingMessages(workspaceSlug, parentEntryId, entries);

	const parent = pages.data?.pages[0]?.parent;
	// Retries only the reads that failed: the thread, the conversation's state, or both.
	const retry = () => {
		if (pages.isError) {
			void pages.refetch();
		}
		if (conversation.isError) {
			void conversation.refetch();
		}
	};
	if (pages.isError && parent === undefined) {
		return problemStatusOf(pages.error) === 404
			? { status: "missing" }
			: { status: "error", onRetry: retry };
	}
	if (conversation.isError && conversation.data === undefined) {
		return { status: "error", onRetry: retry };
	}
	// A deleted parent still leads its thread, as "This message was deleted.": deleting it removes
	// only its words, so the replies stay readable and each keeps its own Delete.
	if (parent === undefined || conversation.data === undefined) {
		return { status: "loading" };
	}
	return {
		status: "ready",
		parent,
		timeline: { status: "ready", entries, pending, turns, earlier: earlierOf(pages) },
	};
}

interface SendOptions {
	parentEntryId?: string;
	/** The page the developer asks from, as the Hephaestus panel names it. */
	pageContext?: string;
}

export type ConversationActions = TimelineActions & {
	send: (text: string, options?: SendOptions) => void;
};

function reportFailure(fallback: string) {
	return (error: unknown) => {
		toast.error(problemDetailOf(error, fallback));
	};
}

function useVote(workspaceSlug: string) {
	const queryClient = useQueryClient();
	const callbacks = {
		onSuccess: (entry: MentorEntry) => {
			inOrder(queryClient, workspaceSlug, () => applyVote(queryClient, workspaceSlug, entry));
		},
		onError: reportFailure("We could not save your vote. Try again."),
	};
	const set = useMutation({ ...setMentorVoteMutation(), ...callbacks });
	const clear = useMutation({ ...clearMentorVoteMutation(), ...callbacks });
	const show = (entry: MentorEntry, vote: MessageVote | undefined) =>
		patchShownEntry(queryClient, workspaceSlug, entry, (shown) => ({ ...shown, vote }));
	const submit = async (entry: MentorEntry, vote: MessageVote | null) => {
		const previous = entry.vote;
		await cancelScope(queryClient, workspaceSlug, entry.parentEntryId);
		show(entry, vote ?? undefined);
		const restore = { onError: () => show(entry, previous) };
		const path = { workspaceSlug, entryId: entry.id };
		if (vote === null) {
			clear.mutate({ path }, restore);
		} else {
			set.mutate({ path, body: { vote } }, restore);
		}
	};
	return (entry: MentorEntry, vote: MessageVote | null) => {
		void submit(entry, vote);
	};
}

/** Every write the developer can make to their conversation. A user view renders none of them. */
export function useConversationActions(workspaceSlug: string): ConversationActions {
	const queryClient = useQueryClient();
	/** Removes every attempt to send one message from the unsent ones. */
	const forgetSend = (requestId: string) => {
		const sends = queryClient.getMutationCache();
		for (const mutation of sends.findAll({ mutationKey: submitKey(workspaceSlug) })) {
			if (turnRequestOf(mutation.state.variables)?.requestId === requestId) {
				sends.remove(mutation);
			}
		}
	};
	const submit = useMutation({
		...submitMentorTurnMutation(),
		mutationKey: submitKey(workspaceSlug),
		// A message the server did not store, unreachable or refused, stays on screen with Retry until
		// the developer deletes it or the page reloads.
		gcTime: Infinity,
		onSuccess: (submission, variables) => {
			const requestId = turnRequestOf(variables)?.requestId;
			inOrder(queryClient, workspaceSlug, () => {
				applySubmission(queryClient, workspaceSlug, submission);
				// The stored copy is in the pages now, so the send is not kept for a later read to miss.
				// A write that throws leaves it, so the accepted question stays on screen.
				if (requestId !== undefined) {
					forgetSend(requestId);
				}
			});
		},
	});
	const stop = useMutation({
		...updateMentorTurnStatusMutation(),
		onSuccess: (turn) => {
			inOrder(queryClient, workspaceSlug, () => applyTurn(queryClient, workspaceSlug, turn));
		},
		onError: reportFailure("We could not stop the answer. Try again."),
	});
	const remove = useMutation({
		...deleteMentorEntryMutation(),
		onError: reportFailure("We could not delete your message. Try again."),
	});
	const vote = useVote(workspaceSlug);

	const submitRequest = (body: MentorTurnRequest) => {
		submit.mutate({ path: { workspaceSlug }, body });
	};

	const send = (text: string, options: SendOptions = {}) => {
		submitRequest({ requestId: crypto.randomUUID(), text, ...options });
	};

	return {
		send,
		ask: {
			onTryAgain: (question) => send(question.text, { parentEntryId: question.parentEntryId }),
			onRetrySend: (requestId) => {
				const request = queryClient
					.getMutationCache()
					.findAll({ mutationKey: submitKey(workspaceSlug) })
					.map(({ state }) => turnRequestOf(state.variables))
					.find((candidate) => candidate?.requestId === requestId);
				if (request !== undefined) {
					submitRequest(request);
				}
			},
		},
		onDiscardSend: forgetSend,
		onStop: (turnId) => {
			stop.mutate({ path: { workspaceSlug, turnId }, body: { status: "STOPPED" } });
		},
		onVote: vote,
		onDelete: (entry) => {
			remove.mutate(
				{ path: { workspaceSlug, entryId: entry.id } },
				{
					onSuccess: () =>
						patchShownEntry(queryClient, workspaceSlug, entry, (shown) => ({
							...shown,
							deleted: true,
							text: "",
						})),
				},
			);
		},
	};
}

const readKey = (workspaceSlug: string) => ["markMentorConversationRead", workspaceSlug] as const;

/**
 * The read this device is still sending: the time it asked the server to mark. It shows as read
 * while the request and its retries run, and drops by itself when they fail. The server's own marker,
 * `lastReadAt`, comes only from the server (a snapshot, a `read` event or the request's answer), so
 * a failed request here never lowers what another device confirmed.
 */
function usePendingRead(workspaceSlug: string): Date | undefined {
	const pending = useMutationState({
		filters: { mutationKey: readKey(workspaceSlug), status: "pending" },
		select: ({ state }) => readAtOf(state.variables),
	});
	let latest: Date | undefined;
	for (const at of pending) {
		if (at !== undefined && (latest === undefined || at > latest)) {
			latest = at;
		}
	}
	return latest;
}

/**
 * Whether Hephaestus answered, in the conversation or a thread, since the developer last read it on any
 * device, or on this one while its read is still on the way.
 */
export function useConversationUnread(workspaceSlug: string): boolean {
	const conversation = useQuery(conversationOptions(workspaceSlug));
	return hasUnreadAnswer(conversation.data, usePendingRead(workspaceSlug));
}

/**
 * Marks the conversation read up to its newest answer while a view of it is open and the page is
 * visible, and again for each answer that lands then. A view in a background tab marks nothing, so
 * an answer that lands there stays unread on every device until the page shows. The server keeps the
 * later of two times, so a late request never lowers it, and every device reads the same marker.
 */
export function useMarkConversationRead(workspaceSlug: string, enabled: boolean) {
	const queryClient = useQueryClient();
	const visible = usePageVisible();
	const conversation = useQuery({
		...conversationOptions(workspaceSlug),
		enabled,
	}).data;
	const pendingRead = usePendingRead(workspaceSlug);
	const mark = useMutation({
		...markMentorConversationReadMutation(),
		mutationKey: readKey(workspaceSlug),
		// The dot clears at once; a request that fails is sent again a few times, each wait longer.
		retry: READ_RETRIES,
		retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
		onSuccess: ({ lastReadAt }) => {
			if (lastReadAt !== undefined) {
				applyRead(queryClient, workspaceSlug, lastReadAt);
			}
		},
	});
	const answeredAt = conversation?.lastAnswerAt;
	const unread = hasUnreadAnswer(conversation, pendingRead);
	const { mutate, isError, variables } = mark;
	// A read that failed every attempt is not sent again for the same answer; the next answer tries.
	const failed = isError && variables.body.at.getTime() === answeredAt?.getTime();
	useEffect(() => {
		if (enabled && visible && unread && answeredAt !== undefined && !failed) {
			mutate({ path: { workspaceSlug }, body: { at: answeredAt } });
		}
	}, [enabled, visible, unread, answeredAt, failed, workspaceSlug, mutate]);
}
