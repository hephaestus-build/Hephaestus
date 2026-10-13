import { describe, expect, it } from "vitest";

import type { MentorEntry, MentorTurn } from "@/api/types.gen";

import { dayLabel, timelineRows } from "./timeline-rows";

const DAY = "2026-10-08";

function entry(id: string, fields: Partial<MentorEntry> & Pick<MentorEntry, "kind">): MentorEntry {
	return {
		id,
		conversationId: "main",
		seq: Number(id.replaceAll(/\D/gu, "")),
		surface: "APP",
		text: id,
		createdAt: new Date(`${DAY}T09:00:00`),
		deleted: false,
		observations: [],
		...fields,
	};
}

function turn(id: string, status: MentorTurn["status"], requestId = `request-${id}`): MentorTurn {
	return {
		id,
		conversationId: "main",
		requestId,
		status,
		attempt: status === "QUEUED" ? 0 : 1,
		surface: "APP",
		createdAt: new Date(`${DAY}T09:00:00`),
	};
}

/** Each row as a line, with the day it opens as a line of its own above it. */
const shape = (rows: ReturnType<typeof timelineRows>) =>
	rows.flatMap((row) => {
		const day = row.kind !== "live" && row.day ? [`day ${row.day.toDateString()}`] : [];
		switch (row.kind) {
			case "entry": {
				const line = [
					row.entry.id,
					row.grouped ? "grouped" : "",
					row.queued ? `queued ${row.queued.turn.id}${row.queued.next ? " next" : ""}` : "",
					row.outcome
						? `${row.outcome.status}${row.outcome.error === undefined ? "" : ` ${row.outcome.error}`}`
						: "",
				]
					.filter(Boolean)
					.join(" ");
				return [...day, line];
			}
			case "pending": {
				return [...day, `pending ${row.message.requestId}`];
			}
			case "live": {
				return [`live ${row.turn.id}`];
			}
			default: {
				throw new Error("Unknown row");
			}
		}
	});

const keys = (input: Parameters<typeof timelineRows>[0]) =>
	timelineRows(input).map(({ key, messageId }) => ({ key, messageId }));

describe("timelineRows", () => {
	it("opens each local day with a separator and groups one author's messages within five minutes", () => {
		const rows = timelineRows({
			entries: [
				entry("e1", { kind: "USER", createdAt: new Date(`${DAY}T09:00:00`) }),
				entry("e2", { kind: "USER", createdAt: new Date(`${DAY}T09:04:00`) }),
				entry("e3", { kind: "USER", createdAt: new Date(`${DAY}T09:04:30`), surface: "SLACK" }),
				entry("e4", { kind: "HEPH", createdAt: new Date(`${DAY}T09:05:00`) }),
				entry("e5", { kind: "HEPH", createdAt: new Date(`${DAY}T09:11:00`) }),
				entry("e6", { kind: "HEPH", createdAt: new Date("2026-10-09T09:12:00") }),
			],
			pending: [],
			turns: [],
		});
		expect(shape(rows)).toStrictEqual([
			"day Thu Oct 08 2026",
			"e1",
			"e2 grouped",
			"e3",
			"e4",
			"e5",
			"day Fri Oct 09 2026",
			"e6",
		]);
	});

	it("never groups a weekly digest with the message before it", () => {
		const rows = timelineRows({
			entries: [entry("e1", { kind: "HEPH" }), entry("e2", { kind: "DIGEST", surface: "SYSTEM" })],
			pending: [],
			turns: [],
		});
		expect(shape(rows)).toStrictEqual(["day Thu Oct 08 2026", "e1", "e2"]);
	});

	it("queues a follow-up under its question while another turn runs, and shows the running one last", () => {
		const rows = timelineRows({
			entries: [
				entry("e1", { kind: "USER", turnId: "t1" }),
				entry("e2", { kind: "USER", turnId: "t2" }),
			],
			pending: [
				{
					requestId: "r3",
					text: "unsent",
					createdAt: new Date(`${DAY}T09:01:00`),
					status: "sending",
				},
			],
			turns: [turn("t1", "RUNNING"), turn("t2", "QUEUED")],
		});
		expect(shape(rows)).toStrictEqual([
			"day Thu Oct 08 2026",
			"e1",
			"e2 grouped queued t2 next",
			"pending r3",
			"live t1",
		]);
	});

	it("marks only the first waiting follow-up as answered next", () => {
		const rows = timelineRows({
			entries: [
				entry("e1", { kind: "USER", turnId: "t1" }),
				entry("e2", { kind: "USER", turnId: "t2" }),
				entry("e3", { kind: "USER", turnId: "t3" }),
			],
			pending: [],
			turns: [turn("t1", "RUNNING"), turn("t2", "QUEUED"), turn("t3", "QUEUED")],
		});
		expect(shape(rows)).toStrictEqual([
			"day Thu Oct 08 2026",
			"e1",
			"e2 grouped queued t2 next",
			"e3 grouped queued t3",
			"live t1",
		]);
	});

	it("shows a queued turn as starting when nothing else runs", () => {
		const rows = timelineRows({
			entries: [entry("e1", { kind: "USER", requestId: "r1" })],
			pending: [],
			turns: [turn("t1", "QUEUED", "r1")],
		});
		expect(shape(rows)).toStrictEqual(["day Thu Oct 08 2026", "e1", "live t1"]);
	});

	it("keeps one key per message from sent to stored, and from Heph's live answer to the stored one", () => {
		const sending = {
			requestId: "r2",
			text: "And my latest one?",
			createdAt: new Date(`${DAY}T09:01:00`),
			status: "sending",
		} as const;
		const before = keys({
			entries: [entry("e1", { kind: "USER", requestId: "r1", turnId: "t1" })],
			pending: [sending],
			turns: [turn("t1", "RUNNING", "r1")],
		});
		// The answer arrives before the turn's end: the stored answer stands in for the live row.
		const after = keys({
			entries: [
				entry("e1", { kind: "USER", requestId: "r1", turnId: "t1" }),
				entry("e2", { kind: "USER", requestId: "r2" }),
				entry("e3", { kind: "HEPH", turnId: "t1" }),
				entry("e4", { kind: "HEPH", turnId: "t1" }),
			],
			pending: [],
			turns: [turn("t1", "RUNNING", "r1")],
		});

		expect(before).toStrictEqual([
			{ key: "request:r1", messageId: "e1" },
			{ key: "request:r2", messageId: "request:r2" },
			{ key: "turn:t1", messageId: "turn:t1" },
		]);
		expect(after).toStrictEqual([
			{ key: "request:r1", messageId: "e1" },
			{ key: "request:r2", messageId: "e2" },
			{ key: "turn:t1", messageId: "e3" },
			{ key: "e4", messageId: "e4" },
		]);
	});

	it("puts how a turn ended, as its question carries it, under the turn's last message", () => {
		const rows = timelineRows({
			entries: [
				entry("e1", { kind: "USER", turnId: "t1", turnStatus: "STOPPED" }),
				entry("e2", { kind: "HEPH", turnId: "t1" }),
				entry("e3", { kind: "USER", turnId: "t2", turnStatus: "FAILED", turnError: "Too long." }),
				entry("e4", { kind: "USER", turnId: "t3", turnStatus: "DONE" }),
			],
			pending: [],
			turns: [],
		});
		expect(shape(rows)).toStrictEqual([
			"day Thu Oct 08 2026",
			"e1",
			"e2 STOPPED",
			"e3 FAILED Too long.",
			"e4 grouped",
		]);
	});
});

describe("dayLabel", () => {
	const now = new Date("2026-10-09T15:00:00");

	it.each([
		["2026-10-09T08:00:00", "Today"],
		["2026-10-08T23:59:00", "Yesterday"],
		["2026-10-05T10:00:00", "Monday, 5 October"],
		["2025-12-31T10:00:00", "Wednesday, 31 December 2025"],
	])("names %s as %s", (date, label) => {
		expect(dayLabel(new Date(date), now)).toBe(label);
	});
});
