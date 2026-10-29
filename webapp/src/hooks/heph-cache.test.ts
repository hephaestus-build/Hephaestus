import {
	focusManager,
	type InfiniteData,
	InfiniteQueryObserver,
	QueryClient,
} from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";

import {
	getMentorConversationQueryKey,
	getMentorThreadInfiniteQueryKey,
	listMentorEntriesInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type {
	MentorConversation,
	MentorEntry,
	MentorEntryPage,
	MentorThread,
	MentorTurn,
} from "@/api/types.gen";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";

import {
	applyEntry,
	applySnapshot,
	applySubmission,
	applyTurn,
	applyVote,
	entriesOf,
	hephEntriesOptions,
	hephThreadOptions,
	inOrder,
	patchShownEntry,
} from "./heph-cache";

const SLUG = "acme";
const MAIN = "c0000000-0000-4000-8000-000000000000";
const PARENT = "e0000000-0000-4000-8000-000000000001";

const entriesKey = listMentorEntriesInfiniteQueryKey({ path: { workspaceSlug: SLUG } });
const threadKey = getMentorThreadInfiniteQueryKey({
	path: { workspaceSlug: SLUG, entryId: PARENT },
});
const conversationKey = getMentorConversationQueryKey({ path: { workspaceSlug: SLUG } });

function entry(seq: number, fields: Partial<MentorEntry> = {}): MentorEntry {
	return {
		id: `e0000000-0000-4000-8000-${String(seq).padStart(12, "0")}`,
		conversationId: MAIN,
		seq,
		kind: "HEPH",
		surface: "APP",
		text: `Message ${seq}`,
		createdAt: new Date(Date.UTC(2026, 9, 9, 9, seq)),
		deleted: false,
		links: [],
		...fields,
	};
}

function reply(seq: number, fields: Partial<MentorEntry> = {}): MentorEntry {
	return entry(seq, {
		id: `f0000000-0000-4000-8000-${String(seq).padStart(12, "0")}`,
		conversationId: "c0000000-0000-4000-8000-000000000001",
		parentEntryId: PARENT,
		...fields,
	});
}

function turn(status: MentorTurn["status"], fields: Partial<MentorTurn> = {}): MentorTurn {
	return {
		id: "a0000000-0000-4000-8000-000000000001",
		conversationId: MAIN,
		requestId: "b0000000-0000-4000-8000-000000000001",
		status,
		attempt: status === "QUEUED" ? 0 : 1,
		surface: "APP",
		createdAt: new Date(Date.UTC(2026, 9, 9, 9, 0)),
		...fields,
	};
}

/** The loaded pages, newest first, as the infinite query caches them. */
function pages<P>(...loaded: P[]): InfiniteData<P> {
	return { pages: loaded, pageParams: loaded.map(() => ({})) };
}

let queryClient: QueryClient;

function mainEntries(): MentorEntry[] {
	return entriesOf(
		queryClient.getQueryData<InfiniteData<MentorEntryPage>>(entriesKey)?.pages ?? [],
	);
}

function threadSeqs(): number[] {
	const thread = queryClient.getQueryData<InfiniteData<MentorThread>>(threadKey);
	return entriesOf(thread?.pages ?? []).map(({ seq }) => seq);
}

function mainSeqs(): number[] {
	return mainEntries().map(({ seq }) => seq);
}

function conversation(fields: Partial<MentorConversation> = {}) {
	queryClient.setQueryData<MentorConversation>(conversationKey, {
		id: MAIN,
		latestSeq: 3,
		activeTurns: [],
		threads: [],
		...fields,
	});
}

const invalidated = (queryKey: readonly unknown[]) =>
	queryClient.getQueryState(queryKey)?.isInvalidated === true;

beforeEach(() => {
	queryClient = new QueryClient();
});

describe("applyEntry", () => {
	it("puts a message that arrives out of order where its seq belongs", () => {
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [entry(1), entry(3)] }));

		applyEntry(queryClient, SLUG, entry(2));

		expect(mainSeqs()).toStrictEqual([1, 2, 3]);
	});

	it("replaces a message it already holds instead of adding it twice", () => {
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [entry(1), entry(2)] }));

		applyEntry(queryClient, SLUG, entry(2, { text: "Edited in Slack" }));

		expect(mainEntries().map(({ text }) => text)).toStrictEqual(["Message 1", "Edited in Slack"]);
	});

	it("leaves a message older than every loaded page for the read that reaches it", () => {
		queryClient.setQueryData(
			entriesKey,
			pages<MentorEntryPage>({ entries: [entry(5), entry(6)], earlierBefore: 5 }),
		);

		applyEntry(queryClient, SLUG, entry(2));

		expect(mainSeqs()).toStrictEqual([5, 6]);
	});

	it("adds a new message to the newest page when older pages are loaded", () => {
		queryClient.setQueryData(
			entriesKey,
			pages<MentorEntryPage>(
				{ entries: [entry(3), entry(4)], earlierBefore: 3 },
				{ entries: [entry(1), entry(2)] },
			),
		);

		applyEntry(queryClient, SLUG, entry(5));
		applyEntry(queryClient, SLUG, entry(2, { text: "Edited in Slack" }));

		const loaded = queryClient.getQueryData<InfiniteData<MentorEntryPage>>(entriesKey)?.pages;
		expect(loaded?.map(({ entries }) => entries.map(({ seq }) => seq))).toStrictEqual([
			[3, 4, 5],
			[1, 2],
		]);
		expect(mainEntries().map(({ text }) => text)).toStrictEqual([
			"Message 1",
			"Edited in Slack",
			"Message 3",
			"Message 4",
			"Message 5",
		]);
	});

	it("puts a late message into the older page whose range holds it", () => {
		queryClient.setQueryData(
			entriesKey,
			pages<MentorEntryPage>(
				{ entries: [entry(5), entry(6)], earlierBefore: 5 },
				{ entries: [entry(1), entry(3)] },
			),
		);

		applyEntry(queryClient, SLUG, entry(4));

		const loaded = queryClient.getQueryData<InfiniteData<MentorEntryPage>>(entriesKey)?.pages;
		expect(loaded?.map(({ entries }) => entries.map(({ seq }) => seq))).toStrictEqual([
			[5, 6],
			[1, 3, 4],
		]);
	});

	it("removes Hephaestus's deleted message and keeps the developer's as a placeholder", () => {
		queryClient.setQueryData(
			entriesKey,
			pages<MentorEntryPage>({ entries: [entry(1, { kind: "USER" }), entry(2)] }),
		);

		applyEntry(queryClient, SLUG, entry(2, { deleted: true, text: "" }));
		applyEntry(queryClient, SLUG, entry(1, { kind: "USER", deleted: true, text: "" }));

		const shown = queryClient.getQueryData<InfiniteData<MentorEntryPage>>(entriesKey)?.pages[0];
		expect(shown?.entries.map(({ seq, deleted }) => ({ seq, deleted }))).toStrictEqual([
			{ seq: 1, deleted: true },
		]);
	});

	it("counts a reply once when the send's answer and the live event both bring it", () => {
		const parent = entry(1, { id: PARENT });
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [parent] }));
		queryClient.setQueryData(threadKey, pages<MentorThread>({ parent, entries: [] }));

		applyEntry(queryClient, SLUG, reply(1));
		applyEntry(queryClient, SLUG, reply(1));

		const summary =
			queryClient.getQueryData<InfiniteData<MentorEntryPage>>(entriesKey)?.pages[0]?.entries[0]
				?.thread;
		expect(summary).toMatchObject({ replyCount: 1, latestSeq: 1 });
		expect(
			queryClient.getQueryData<InfiniteData<MentorThread>>(threadKey)?.pages[0]?.entries,
		).toHaveLength(1);
	});
});

describe("patchShownEntry", () => {
	it("patches a top-level message in the main pages and above its open thread", () => {
		const parent = entry(1, { id: PARENT });
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [parent] }));
		queryClient.setQueryData(threadKey, pages<MentorThread>({ parent, entries: [reply(1)] }));

		patchShownEntry(queryClient, SLUG, parent, (shown) => ({ ...shown, vote: "HELPFUL" }));

		expect(mainEntries()[0]?.vote).toBe("HELPFUL");
		expect(
			queryClient.getQueryData<InfiniteData<MentorThread>>(threadKey)?.pages[0]?.parent.vote,
		).toBe("HELPFUL");
	});
});

describe("applySnapshot", () => {
	it("reads every loaded page again, though an edit while the stream was down moved no seq", async () => {
		const parent = entry(1, { id: PARENT });
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [parent, entry(2)] }));
		queryClient.setQueryData(threadKey, pages<MentorThread>({ parent, entries: [reply(1)] }));
		conversation({ latestSeq: 2 });

		await applySnapshot(queryClient, SLUG, {
			id: MAIN,
			latestSeq: 2,
			activeTurns: [],
			threads: [],
		});

		expect(invalidated(entriesKey)).toBe(true);
		expect(invalidated(threadKey)).toBe(true);
	});

	it("reads an open thread again and shows the snapshot's reply count at once", async () => {
		const parent = entry(1, { id: PARENT });
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [parent] }));
		queryClient.setQueryData(threadKey, pages<MentorThread>({ parent, entries: [reply(1)] }));
		const summary = {
			conversationId: "c0000000-0000-4000-8000-000000000001",
			parentEntryId: PARENT,
			replyCount: 2,
			latestSeq: 2,
		};

		await applySnapshot(queryClient, SLUG, {
			id: MAIN,
			latestSeq: 1,
			activeTurns: [],
			threads: [summary],
		});

		expect(invalidated(threadKey)).toBe(true);
		expect(
			queryClient.getQueryData<InfiniteData<MentorEntryPage>>(entriesKey)?.pages[0]?.entries[0]
				?.thread,
		).toStrictEqual(summary);
		expect(
			queryClient.getQueryData<InfiniteData<MentorThread>>(threadKey)?.pages[0]?.parent.thread,
		).toStrictEqual(summary);
	});
});

describe("applyTurn", () => {
	beforeEach(() => {
		queryClient.setQueryData(
			entriesKey,
			pages<MentorEntryPage>({
				entries: [entry(1, { kind: "USER", turnId: "a0000000-0000-4000-8000-000000000001" })],
			}),
		);
	});

	it("marks a failed turn's question failed and takes the turn off the running ones", () => {
		conversation({ activeTurns: [turn("RUNNING")] });

		applyTurn(queryClient, SLUG, turn("FAILED", { error: "Hephaestus took too long to answer." }));

		expect(
			queryClient.getQueryData<MentorConversation>(conversationKey)?.activeTurns,
		).toStrictEqual([]);
		const question =
			queryClient.getQueryData<InfiniteData<MentorEntryPage>>(entriesKey)?.pages[0]?.entries[0];
		expect(question).toMatchObject({
			turnStatus: "FAILED",
			turnError: "Hephaestus took too long to answer.",
		});
	});

	it("moves a queued turn to running", () => {
		conversation({ activeTurns: [turn("QUEUED")] });

		applyTurn(queryClient, SLUG, turn("RUNNING"));

		expect(
			queryClient
				.getQueryData<MentorConversation>(conversationKey)
				?.activeTurns.map(({ status }) => status),
		).toStrictEqual(["RUNNING"]);
	});

	it("never moves an ended turn back when the send's answer arrives after the stream reported the end", () => {
		const question = entry(1, { kind: "USER", turnId: turn("QUEUED").id, turnStatus: "QUEUED" });
		conversation({ activeTurns: [] });

		applyTurn(queryClient, SLUG, turn("FAILED", { error: "Hephaestus took too long to answer." }));
		applySubmission(queryClient, SLUG, { question, turn: turn("QUEUED") });

		expect(
			queryClient.getQueryData<MentorConversation>(conversationKey)?.activeTurns,
		).toStrictEqual([]);
		expect(mainEntries()[0]).toMatchObject({
			turnStatus: "FAILED",
			turnError: "Hephaestus took too long to answer.",
		});
	});

	it("follows a lost lease: running, queued again for its retry, then running the retry", () => {
		conversation({ activeTurns: [] });
		const statuses = () =>
			queryClient
				.getQueryData<MentorConversation>(conversationKey)
				?.activeTurns.map(({ status, attempt }) => `${status} ${attempt}`);

		applyTurn(queryClient, SLUG, turn("RUNNING", { attempt: 1 }));
		applyTurn(queryClient, SLUG, turn("QUEUED", { attempt: 1 }));
		expect(statuses()).toStrictEqual(["QUEUED 1"]);
		expect(mainEntries()[0]?.turnStatus).toBe("QUEUED");

		applyTurn(queryClient, SLUG, turn("RUNNING", { attempt: 2 }));
		expect(statuses()).toStrictEqual(["RUNNING 2"]);
	});

	it("drops a send's answer that arrives after the turn already started", () => {
		conversation({ activeTurns: [] });

		applyTurn(queryClient, SLUG, turn("RUNNING", { attempt: 1 }));
		applyTurn(queryClient, SLUG, turn("QUEUED", { attempt: 0 }));

		expect(
			queryClient
				.getQueryData<MentorConversation>(conversationKey)
				?.activeTurns.map(({ status }) => status),
		).toStrictEqual(["RUNNING"]);
		expect(mainEntries()[0]?.turnStatus).toBe("RUNNING");
	});

	it("drops a send's answer that arrives after a snapshot showed the turn running", async () => {
		const turnId = turn("QUEUED").id;
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [] }));
		conversation({ activeTurns: [] });
		await applySnapshot(queryClient, SLUG, {
			id: MAIN,
			latestSeq: 1,
			activeTurns: [turn("RUNNING", { attempt: 1 })],
			threads: [],
		});

		applySubmission(queryClient, SLUG, {
			question: entry(1, { kind: "USER", turnId, turnStatus: "QUEUED" }),
			turn: turn("QUEUED", { attempt: 0 }),
		});

		expect(
			queryClient
				.getQueryData<MentorConversation>(conversationKey)
				?.activeTurns.map(({ status, attempt }) => `${status} ${attempt}`),
		).toStrictEqual(["RUNNING 1"]);
		expect(mainEntries().map(({ seq, turnStatus }) => ({ seq, turnStatus }))).toStrictEqual([
			{ seq: 1, turnStatus: "RUNNING" },
		]);
	});

	it("applies a send's answer that is current: the question and its queued turn", () => {
		const turnId = turn("QUEUED").id;
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [] }));
		conversation({ activeTurns: [] });

		applySubmission(queryClient, SLUG, {
			question: entry(1, { kind: "USER", turnId, turnStatus: "QUEUED" }),
			turn: turn("QUEUED", { attempt: 0 }),
		});

		expect(
			queryClient
				.getQueryData<MentorConversation>(conversationKey)
				?.activeTurns.map(({ status }) => status),
		).toStrictEqual(["QUEUED"]);
		expect(mainEntries()[0]?.turnStatus).toBe("QUEUED");
	});

	it("keeps the end a reread showed when a late copy of the question arrives", () => {
		const turnId = turn("QUEUED").id;
		queryClient.setQueryData(
			entriesKey,
			pages<MentorEntryPage>({
				entries: [entry(1, { kind: "USER", turnId, turnStatus: "STOPPED" })],
			}),
		);
		conversation({ activeTurns: [] });

		applyEntry(queryClient, SLUG, entry(1, { kind: "USER", turnId, turnStatus: "QUEUED" }));
		applyTurn(queryClient, SLUG, turn("QUEUED"));

		expect(mainEntries()[0]?.turnStatus).toBe("STOPPED");
		expect(
			queryClient.getQueryData<MentorConversation>(conversationKey)?.activeTurns,
		).toStrictEqual([]);
	});
});

/**
 * Observes `options` as a mounted component would and refetches it as returning to the tab does.
 * Resolves once that refetch is in flight; `landed` once its response is in the cache.
 */
async function refetchOnFocus(
	observe: (client: QueryClient) => {
		subscribe: (listener: () => void) => () => void;
		getCurrentResult: () => { isSuccess: boolean; isRefetching: boolean; isFetching: boolean };
	},
) {
	queryClient.mount();
	onTestFinished(() => queryClient.unmount());
	const observer = observe(queryClient);
	onTestFinished(observer.subscribe(() => undefined));
	await vi.waitFor(() => expect(observer.getCurrentResult().isSuccess).toBe(true));
	focusManager.setFocused(false);
	focusManager.setFocused(true);
	onTestFinished(() => focusManager.setFocused(undefined));
	await vi.waitFor(() => expect(observer.getCurrentResult().isRefetching).toBe(true));
	return {
		landed: async () =>
			vi.waitFor(() => expect(observer.getCurrentResult().isFetching).toBe(false)),
	};
}

describe("a late answer to the developer's own write", () => {
	const turnId = turn("QUEUED").id;
	const deleted = entry(1, { kind: "USER", turnId, deleted: true, text: "" });
	const sent = entry(1, { kind: "USER", turnId, text: "Secret I deleted" });

	it("keeps a question deleted when the send's answer arrives after the deletion", () => {
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [deleted] }));
		conversation({ activeTurns: [] });

		applySubmission(queryClient, SLUG, { question: sent, turn: turn("QUEUED") });

		expect(mainEntries()).toMatchObject([{ deleted: true, text: "" }]);
	});

	it("keeps a deleted question above its open thread when the send's answer arrives late", () => {
		const parent = { ...deleted, id: PARENT };
		queryClient.setQueryData(threadKey, pages<MentorThread>({ parent, entries: [reply(1)] }));

		applyEntry(queryClient, SLUG, { ...sent, id: PARENT });

		const thread = queryClient.getQueryData<InfiniteData<MentorThread>>(threadKey);
		expect(thread?.pages[0]?.parent).toMatchObject({ deleted: true, text: "" });
	});

	it("takes only the vote from a vote's answer, so words edited since stay", () => {
		queryClient.setQueryData(
			entriesKey,
			pages<MentorEntryPage>({ entries: [entry(1, { text: "Edited in Slack since" })] }),
		);

		applyVote(queryClient, SLUG, entry(1, { text: "Written before the edit", vote: "HELPFUL" }));

		expect(mainEntries()).toMatchObject([{ text: "Edited in Slack since", vote: "HELPFUL" }]);
	});

	it("never shows again an answer of Hephaestus's that was deleted before the vote's answer arrived", () => {
		queryClient.setQueryData(entriesKey, pages<MentorEntryPage>({ entries: [entry(1)] }));

		applyVote(queryClient, SLUG, entry(2, { vote: "NOT_HELPFUL" }));

		expect(mainSeqs()).toStrictEqual([1]);
	});
});

const readThreadWithReply = () =>
	HttpResponse.json({ parent: entry(1, { id: PARENT }), entries: [reply(1)] });

describe("a read in flight", () => {
	const turnId = turn("RUNNING").id;

	it("keeps Hephaestus's answer that arrives live while a refetch on focus of the conversation is in flight", async () => {
		const question = entry(1, { kind: "USER", turnId, turnStatus: "RUNNING" });
		const response = deferred();
		// Both reads are from before Hephaestus answered: the question still runs, and no answer is stored.
		const read = () => HttpResponse.json({ entries: [question] });
		server.use(
			http.get("*/workspaces/acme/mentor/entries", async () => {
				await response.promise;
				return read();
			}),
		);
		server.use(http.get("*/workspaces/acme/mentor/entries", read, { once: true }));
		const refetch = await refetchOnFocus(
			(client) => new InfiniteQueryObserver(client, { ...hephEntriesOptions(SLUG), staleTime: 0 }),
		);

		inOrder(queryClient, SLUG, () =>
			applyEntry(queryClient, SLUG, entry(2, { kind: "HEPH", turnId })),
		);
		response.resolve();
		await refetch.landed();

		// The answer is written again over the older response, as soon as it lands.
		await vi.waitFor(() => expect(mainSeqs()).toStrictEqual([1, 2]));
	});

	it("keeps a reply that arrives live while a refetch on focus of the thread is in flight", async () => {
		const response = deferred();
		server.use(
			http.get("*/workspaces/acme/mentor/entries/:entryId/thread", async () => {
				await response.promise;
				return readThreadWithReply();
			}),
		);
		server.use(
			http.get("*/workspaces/acme/mentor/entries/:entryId/thread", readThreadWithReply, {
				once: true,
			}),
		);
		const refetch = await refetchOnFocus(
			(client) =>
				new InfiniteQueryObserver(client, { ...hephThreadOptions(SLUG, PARENT), staleTime: 0 }),
		);

		inOrder(queryClient, SLUG, () => applyEntry(queryClient, SLUG, reply(2)));
		response.resolve();
		await refetch.landed();

		await vi.waitFor(() => expect(threadSeqs()).toStrictEqual([1, 2]));
	});
});
