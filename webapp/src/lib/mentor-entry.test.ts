import { assert, describe, expect, it } from "vitest";

import { parseLiveEvent, turnRequestOf } from "./mentor-entry";

const E1 = "e0000000-0000-4000-8000-000000000001";
const C1 = "c0000000-0000-4000-8000-000000000001";
const C2 = "c0000000-0000-4000-8000-000000000002";
const O1 = "d0000000-0000-4000-8000-000000000001";
const F1 = "f0000000-0000-4000-8000-000000000001";
const T1 = "a0000000-0000-4000-8000-000000000001";
const R1 = "b0000000-0000-4000-8000-000000000001";

const ENTRY = {
	id: E1,
	conversationId: C1,
	seq: 4,
	kind: "HEPH",
	surface: "SLACK",
	text: "Answer",
	createdAt: "2026-10-08T09:12:00.123456Z",
	deleted: false,
	observations: [{ observationId: O1, text: "Feedback" }],
	thread: {
		conversationId: C2,
		parentEntryId: E1,
		replyCount: 2,
		lastReplyAt: "2026-10-08T10:00:00Z",
		latestSeq: 2,
	},
};

describe("parseLiveEvent", () => {
	it("reads an entry with its timestamps as dates", () => {
		const event = parseLiveEvent("entry", JSON.stringify(ENTRY));
		assert(event?.type === "entry");
		expect(event.entry.createdAt).toStrictEqual(new Date("2026-10-08T09:12:00.123Z"));
		expect(event.entry.thread?.lastReplyAt).toStrictEqual(new Date("2026-10-08T10:00:00Z"));
		expect(event.entry.surface).toBe("SLACK");
	});

	it("reads a weekly digest with its timestamps as dates", () => {
		const event = parseLiveEvent(
			"entry",
			JSON.stringify({
				...ENTRY,
				kind: "DIGEST",
				surface: "SYSTEM",
				observations: [],
				digest: {
					weekStart: "2026-10-05",
					windowStart: "2026-10-04T22:00:00Z",
					windowEnd: "2026-10-11T22:00:00Z",
					timeZone: "Europe/Berlin",
					feedback: [
						{
							feedbackId: F1,
							practiceSlug: "explain-decisions",
							practiceName: "Explain decisions",
							channel: "IN_APP",
							deliveredAt: "2026-10-06T09:00:00Z",
							withdrawn: false,
							replaced: false,
						},
					],
					changes: [],
					waiting: 1,
				},
			}),
		);
		assert(event?.type === "entry");
		expect(event.entry.digest?.feedback[0]?.deliveredAt).toStrictEqual(
			new Date("2026-10-06T09:00:00Z"),
		);
		expect(event.entry.digest?.waiting).toBe(1);
	});

	it("reads a running turn with the answer so far", () => {
		const event = parseLiveEvent(
			"turn",
			JSON.stringify({
				id: T1,
				conversationId: C1,
				requestId: R1,
				status: "RUNNING",
				attempt: 1,
				surface: "APP",
				createdAt: "2026-10-08T09:12:00Z",
				partial: {
					activity: "TOOL",
					tool: "fetch_context",
					stage: "Reading your practice feedback…",
					text: "",
				},
			}),
		);
		expect(event).toMatchObject({
			type: "turn",
			turn: { status: "RUNNING", partial: { tool: "fetch_context" } },
		});
	});

	it("drops what it cannot read rather than guessing", () => {
		expect(parseLiveEvent("entry", "not json")).toBeUndefined();
		expect(
			parseLiveEvent("entry", JSON.stringify({ ...ENTRY, kind: "TOOL_RESULT" })),
		).toBeUndefined();
		expect(
			parseLiveEvent("entry", JSON.stringify({ ...ENTRY, createdAt: "yesterday" })),
		).toBeUndefined();
		expect(parseLiveEvent("ping", "{}")).toBeUndefined();
	});
});

describe("turnRequestOf", () => {
	it("reads the body of a submission from the mutation cache", () => {
		expect(
			turnRequestOf({ path: { workspaceSlug: "acme" }, body: { requestId: R1, text: "Hi" } }),
		).toStrictEqual({ requestId: R1, text: "Hi" });
		expect(turnRequestOf({ path: { workspaceSlug: "acme" } })).toBeUndefined();
	});
});
