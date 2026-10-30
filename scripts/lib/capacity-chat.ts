import { randomUUID } from "node:crypto";
import { monitorEventLoopDelay } from "node:perf_hooks";
import { setTimeout as sleep } from "node:timers/promises";

import { CapacityEvents } from "./capacity-events.ts";
import { stopSyntheticTurns } from "./capacity-stop.ts";
import { asArray, asRecord, isRecord, parseJson } from "./json.ts";
import { median, percentile } from "./statistics.ts";

interface Turn {
	requestId: string;
	user: number;
	sent: number;
	id?: string;
	questionId?: string;
	parent: string | null;
	running?: number;
	firstEvent?: number;
	firstText?: number;
	ended?: number;
	status?: string;
	needsResync?: boolean;
	completion: ReturnType<typeof Promise.withResolvers<string>>;
}

function trend(values: number[]) {
	return {
		type: "trend",
		values: {
			med: median(values),
			"p(95)": percentile(values, 0.95),
			"p(99)": percentile(values, 0.99),
			max: values.length > 0 ? Math.max(...values) : null,
			count: values.length,
		},
	};
}

/** Finite open cohort: initial arrivals ignore completions; later turns follow each person's conversation. */
export async function runCapacityChat(options: {
	base: string;
	tokens: readonly string[];
	signal: AbortSignal;
	onEvent: (row: Record<string, unknown>) => void;
	thinkMs?: number;
	turnTimeoutMs?: number;
	readyTimeoutMs?: number;
	runLabel?: string;
}) {
	const { base, tokens, signal, onEvent } = options;
	const abort = new AbortController();
	const log = (row: Record<string, unknown>) => {
		onEvent({ at: Date.now(), ...row });
	};
	const headers = (user: number) => ({
		authorization: `Bearer ${tokens[user] ?? ""}`,
		"content-type": "application/json",
	});
	const ready = tokens.map(() => Promise.withResolvers<boolean>());
	const pending = new Map<string, Turn>();
	const measurements: Turn[] = [];
	const arrivals: number[] = [];
	const failures: string[] = [];
	let resyncReads = 0;
	const eventLoop = monitorEventLoopDelay({ resolution: 10 });
	eventLoop.enable();
	function stop() {
		abort.abort();
		for (const entry of ready) {
			entry.resolve(false);
		}
		for (const turn of pending.values()) {
			turn.completion.resolve("CANCELLED");
		}
	}
	signal.addEventListener("abort", stop, { once: true });
	if (signal.aborted) {
		stop();
	}
	function observe(turn: Turn, row: Record<string, unknown>) {
		if (turn.firstEvent === undefined) {
			turn.firstEvent = Date.now();
			log({ phase: "first_turn_event", requestId: turn.requestId });
		}
		if (row.status === "RUNNING" && turn.running === undefined) {
			turn.running = Date.now();
			log({ phase: "running", requestId: turn.requestId });
		}
		if (
			isRecord(row.partial) &&
			typeof row.partial.text === "string" &&
			row.partial.text !== "" &&
			turn.firstText === undefined
		) {
			turn.firstText = Date.now();
			log({ phase: "first_text", requestId: turn.requestId });
		}
		if (
			typeof row.status === "string" &&
			["DONE", "FAILED", "STOPPED"].includes(row.status) &&
			turn.ended === undefined
		) {
			turn.ended = Date.now();
			turn.status = row.status;
			log({ phase: "turn_end", requestId: turn.requestId, status: row.status });
			turn.completion.resolve(row.status);
		}
	}
	async function resync(turn: Turn) {
		if (turn.questionId === undefined) {
			turn.needsResync = true;
			return;
		}
		const route =
			turn.parent === null ? "/entries?limit=20" : `/entries/${turn.parent}/thread?limit=20`;
		const response = await fetch(`${base}${route}`, {
			headers: headers(turn.user),
			signal: abort.signal,
		});
		resyncReads += 1;
		if (!response.ok) {
			await response.arrayBuffer();
			throw new Error(`RESYNC_HTTP_${response.status}`);
		}
		const page = asRecord(parseJson(await response.text()), "entry page");
		const entry = asArray(page.entries, "entries")
			.map((row) => asRecord(row, "entry"))
			.find((row) => row.id === turn.questionId);
		log({ phase: "resync", requestId: turn.requestId, status: entry?.turnStatus ?? null });
		if (entry !== undefined) {
			observe(turn, { status: entry.turnStatus });
		}
		turn.needsResync = false;
	}
	async function receive(event: string, raw: string, user: number) {
		if (event === "turn") {
			const row = asRecord(parseJson(raw), "turn event");
			const turn = typeof row.requestId === "string" ? pending.get(row.requestId) : undefined;
			if (turn !== undefined && turn.user === user) {
				observe(turn, row);
			}
			return;
		}
		if (event !== "snapshot") {
			return;
		}
		const snapshot = asRecord(parseJson(raw), "snapshot");
		const active = asArray(snapshot.activeTurns, "active turns").map((row) =>
			asRecord(row, "turn"),
		);
		for (const turn of pending.values()) {
			if (turn.user !== user) {
				continue;
			}
			const row = active.find((candidate) => candidate.requestId === turn.requestId);
			if (row === undefined) {
				await resync(turn);
			} else {
				observe(turn, row);
			}
		}
		ready[user]?.resolve(true);
	}
	const subscriptions = tokens.map(async (_, user) => {
		try {
			const response = await fetch(`${base}/events`, {
				headers: headers(user),
				signal: abort.signal,
			});
			if (!response.ok || response.body === null) {
				await response.arrayBuffer();
				throw new Error(`SSE_HTTP_${response.status}`);
			}
			const parser = new CapacityEvents();
			let firstBody = true;
			for await (const chunk of response.body) {
				if (firstBody) {
					log({ phase: "subscription_first_body_byte", user });
					firstBody = false;
				}
				const frames: [string, string][] = [];
				parser.push(chunk, (event, raw) => {
					frames.push([event, raw]);
				});
				for (const [event, raw] of frames) {
					await receive(event, raw, user);
				}
			}
			if (!abort.signal.aborted) {
				throw new Error("SSE_EARLY_END");
			}
		} catch (error) {
			if (!abort.signal.aborted) {
				failures.push(error instanceof Error ? error.message : "SSE_FAILURE");
				stop();
			}
		}
	});
	const readyTimer = setTimeout(() => {
		failures.push("SSE_READY_TIMEOUT");
		stop();
	}, options.readyTimeoutMs ?? 30_000);
	const readyResults = await Promise.all(ready.map(async (entry) => entry.promise));
	clearTimeout(readyTimer);
	const start = Date.now();
	let completedSessions = 0;
	const sessions =
		readyResults.every(Boolean) && !abort.signal.aborted
			? tokens.map(async (_, user) => {
					try {
						const planned = start + (user * 1001) / tokens.length;
						await sleep(Math.max(0, planned - Date.now()), undefined, { signal: abort.signal });
						arrivals.push(Date.now() - planned);
						log({ phase: "session_arrival", user, plannedAt: planned });
						let parent: string | null = null;
						for (let index = 0; index < 3; index += 1) {
							const turn: Turn = {
								requestId: randomUUID(),
								user,
								sent: Date.now(),
								parent,
								completion: Promise.withResolvers<string>(),
							};
							pending.set(turn.requestId, turn);
							measurements.push(turn);
							log({ phase: "turn_sent", requestId: turn.requestId, user, index });
							const timer = setTimeout(() => {
								turn.completion.resolve("TIMEOUT");
							}, options.turnTimeoutMs ?? 600_000);
							try {
								const response = await fetch(`${base}/turns`, {
									method: "POST",
									headers: headers(user),
									signal: AbortSignal.any([
										abort.signal,
										AbortSignal.timeout(options.turnTimeoutMs ?? 600_000),
									]),
									body: JSON.stringify({
										requestId: turn.requestId,
										text: `capacity-bench:${turn.requestId} capacity-replay:${options.runLabel ?? "test"}:${user}:${index} Describe one practice in this synthetic course.`,
										...(parent === null ? {} : { parentEntryId: parent }),
									}),
								});
								if (!response.ok) {
									await response.arrayBuffer();
									turn.status = `HTTP_${response.status}`;
									throw new Error(turn.status);
								}
								const body = asRecord(parseJson(await response.text()), "submission");
								const { id } = asRecord(body.turn, "turn");
								const questionId = asRecord(body.question, "question").id;
								if (typeof id !== "string" || typeof questionId !== "string") {
									throw new Error("MISSING_TURN_ID");
								}
								turn.id = id;
								turn.questionId = questionId;
								parent ??= questionId;
								if (turn.needsResync === true) {
									await resync(turn);
								}
								const status = await turn.completion.promise;
								turn.status = status;
								if (status !== "DONE") {
									throw new Error(status);
								}
							} finally {
								clearTimeout(timer);
								pending.delete(turn.requestId);
							}
							if (index < 2) {
								await sleep(options.thinkMs ?? 30_000, undefined, { signal: abort.signal });
							}
						}
						completedSessions += 1;
					} catch (error) {
						let reason = "SESSION_FAILURE";
						if (abort.signal.aborted) {
							reason = "CANCELLED";
						} else if (error instanceof Error) {
							reason = error.message;
						}
						failures.push(reason);
					}
				})
			: [];
	await Promise.all(sessions);
	const finish = Date.now();
	stop();
	await Promise.all(subscriptions);
	signal.removeEventListener("abort", stop);
	eventLoop.disable();
	// Query every synthetic owner, including a POST accepted before its response was interrupted.
	const cleanup = await stopSyntheticTurns(base, tokens);
	const done = measurements.filter((row) => row.status === "DONE");
	const firstText = done.flatMap((row) =>
		row.firstText === undefined ? [] : [row.firstText - row.sent],
	);
	const outcomes = Object.fromEntries(
		[...new Set(measurements.map((row) => row.status ?? "UNFINISHED"))].map((status) => [
			status,
			measurements.filter((row) => (row.status ?? "UNFINISHED") === status).length,
		]),
	);
	return {
		driver: "typed-typescript-sse",
		quantile: "nearest rank; successful DONE turns only",
		startedAt: new Date(start).toISOString(),
		finishedAt: new Date(finish).toISOString(),
		qualified:
			done.length === tokens.length * 3 &&
			firstText.length === done.length &&
			failures.length === 0 &&
			cleanup.failures === 0 &&
			cleanup.remaining === 0,
		completedSessions,
		resyncReads,
		done: done.length,
		expectedTurns: tokens.length * 3,
		outcomes,
		failures,
		cleanup,
		arrivals: { expected: tokens.length, dispatched: arrivals.length, latenessMs: trend(arrivals) },
		eventLoopDelayMs: { p95: eventLoop.percentile(95) / 1e6, max: eventLoop.max / 1e6 },
		censored: {
			doneWithoutRunning: done.filter((row) => row.running === undefined).length,
			unfinished: measurements.filter((row) => row.ended === undefined).length,
			doneWithoutFirstText: done.length - firstText.length,
		},
		metrics: {
			heph_first_text: trend(firstText),
			heph_queue_wait: trend(
				done.flatMap((row) => (row.running === undefined ? [] : [row.running - row.sent])),
			),
			heph_turn_duration: trend(
				done.flatMap((row) => (row.ended === undefined ? [] : [row.ended - row.sent])),
			),
			heph_turns_finished: { values: { count: done.length } },
			course_sessions_finished: { values: { count: completedSessions } },
			heph_turn_refused: {
				values: { count: measurements.filter((row) => row.status === "HTTP_409").length },
			},
		},
		measurements: measurements.map((row) => ({
			requestId: row.requestId,
			user: row.user,
			sent: row.sent,
			running: row.running,
			firstEvent: row.firstEvent,
			firstText: row.firstText,
			ended: row.ended,
			status: row.status ?? "UNFINISHED",
		})),
	};
}
