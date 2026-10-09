import type { InfiniteData, QueryClient } from "@tanstack/react-query";

import {
	getMentorConversationQueryKey,
	getMentorThreadInfiniteOptions,
	getMentorThreadInfiniteQueryKey,
	listMentorEntriesInfiniteOptions,
	listMentorEntriesInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type {
	MentorConversation,
	MentorEntry,
	MentorEntryPage,
	MentorThread,
	MentorThreadSummary,
	MentorTurn,
} from "@/api/types.gen";
import { isRemovedEntry } from "@/lib/mentor-entry";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

/**
 * The Heph conversation's cache. The REST reads fill it; the live stream, the app's own writes and
 * a reconnect's snapshot all patch it through the functions here, so there is one writer per shape.
 */

/**
 * Pages are cached newest first, as TanStack Query keeps a list read backwards: the first read names
 * no cursor and gets the newest page, and each older page is the next one, read with `earlierBefore`.
 * A refetch starts at the newest page and walks back through as many pages as were loaded.
 */
export function hephEntriesOptions(workspaceSlug: string) {
	const options = { path: { workspaceSlug } };
	return {
		...listMentorEntriesInfiniteOptions(options),
		initialPageParam: options,
		getNextPageParam: (older: MentorEntryPage) => older.earlierBefore,
	};
}

export function hephThreadOptions(workspaceSlug: string, parentEntryId: string) {
	const options = { path: { workspaceSlug, entryId: parentEntryId } };
	return {
		...getMentorThreadInfiniteOptions(options),
		initialPageParam: options,
		getNextPageParam: (older: MentorThread) => older.earlierBefore,
	};
}

/** The loaded entries in the order they were written: the oldest page first. */
export function entriesOf(pages: { entries: MentorEntry[] }[]): MentorEntry[] {
	return [...pages].reverse().flatMap((page) => page.entries);
}

const entriesKey = (workspaceSlug: string) =>
	listMentorEntriesInfiniteQueryKey({ path: { workspaceSlug } });

const threadKey = (workspaceSlug: string, parentEntryId: string) =>
	getMentorThreadInfiniteQueryKey({ path: { workspaceSlug, entryId: parentEntryId } });

const conversationKey = (workspaceSlug: string) =>
	getMentorConversationQueryKey({ path: { workspaceSlug } });

const HEPH_PAGES: ReadonlySet<string> = new Set(
	[entriesKey(""), threadKey("", "")].map(([key]) => key._id),
);

const HEPH_READS: ReadonlySet<string> = new Set([conversationKey("")[0]._id, ...HEPH_PAGES]);

/** Reads the whole conversation again: its state, its pages and every loaded thread. */
export async function rereadConversation(queryClient: QueryClient, workspaceSlug: string) {
	await invalidateWorkspaceReads(queryClient, workspaceSlug, HEPH_READS);
}

/** Per workspace, the last write still in flight: a reread, or a write queued behind one. */
const pendingWrites = new WeakMap<QueryClient, Map<string, Promise<void>>>();

function queueOf(queryClient: QueryClient): Map<string, Promise<void>> {
	let byWorkspace = pendingWrites.get(queryClient);
	if (byWorkspace === undefined) {
		byWorkspace = new Map();
		pendingWrites.set(queryClient, byWorkspace);
	}
	return byWorkspace;
}

function hold(
	byWorkspace: Map<string, Promise<void>>,
	workspaceSlug: string,
	write: Promise<void>,
) {
	const current: Promise<void> = write.finally(() => {
		if (byWorkspace.get(workspaceSlug) === current) {
			byWorkspace.delete(workspaceSlug);
		}
	});
	byWorkspace.set(workspaceSlug, current);
}

/**
 * Writes to one workspace's conversation run in the order they arrive: a snapshot, a live event,
 * the answer to the developer's own write. A reread's response can be older than a write that
 * arrives while it is in flight, so a write waits for every reread before it and then patches what
 * that reread wrote. With nothing in flight, it runs at once. A failed step does not hold the ones
 * after it.
 */
export function inOrder(queryClient: QueryClient, workspaceSlug: string, write: () => void) {
	const byWorkspace = queueOf(queryClient);
	const previous = byWorkspace.get(workspaceSlug);
	if (previous === undefined) {
		write();
		return;
	}
	hold(byWorkspace, workspaceSlug, previous.then(write, write));
}

/** As {@link inOrder}, for a write that starts a reread: the writes after it wait until it lands. */
export function rereadInOrder(
	queryClient: QueryClient,
	workspaceSlug: string,
	reread: () => Promise<void>,
) {
	const byWorkspace = queueOf(queryClient);
	const previous = byWorkspace.get(workspaceSlug);
	hold(
		byWorkspace,
		workspaceSlug,
		previous === undefined ? reread() : previous.then(reread, reread),
	);
}

type Pages<P extends { entries: MentorEntry[] }> = InfiniteData<P>;

function newestSeq(pages: { entries: MentorEntry[] }[]): number | undefined {
	return pages[0]?.entries.at(-1)?.seq;
}

/** A copy of a question that arrives late, such as a send's answer, never reopens its ended turn. */
function laterCopy(stored: MentorEntry, incoming: MentorEntry): MentorEntry {
	if (!isEnded(stored.turnStatus) || isEnded(incoming.turnStatus)) {
		return incoming;
	}
	const { turnError: _late, ...copy } = incoming;
	return {
		...copy,
		turnStatus: stored.turnStatus,
		...(stored.turnError === undefined ? {} : { turnError: stored.turnError }),
	};
}

/**
 * Puts `entry` where its `seq` belongs: in place of the stored copy, at the end of the newest page,
 * or in order inside the loaded range. An entry older than every loaded page is left for the read
 * that reaches it.
 */
function upsertEntry<P extends { entries: MentorEntry[]; earlierBefore?: number }>(
	data: Pages<P>,
	entry: MentorEntry,
): Pages<P> {
	const remove = isRemovedEntry(entry);
	const index = data.pages.findIndex((page) => page.entries.some(({ id }) => id === entry.id));
	if (index !== -1) {
		return {
			...data,
			pages: data.pages.map((page, pageIndex) =>
				pageIndex === index
					? {
							...page,
							entries: remove
								? page.entries.filter(({ id }) => id !== entry.id)
								: page.entries.map((stored) =>
										stored.id === entry.id ? laterCopy(stored, entry) : stored,
									),
						}
					: page,
			),
		};
	}
	if (remove || data.pages.length === 0) {
		return data;
	}
	const newest = newestSeq(data.pages);
	let target = 0;
	if (newest !== undefined && entry.seq < newest) {
		target = data.pages.findIndex((page) => (page.entries[0]?.seq ?? Infinity) <= entry.seq);
		if (target === -1) {
			if (data.pages.at(-1)?.earlierBefore !== undefined) {
				return data;
			}
			target = data.pages.length - 1;
		}
	}
	return {
		...data,
		pages: data.pages.map((page, pageIndex) =>
			pageIndex === target
				? { ...page, entries: [...page.entries, entry].sort((a, b) => a.seq - b.seq) }
				: page,
		),
	};
}

function patchEntry<P extends { entries: MentorEntry[] }>(
	data: Pages<P>,
	entryId: string,
	patch: (entry: MentorEntry) => MentorEntry,
): Pages<P> {
	return {
		...data,
		pages: data.pages.map((page) =>
			page.entries.some(({ id }) => id === entryId)
				? {
						...page,
						entries: page.entries.map((entry) => (entry.id === entryId ? patch(entry) : entry)),
					}
				: page,
		),
	};
}

/** A reply moves its parent's summary forward; the server sends the reply, not the parent again. */
function summaryAfter(
	summary: MentorThreadSummary | undefined,
	reply: MentorEntry & { parentEntryId: string },
): MentorThreadSummary | undefined {
	if (isRemovedEntry(reply)) {
		return summary && { ...summary, replyCount: Math.max(0, summary.replyCount - 1) };
	}
	if (summary === undefined) {
		return {
			conversationId: reply.conversationId,
			parentEntryId: reply.parentEntryId,
			replyCount: 1,
			lastReplyAt: reply.createdAt,
			latestSeq: reply.seq,
		};
	}
	if (reply.seq <= summary.latestSeq) {
		return summary;
	}
	return {
		...summary,
		replyCount: summary.replyCount + 1,
		lastReplyAt: reply.createdAt,
		latestSeq: reply.seq,
	};
}

/**
 * A message's thread summary shows on the message in the main pages and above its open thread. This
 * is its one writer, so both places show the same count.
 */
function patchThreadSummary(
	queryClient: QueryClient,
	workspaceSlug: string,
	parentEntryId: string,
	update: (summary: MentorThreadSummary | undefined) => MentorThreadSummary | undefined,
) {
	const patch = (parent: MentorEntry): MentorEntry => ({
		...parent,
		thread: update(parent.thread),
	});
	queryClient.setQueryData<Pages<MentorEntryPage>>(
		entriesKey(workspaceSlug),
		(data) => data && patchEntry(data, parentEntryId, patch),
	);
	queryClient.setQueryData<Pages<MentorThread>>(
		threadKey(workspaceSlug, parentEntryId),
		(data) =>
			data && {
				...data,
				pages: data.pages.map((page) => ({ ...page, parent: patch(page.parent) })),
			},
	);
}

/**
 * Whether Heph answered, in the conversation or a thread, after the later of the server's read marker
 * and `pendingReadAt`, a read this device is still sending.
 */
export function hasUnreadAnswer(
	conversation: MentorConversation | undefined,
	pendingReadAt?: Date,
): boolean {
	const answered = conversation?.lastAnswerAt;
	if (answered === undefined) {
		return false;
	}
	return [conversation?.lastReadAt, pendingReadAt].every(
		(read) => read === undefined || answered > read,
	);
}

/** The server's read marker, from a snapshot, a `read` event or a read request's answer: it only moves forward. */
export function applyRead(queryClient: QueryClient, workspaceSlug: string, at: Date) {
	queryClient.setQueryData<MentorConversation>(
		conversationKey(workspaceSlug),
		(conversation) =>
			conversation && {
				...conversation,
				lastReadAt:
					conversation.lastReadAt !== undefined && conversation.lastReadAt > at
						? conversation.lastReadAt
						: at,
			},
	);
}

/** A shown Heph answer, in the conversation or any thread, moves the conversation's newest answer. */
function applyAnswer(queryClient: QueryClient, workspaceSlug: string, entry: MentorEntry) {
	if (entry.kind !== "HEPH" || entry.deleted) {
		return;
	}
	queryClient.setQueryData<MentorConversation>(
		conversationKey(workspaceSlug),
		(conversation) =>
			conversation && {
				...conversation,
				lastAnswerAt:
					conversation.lastAnswerAt !== undefined && conversation.lastAnswerAt > entry.createdAt
						? conversation.lastAnswerAt
						: entry.createdAt,
			},
	);
}

export function applyEntry(queryClient: QueryClient, workspaceSlug: string, entry: MentorEntry) {
	applyAnswer(queryClient, workspaceSlug, entry);
	const { parentEntryId } = entry;
	if (parentEntryId === undefined) {
		queryClient.setQueryData<Pages<MentorEntryPage>>(
			entriesKey(workspaceSlug),
			(data) => data && upsertEntry(data, entry),
		);
		// The message may head an open thread, which shows it too.
		queryClient.setQueryData<Pages<MentorThread>>(
			threadKey(workspaceSlug, entry.id),
			(data) => data && { ...data, pages: data.pages.map((page) => ({ ...page, parent: entry })) },
		);
		return;
	}
	const reply = { ...entry, parentEntryId };
	queryClient.setQueryData<Pages<MentorThread>>(
		threadKey(workspaceSlug, parentEntryId),
		(data) => data && upsertEntry(data, entry),
	);
	patchThreadSummary(queryClient, workspaceSlug, parentEntryId, (summary) =>
		summaryAfter(summary, reply),
	);
}

/** Patches one message wherever it is shown, before the server has answered the write. */
export function patchShownEntry(
	queryClient: QueryClient,
	workspaceSlug: string,
	entry: Pick<MentorEntry, "id" | "parentEntryId">,
	patch: (entry: MentorEntry) => MentorEntry,
) {
	if (entry.parentEntryId === undefined) {
		queryClient.setQueryData<Pages<MentorEntryPage>>(
			entriesKey(workspaceSlug),
			(data) => data && patchEntry(data, entry.id, patch),
		);
		// The message may head an open thread, which shows it too.
		queryClient.setQueryData<Pages<MentorThread>>(
			threadKey(workspaceSlug, entry.id),
			(data) =>
				data && {
					...data,
					pages: data.pages.map((page) => ({ ...page, parent: patch(page.parent) })),
				},
		);
		return;
	}
	queryClient.setQueryData<Pages<MentorThread>>(
		threadKey(workspaceSlug, entry.parentEntryId),
		(data) => data && patchEntry(data, entry.id, patch),
	);
}

/** The pages of one scope: the main conversation, or one thread. */
const scopeKey = (workspaceSlug: string, parentEntryId: string | undefined) =>
	parentEntryId === undefined ? entriesKey(workspaceSlug) : threadKey(workspaceSlug, parentEntryId);

/** Stops a read of a scope's pages in flight, so it cannot overwrite a patch made before the server answers. */
export async function cancelScope(
	queryClient: QueryClient,
	workspaceSlug: string,
	parentEntryId: string | undefined,
) {
	await queryClient.cancelQueries({ queryKey: scopeKey(workspaceSlug, parentEntryId) });
}

const isEnded = (status: MentorTurn["status"] | undefined) =>
	status === "DONE" || status === "FAILED" || status === "STOPPED";

/**
 * Where a turn's state falls in its life: by attempt, and within one attempt RUNNING before QUEUED
 * (a lost lease queues it again) and an end last. A turn that ended stays ended.
 */
function turnOrder(turn: Pick<MentorTurn, "attempt" | "status">): number {
	if (isEnded(turn.status)) {
		return Infinity;
	}
	return turn.attempt * 2 + (turn.status === "QUEUED" ? 1 : 0);
}

function withActiveTurn(turns: MentorTurn[], turn: MentorTurn): MentorTurn[] {
	const others = turns.filter(({ id }) => id !== turn.id);
	if (turn.status !== "QUEUED" && turn.status !== "RUNNING") {
		return others;
	}
	return [...others, turn].sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime());
}

/** The question a turn answers carries how the turn ended, so a stopped or failed turn reads the same after a reload. */
function withTurnOutcome(entry: MentorEntry, turn: MentorTurn): MentorEntry {
	if (entry.kind !== "USER" || entry.turnId !== turn.id) {
		return entry;
	}
	const { turnError: _previousError, ...question } = entry;
	return {
		...question,
		turnStatus: turn.status,
		...(turn.status === "FAILED" && turn.error !== undefined ? { turnError: turn.error } : {}),
	};
}

function patchQuestion<P extends { entries: MentorEntry[] }>(
	data: Pages<P>,
	turn: MentorTurn,
): Pages<P> {
	return {
		...data,
		pages: data.pages.map((page) =>
			page.entries.some(({ turnId }) => turnId === turn.id)
				? { ...page, entries: page.entries.map((entry) => withTurnOutcome(entry, turn)) }
				: page,
		),
	};
}

/** The turn as the cache shows it: among the active turns, or ended on its question. */
function cachedTurn(
	queryClient: QueryClient,
	workspaceSlug: string,
	turn: MentorTurn,
): { active?: MentorTurn; order?: number } {
	const active = queryClient
		.getQueryData<MentorConversation>(conversationKey(workspaceSlug))
		?.activeTurns.find(({ id }) => id === turn.id);
	if (active !== undefined) {
		return { active, order: turnOrder(active) };
	}
	const question = queryClient
		.getQueryData<Pages<MentorEntryPage | MentorThread>>(
			scopeKey(workspaceSlug, turn.parentEntryId),
		)
		?.pages.flatMap(({ entries }) => entries)
		.find((entry) => entry.kind === "USER" && entry.turnId === turn.id);
	return isEnded(question?.turnStatus) ? { order: Infinity } : {};
}

/**
 * A state older than the one the cache shows, from a snapshot, a read or the live stream, such as a
 * send's answer that arrives after the turn started or ended.
 */
function isStale(queryClient: QueryClient, workspaceSlug: string, turn: MentorTurn): boolean {
	const { order } = cachedTurn(queryClient, workspaceSlug, turn);
	return order !== undefined && turnOrder(turn) < order;
}

export function applyTurn(queryClient: QueryClient, workspaceSlug: string, turn: MentorTurn) {
	if (isStale(queryClient, workspaceSlug, turn)) {
		return;
	}
	if (turn.parentEntryId === undefined) {
		queryClient.setQueryData<Pages<MentorEntryPage>>(
			entriesKey(workspaceSlug),
			(data) => data && patchQuestion(data, turn),
		);
	} else {
		queryClient.setQueryData<Pages<MentorThread>>(
			threadKey(workspaceSlug, turn.parentEntryId),
			(data) => data && patchQuestion(data, turn),
		);
	}
	queryClient.setQueryData<MentorConversation>(
		conversationKey(workspaceSlug),
		(conversation) =>
			conversation && {
				...conversation,
				activeTurns: withActiveTurn(conversation.activeTurns, turn),
			},
	);
}

/**
 * A send's answer: its stored question and its turn, as one update. An answer older than the turn the
 * cache shows only adds the question, with the turn as the cache knows it.
 */
export function applySubmission(
	queryClient: QueryClient,
	workspaceSlug: string,
	{ question, turn }: { question: MentorEntry; turn: MentorTurn },
) {
	if (isStale(queryClient, workspaceSlug, turn)) {
		const { active } = cachedTurn(queryClient, workspaceSlug, turn);
		applyEntry(queryClient, workspaceSlug, active ? withTurnOutcome(question, active) : question);
		return;
	}
	applyEntry(queryClient, workspaceSlug, question);
	applyTurn(queryClient, workspaceSlug, turn);
}

/**
 * A snapshot arrives on every (re)connect. An edit, a deletion or a turn's end can happen while the
 * stream is down without moving `latestSeq`, so every loaded page of the conversation and of its
 * threads is read again. Settles once the pages in view are read.
 */
export async function applySnapshot(
	queryClient: QueryClient,
	workspaceSlug: string,
	snapshot: MentorConversation,
): Promise<void> {
	queryClient.setQueryData<MentorConversation>(conversationKey(workspaceSlug), snapshot);
	// Patch before invalidating: a later `setQueryData` would mark the invalidated pages fresh again.
	for (const summary of snapshot.threads) {
		patchThreadSummary(queryClient, workspaceSlug, summary.parentEntryId, () => summary);
	}
	await invalidateWorkspaceReads(queryClient, workspaceSlug, HEPH_PAGES);
}
