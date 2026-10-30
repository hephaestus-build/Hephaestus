import { HttpResponse, http, type PathParams } from "msw";

import type {
	MentorConversation,
	MentorEntry,
	MentorEntryPage,
	MentorSubmission,
	MentorThread,
	MentorTurn,
	MentorTurnRequest,
	MentorVoteRequest,
} from "@/api/types.gen";
import type { Wire } from "@/lib/dates";

type WireEntry = Wire<MentorEntry>;
type WireTurn = Wire<MentorTurn>;

const BASE_TIME = Date.parse("2026-10-09T09:00:00Z");

/** The developer's conversation. Each thread has a conversation id of its own. */
const MAIN_CONVERSATION_ID = "c0000000-0000-4000-8000-000000000000";

function beforeOf(request: Request): number | undefined {
	const value = new URL(request.url).searchParams.get("before");
	return value === null ? undefined : Number(value);
}

/**
 * the conversation endpoints over an in-memory ledger, so a route test drives the real
 * requests and reads back what the app wrote. Every top-level message and every thread has its own
 * `seq`, as on the server.
 */
export class ConversationFake {
	readonly entries: WireEntry[] = [];
	readonly turns: WireTurn[] = [];
	/** Every `POST /mentor/turns` body, a repeated `requestId` included. */
	readonly submissions: MentorTurnRequest[] = [];
	readonly stops: string[] = [];
	readonly votes: { entryId: string; vote: MentorVoteRequest["vote"] | null }[] = [];
	readonly deletes: string[] = [];
	/** The read marker the server keeps for the developer, on every device. */
	lastReadAt: string | undefined;
	readonly pageSize: number;
	readonly workspaceSlug: string;
	private ids = 0;

	constructor(workspaceSlug = "acme", { pageSize = 50 } = {}) {
		this.workspaceSlug = workspaceSlug;
		this.pageSize = pageSize;
	}

	private nextId(prefix: string): string {
		this.ids += 1;
		return `${prefix}0000000-0000-4000-8000-${String(this.ids).padStart(12, "0")}`;
	}

	private scope(parentEntryId: string | undefined): WireEntry[] {
		return this.entries.filter((entry) => entry.parentEntryId === parentEntryId);
	}

	/** Stores one message, as Slack, Hephaestus or the app would have written it. */
	add(
		fields: Pick<WireEntry, "kind" | "text"> & Partial<Omit<WireEntry, "kind" | "text">>,
	): WireEntry {
		const scope = this.scope(fields.parentEntryId);
		const seq = (scope.at(-1)?.seq ?? 0) + 1;
		const parent = this.entries.find(({ id }) => id === fields.parentEntryId);
		const entry: WireEntry = {
			id: this.nextId("e"),
			conversationId:
				fields.parentEntryId === undefined
					? MAIN_CONVERSATION_ID
					: (parent?.thread?.conversationId ?? this.nextId("c")),
			seq,
			surface: "APP",
			createdAt: new Date(BASE_TIME + this.entries.length * 60_000).toISOString(),
			deleted: false,
			links: [],
			...fields,
		};
		this.entries.push(entry);
		const { parentEntryId } = entry;
		if (parentEntryId !== undefined && parent) {
			parent.thread = {
				conversationId: entry.conversationId,
				parentEntryId,
				replyCount: (parent.thread?.replyCount ?? 0) + 1,
				lastReplyAt: entry.createdAt,
				latestSeq: entry.seq,
			};
		}
		return entry;
	}

	addTurn(fields: Pick<WireTurn, "status"> & Partial<WireTurn>): WireTurn {
		const turn: WireTurn = {
			id: this.nextId("a"),
			conversationId: MAIN_CONVERSATION_ID,
			requestId: this.nextId("b"),
			surface: "APP",
			createdAt: new Date(BASE_TIME).toISOString(),
			attempt: fields.status === "QUEUED" ? 0 : 1,
			...fields,
		};
		this.turns.push(turn);
		return turn;
	}

	/** The newest Hephaestus answer still shown, in the conversation or any thread, as the server reports it. */
	private lastAnswerAt(): { lastAnswerAt?: string } {
		// ISO times in UTC sort as text.
		let newest: string | undefined;
		for (const { kind, deleted, createdAt } of this.entries) {
			if (kind === "HEPH" && !deleted && (newest === undefined || createdAt > newest)) {
				newest = createdAt;
			}
		}
		return newest === undefined ? {} : { lastAnswerAt: newest };
	}

	conversation(): Wire<MentorConversation> {
		return {
			id: MAIN_CONVERSATION_ID,
			latestSeq: this.scope(undefined).at(-1)?.seq ?? 0,
			activeTurns: this.turns.filter(({ status }) => status === "QUEUED" || status === "RUNNING"),
			threads: this.scope(undefined).flatMap(({ thread }) => (thread ? [thread] : [])),
			...this.lastAnswerAt(),
			...(this.lastReadAt === undefined ? {} : { lastReadAt: this.lastReadAt }),
		};
	}

	private page(scope: WireEntry[], before: number | undefined): Wire<MentorEntryPage> {
		const older = before === undefined ? scope : scope.filter(({ seq }) => seq < before);
		const entries = older.slice(-this.pageSize);
		const earlierBefore = older.length > entries.length ? entries[0]?.seq : undefined;
		return { entries, ...(earlierBefore === undefined ? {} : { earlierBefore }) };
	}

	handlers() {
		const base = `*/workspaces/${this.workspaceSlug}/mentor`;
		return [
			http.get(`${base}/conversation`, () => HttpResponse.json(this.conversation())),
			http.put<PathParams, { at: string }>(`${base}/conversation/read`, async ({ request }) => {
				const { at } = await request.json();
				// The server keeps the later time.
				if (this.lastReadAt === undefined || at > this.lastReadAt) {
					this.lastReadAt = at;
				}
				return HttpResponse.json({ lastReadAt: this.lastReadAt });
			}),
			http.get(`${base}/entries`, ({ request }) =>
				HttpResponse.json(this.page(this.scope(undefined), beforeOf(request))),
			),
			http.get<{ entryId: string }>(`${base}/entries/:entryId/thread`, ({ params, request }) => {
				const parent = this.entries.find(({ id }) => id === params.entryId);
				if (!parent || parent.parentEntryId !== undefined) {
					return HttpResponse.json({ status: 404 }, { status: 404 });
				}
				return HttpResponse.json({
					parent,
					...(parent.thread ? { conversationId: parent.thread.conversationId } : {}),
					...this.page(this.scope(parent.id), beforeOf(request)),
				} satisfies Wire<MentorThread>);
			}),
			http.post<PathParams, MentorTurnRequest>(`${base}/turns`, async ({ request }) => {
				const body = await request.json();
				this.submissions.push(body);
				const turn = this.addTurn({
					status: "QUEUED",
					requestId: body.requestId,
					...(body.parentEntryId === undefined ? {} : { parentEntryId: body.parentEntryId }),
				});
				const stored = this.add({
					kind: "USER",
					text: body.text,
					requestId: body.requestId,
					turnId: turn.id,
					...(body.parentEntryId === undefined ? {} : { parentEntryId: body.parentEntryId }),
				});
				return HttpResponse.json({ turn, question: stored } satisfies Wire<MentorSubmission>);
			}),
			http.patch<{ turnId: string }>(`${base}/turns/:turnId/status`, ({ params }) => {
				const turn = this.turns.find(({ id }) => id === params.turnId);
				if (!turn) {
					return HttpResponse.json({ status: 404 }, { status: 404 });
				}
				this.stops.push(turn.id);
				turn.status = "STOPPED";
				delete turn.partial;
				return HttpResponse.json(turn);
			}),
			http.put<{ entryId: string }, MentorVoteRequest>(
				`${base}/entries/:entryId/vote`,
				async ({ params, request }) => {
					const { vote } = await request.json();
					return this.vote(params.entryId, vote);
				},
			),
			http.delete<{ entryId: string }>(`${base}/entries/:entryId/vote`, ({ params }) =>
				this.vote(params.entryId, null),
			),
			http.delete<{ entryId: string }>(`${base}/entries/:entryId`, ({ params }) => {
				const entry = this.entries.find(({ id }) => id === params.entryId);
				if (entry) {
					this.deletes.push(entry.id);
					entry.deleted = true;
					entry.text = "";
				}
				return HttpResponse.json({ lastReadAt: this.lastReadAt });
			}),
		];
	}

	private vote(entryId: string, vote: MentorVoteRequest["vote"] | null) {
		const entry = this.entries.find(({ id }) => id === entryId);
		if (!entry) {
			return HttpResponse.json({ status: 404 }, { status: 404 });
		}
		this.votes.push({ entryId, vote });
		if (vote === null) {
			delete entry.vote;
		} else {
			entry.vote = vote;
		}
		return HttpResponse.json(entry);
	}
}
