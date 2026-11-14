import {
	hashKey,
	type InfiniteData,
	infiniteQueryOptions,
	type QueryClient,
	type QueryKey,
} from "@tanstack/react-query";

import {
	getConversationOptions,
	getConversationQueryKey,
	getConversationThreadInfiniteOptions,
	getConversationThreadInfiniteQueryKey,
	listConversationEntriesInfiniteOptions,
	listConversationEntriesInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type {
	Conversation,
	CursorPageDtoConversationEntry,
	ConversationEntry,
	ConversationThread,
	ConversationThreadSummary,
	ConversationTurn,
	ProblemDetail,
} from "@/api/types.gen";
import { isRemovedEntry } from "@/lib/conversation-entry";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

/**
 * the conversation's cache. The REST reads fill it; the live stream, the app's own writes and
 * a reconnect's snapshot all patch it through the functions here, so there is one writer per shape.
 */

/**
 * Pages are cached newest first, as TanStack Query keeps a list read backwards: the first read names
 * no cursor and gets the newest page, and each older page is the next one, read with its `nextCursor`.
 * A refetch starts at the newest page and walks back through as many pages as were loaded.
 */
export function conversationEntriesOptions(workspaceSlug: string) {
	const options = { path: { workspaceSlug } };
	const read = listConversationEntriesInfiniteOptions(options);
	return {
		...read,
		queryFn: orderedRead(workspaceSlug, read.queryFn),
		initialPageParam: options,
		getNextPageParam: (older: CursorPageDtoConversationEntry) => older.nextCursor,
	};
}

export function conversationThreadOptions(workspaceSlug: string, parentEntryId: string) {
	const options = { path: { workspaceSlug, entryId: parentEntryId } };
	const read = getConversationThreadInfiniteOptions(options);
	return infiniteQueryOptions<
		ConversationThreadPage,
		ProblemDetail,
		InfiniteData<ConversationThreadPage>,
		typeof read.queryKey,
		string | typeof options
	>({
		queryKey: read.queryKey,
		queryFn: orderedRead(workspaceSlug, flatThread(read.queryFn)),
		initialPageParam: options,
		getNextPageParam: (older: ConversationThreadPage) => older.nextCursor,
	});
}

/**
 * A thread as the cache keeps it: the message and its page of replies at one level, so the functions
 * here patch a thread's pages and the timeline's pages alike.
 */
export type ConversationThreadPage = Omit<ConversationThread, "replies"> &
	CursorPageDtoConversationEntry;

function flatThread<Context>(
	read:
		| ((context: Context) => ConversationThread | Promise<ConversationThread>)
		| undefined
		| symbol,
): (context: Context) => Promise<ConversationThreadPage> {
	return async (context) => {
		if (typeof read !== "function") {
			throw new TypeError("The generated read has no query function");
		}
		const { replies, ...thread } = await read(context);
		return { ...thread, ...replies };
	};
}

/** The conversation's state: its active turns, threads and read marker. */
export function conversationOptions(workspaceSlug: string) {
	const read = getConversationOptions({ path: { workspaceSlug } });
	return { ...read, queryFn: orderedRead(workspaceSlug, read.queryFn) };
}

/** The loaded entries in the order they were written: the oldest page first. */
export function entriesOf(pages: { content: ConversationEntry[] }[]): ConversationEntry[] {
	return [...pages].reverse().flatMap((page) => page.content);
}

const entriesKey = (workspaceSlug: string) =>
	listConversationEntriesInfiniteQueryKey({ path: { workspaceSlug } });

const threadKey = (workspaceSlug: string, parentEntryId: string) =>
	getConversationThreadInfiniteQueryKey({ path: { workspaceSlug, entryId: parentEntryId } });

const conversationKey = (workspaceSlug: string) =>
	getConversationQueryKey({ path: { workspaceSlug } });

const CONVERSATION_PAGES: ReadonlySet<string> = new Set(
	[entriesKey(""), threadKey("", "")].map(([key]) => key._id),
);

const CONVERSATION_READS: ReadonlySet<string> = new Set([
	conversationKey("")[0]._id,
	...CONVERSATION_PAGES,
]);

/** Reads the whole conversation again: its state, its pages and every loaded thread. */
export async function rereadConversation(queryClient: QueryClient, workspaceSlug: string) {
	await invalidateWorkspaceReads(queryClient, workspaceSlug, CONVERSATION_READS);
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

/** Per query, the read in flight that holds its workspace's writes, released once its response is in the cache. */
const readsInFlight = new WeakMap<QueryClient, Map<string, () => void>>();

function readsOf(queryClient: QueryClient): Map<string, () => void> {
	const known = readsInFlight.get(queryClient);
	if (known !== undefined) {
		return known;
	}
	const byQuery = new Map<string, () => void>();
	readsInFlight.set(queryClient, byQuery);
	// TanStack Query writes a response to the cache after the query function returns, so the read
	// lands when its query stops fetching: success, error, cancel, or removal.
	queryClient.getQueryCache().subscribe(({ type, query }) => {
		const release = byQuery.get(query.queryHash);
		if (release !== undefined && (type === "removed" || query.state.fetchStatus !== "fetching")) {
			byQuery.delete(query.queryHash);
			release();
		}
	});
	return byQuery;
}

/**
 * Every read of the conversation — the first load, a refetch on focus, mount or interval, a reread,
 * an older page — holds its workspace's writes until its response is in the cache, as a reread
 * does. A live event that arrives meanwhile then patches what the response wrote instead of being
 * overwritten by a response older than it.
 */
function orderedRead<Context extends { client: QueryClient; queryKey: QueryKey }, Data>(
	workspaceSlug: string,
	read: ((context: Context) => Data | Promise<Data>) | undefined | symbol,
): (context: Context) => Promise<Data> {
	return async (context) => {
		if (typeof read !== "function") {
			throw new TypeError("The generated read has no query function");
		}
		const byQuery = readsOf(context.client);
		const hash = hashKey(context.queryKey);
		// An infinite query reads each loaded page in one fetch; the first page's call holds it.
		if (!byQuery.has(hash)) {
			// oxlint-disable-next-line promise/avoid-new -- the read lands on a cache event, which no promise TanStack Query exposes follows.
			const landed = new Promise<void>((resolve) => {
				byQuery.set(hash, resolve);
			});
			const byWorkspace = queueOf(context.client);
			const previous = byWorkspace.get(workspaceSlug);
			hold(
				byWorkspace,
				workspaceSlug,
				previous === undefined
					? landed
					: previous.then(
							async () => landed,
							async () => landed,
						),
			);
		}
		return read(context);
	};
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

type Pages<P extends { content: ConversationEntry[] }> = InfiniteData<P>;

function newestSeq(pages: { content: ConversationEntry[] }[]): number | undefined {
	return pages[0]?.content.at(-1)?.seq;
}

/**
 * A copy of a message that arrives late, such as a send's answer, never brings back words the
 * developer deleted and never reopens its ended turn.
 */
function laterCopy(stored: ConversationEntry, incoming: ConversationEntry): ConversationEntry {
	if (stored.deleted && !incoming.deleted) {
		return stored;
	}
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
function upsertEntry<P extends { content: ConversationEntry[]; nextCursor?: string }>(
	data: Pages<P>,
	entry: ConversationEntry,
): Pages<P> {
	const remove = isRemovedEntry(entry);
	const index = data.pages.findIndex((page) => page.content.some(({ id }) => id === entry.id));
	if (index !== -1) {
		return {
			...data,
			pages: data.pages.map((page, pageIndex) =>
				pageIndex === index
					? {
							...page,
							content: remove
								? page.content.filter(({ id }) => id !== entry.id)
								: page.content.map((stored) =>
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
		target = data.pages.findIndex((page) => (page.content[0]?.seq ?? Infinity) <= entry.seq);
		if (target === -1) {
			if (data.pages.at(-1)?.nextCursor !== undefined) {
				return data;
			}
			target = data.pages.length - 1;
		}
	}
	return {
		...data,
		pages: data.pages.map((page, pageIndex) =>
			pageIndex === target
				? { ...page, content: [...page.content, entry].sort((a, b) => a.seq - b.seq) }
				: page,
		),
	};
}

function patchEntry<P extends { content: ConversationEntry[] }>(
	data: Pages<P>,
	entryId: string,
	patch: (entry: ConversationEntry) => ConversationEntry,
): Pages<P> {
	return {
		...data,
		pages: data.pages.map((page) =>
			page.content.some(({ id }) => id === entryId)
				? {
						...page,
						content: page.content.map((entry) => (entry.id === entryId ? patch(entry) : entry)),
					}
				: page,
		),
	};
}

/** A reply moves its parent's summary forward; the server sends the reply, not the parent again. */
function summaryAfter(
	summary: ConversationThreadSummary | undefined,
	reply: ConversationEntry & { parentEntryId: string },
): ConversationThreadSummary | undefined {
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
	update: (summary: ConversationThreadSummary | undefined) => ConversationThreadSummary | undefined,
) {
	const patch = (parent: ConversationEntry): ConversationEntry => ({
		...parent,
		thread: update(parent.thread),
	});
	queryClient.setQueryData<Pages<CursorPageDtoConversationEntry>>(
		entriesKey(workspaceSlug),
		(data) => data && patchEntry(data, parentEntryId, patch),
	);
	queryClient.setQueryData<Pages<ConversationThreadPage>>(
		threadKey(workspaceSlug, parentEntryId),
		(data) =>
			data && {
				...data,
				pages: data.pages.map((page) => ({ ...page, parent: patch(page.parent) })),
			},
	);
}

/**
 * Whether Hephaestus answered, in the conversation or a thread, after the later of the server's read marker
 * and `pendingReadAt`, a read this device is still sending.
 */
export function hasUnreadAnswer(
	conversation: Conversation | undefined,
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
	queryClient.setQueryData<Conversation>(
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

/** A shown Hephaestus answer, in the conversation or any thread, moves the conversation's newest answer. */
function applyAnswer(queryClient: QueryClient, workspaceSlug: string, entry: ConversationEntry) {
	if (entry.kind !== "ANSWER" || entry.deleted) {
		return;
	}
	queryClient.setQueryData<Conversation>(
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

export function applyEntry(
	queryClient: QueryClient,
	workspaceSlug: string,
	entry: ConversationEntry,
) {
	applyAnswer(queryClient, workspaceSlug, entry);
	const { parentEntryId } = entry;
	if (parentEntryId === undefined) {
		queryClient.setQueryData<Pages<CursorPageDtoConversationEntry>>(
			entriesKey(workspaceSlug),
			(data) => data && upsertEntry(data, entry),
		);
		// The message may head an open thread, which shows it too.
		queryClient.setQueryData<Pages<ConversationThreadPage>>(
			threadKey(workspaceSlug, entry.id),
			(data) =>
				data && {
					...data,
					pages: data.pages.map((page) => ({ ...page, parent: laterCopy(page.parent, entry) })),
				},
		);
		return;
	}
	const reply = { ...entry, parentEntryId };
	queryClient.setQueryData<Pages<ConversationThreadPage>>(
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
	entry: Pick<ConversationEntry, "id" | "parentEntryId">,
	patch: (entry: ConversationEntry) => ConversationEntry,
) {
	if (entry.parentEntryId === undefined) {
		queryClient.setQueryData<Pages<CursorPageDtoConversationEntry>>(
			entriesKey(workspaceSlug),
			(data) => data && patchEntry(data, entry.id, patch),
		);
		// The message may head an open thread, which shows it too.
		queryClient.setQueryData<Pages<ConversationThreadPage>>(
			threadKey(workspaceSlug, entry.id),
			(data) =>
				data && {
					...data,
					pages: data.pages.map((page) => ({ ...page, parent: patch(page.parent) })),
				},
		);
		return;
	}
	queryClient.setQueryData<Pages<ConversationThreadPage>>(
		threadKey(workspaceSlug, entry.parentEntryId),
		(data) => data && patchEntry(data, entry.id, patch),
	);
}

/**
 * The server's answer to a vote. It carries the whole message as the vote saw it, but only the vote
 * is news: words edited or deleted since stay, and a message no longer shown is not shown again.
 */
export function applyVote(
	queryClient: QueryClient,
	workspaceSlug: string,
	voted: ConversationEntry,
) {
	patchShownEntry(queryClient, workspaceSlug, voted, (shown) => ({ ...shown, vote: voted.vote }));
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

const isEnded = (status: ConversationTurn["status"] | undefined) =>
	status === "DONE" || status === "FAILED" || status === "STOPPED";

/**
 * Where a turn's state falls in its life: by attempt, and within one attempt RUNNING before QUEUED
 * (a lost lease queues it again) and an end last. A turn that ended stays ended.
 */
function turnOrder(turn: Pick<ConversationTurn, "attempt" | "status">): number {
	if (isEnded(turn.status)) {
		return Infinity;
	}
	return turn.attempt * 2 + (turn.status === "QUEUED" ? 1 : 0);
}

function withActiveTurn(turns: ConversationTurn[], turn: ConversationTurn): ConversationTurn[] {
	const others = turns.filter(({ id }) => id !== turn.id);
	if (turn.status !== "QUEUED" && turn.status !== "RUNNING") {
		return others;
	}
	return [...others, turn].sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime());
}

/** The question a turn answers carries how the turn ended, so a stopped or failed turn reads the same after a reload. */
function withTurnOutcome(entry: ConversationEntry, turn: ConversationTurn): ConversationEntry {
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

function patchQuestion<P extends { content: ConversationEntry[] }>(
	data: Pages<P>,
	turn: ConversationTurn,
): Pages<P> {
	return {
		...data,
		pages: data.pages.map((page) =>
			page.content.some(({ turnId }) => turnId === turn.id)
				? { ...page, content: page.content.map((entry) => withTurnOutcome(entry, turn)) }
				: page,
		),
	};
}

/** The turn as the cache shows it: among the active turns, or ended on its question. */
function cachedTurn(
	queryClient: QueryClient,
	workspaceSlug: string,
	turn: ConversationTurn,
): { active?: ConversationTurn; order?: number } {
	const active = queryClient
		.getQueryData<Conversation>(conversationKey(workspaceSlug))
		?.activeTurns.find(({ id }) => id === turn.id);
	if (active !== undefined) {
		return { active, order: turnOrder(active) };
	}
	const question = queryClient
		.getQueryData<Pages<CursorPageDtoConversationEntry | ConversationThreadPage>>(
			scopeKey(workspaceSlug, turn.parentEntryId),
		)
		?.pages.flatMap(({ content }) => content)
		.find((entry) => entry.kind === "USER" && entry.turnId === turn.id);
	return isEnded(question?.turnStatus) ? { order: Infinity } : {};
}

/**
 * A state older than the one the cache shows, from a snapshot, a read or the live stream, such as a
 * send's answer that arrives after the turn started or ended.
 */
function isStale(queryClient: QueryClient, workspaceSlug: string, turn: ConversationTurn): boolean {
	const { order } = cachedTurn(queryClient, workspaceSlug, turn);
	return order !== undefined && turnOrder(turn) < order;
}

export function applyTurn(queryClient: QueryClient, workspaceSlug: string, turn: ConversationTurn) {
	if (isStale(queryClient, workspaceSlug, turn)) {
		return;
	}
	if (turn.parentEntryId === undefined) {
		queryClient.setQueryData<Pages<CursorPageDtoConversationEntry>>(
			entriesKey(workspaceSlug),
			(data) => data && patchQuestion(data, turn),
		);
	} else {
		queryClient.setQueryData<Pages<ConversationThreadPage>>(
			threadKey(workspaceSlug, turn.parentEntryId),
			(data) => data && patchQuestion(data, turn),
		);
	}
	queryClient.setQueryData<Conversation>(
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
	{ question, turn }: { question: ConversationEntry; turn: ConversationTurn },
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
	snapshot: Conversation,
): Promise<void> {
	queryClient.setQueryData<Conversation>(conversationKey(workspaceSlug), snapshot);
	// Patch before invalidating: a later `setQueryData` would mark the invalidated pages fresh again.
	for (const summary of snapshot.threads) {
		patchThreadSummary(queryClient, workspaceSlug, summary.parentEntryId, () => summary);
	}
	await invalidateWorkspaceReads(queryClient, workspaceSlug, CONVERSATION_PAGES);
}
