import { setTimeout as sleep } from "node:timers/promises";

import { responseJson } from "./conversation-loop-http.ts";
import {
	asAssignment,
	isRecord,
	type Assignment,
	type StopReason,
} from "./conversation-loop-protocol.ts";

export interface AssignmentRunner {
	start: (turnId: string, attempt: number, token: string) => Promise<void>;
	stop: (turnId: string, attempt: number, reason: StopReason) => void;
}

export interface AssignmentGateway {
	pull: (signal: AbortSignal) => Promise<Assignment | null>;
	renew: (assignment: Assignment, signal: AbortSignal) => Promise<"RUNNING" | "STOPPED">;
}

/** The assignment credential authorizes pulls only. Attempt calls use only that attempt's token. */
export function assignmentGateway(proxyUrl: string, credential: () => string): AssignmentGateway {
	return {
		async pull(signal) {
			const response = await fetch(`${proxyUrl}/conversation/assignments`, {
				method: "POST",
				headers: { Authorization: `Bearer ${credential()}` },
				signal: AbortSignal.any([signal, AbortSignal.timeout(30_000)]),
				redirect: "error",
			});
			if (response.status === 204) {
				await response.body?.cancel();
				return null;
			}
			if (!response.ok) {
				await response.body?.cancel();
				throw new Error(`Conversation assignment pull failed with ${response.status}`);
			}
			return asAssignment(await responseJson(response, 8192));
		},
		async renew(assignment, signal) {
			const response = await fetch(`${proxyUrl}/turn/${assignment.turnId}/renew`, {
				method: "POST",
				headers: { Authorization: `Bearer ${assignment.token}` },
				signal: AbortSignal.any([signal, AbortSignal.timeout(10_000)]),
				redirect: "error",
			});
			if (!response.ok) {
				await response.body?.cancel();
				throw new Error("Conversation attempt renewal failed");
			}
			const value = await responseJson(response, 8192);
			if (!isRecord(value) || (value.status !== "RUNNING" && value.status !== "STOPPED")) {
				throw new Error("The gateway sent malformed conversation control");
			}
			return value.status;
		},
	};
}

/**
 * Pulls until shutdown, then waits for this loop's turns. It pulls only while fewer than `maxTurns` of its turns run.
 * No listener or inbound port exists.
 */
export async function runAssignments(
	gateway: AssignmentGateway,
	runner: AssignmentRunner,
	signal: AbortSignal,
	onFailure: (error: unknown) => void,
	maxTurns: number,
	idleMs = 250,
): Promise<void> {
	const active = new Set<Promise<void>>();
	async function run(assignment: Assignment) {
		const ended = new AbortController();
		const deadline = setTimeout(
			() => runner.stop(assignment.turnId, assignment.attempt, "deadline"),
			Math.max(0, Date.parse(assignment.deadline) - Date.now()),
		);
		const renewals = (async () => {
			while (!ended.signal.aborted) {
				try {
					await sleep(assignment.renewAfterMs, undefined, { signal: ended.signal });
					if ((await gateway.renew(assignment, ended.signal)) === "STOPPED") {
						runner.stop(assignment.turnId, assignment.attempt, "developer");
					}
				} catch {
					// The renewal failed or the attempt ended while it waited.
					if (!isAborted(ended.signal)) {
						runner.stop(assignment.turnId, assignment.attempt, "lease");
					}
					return;
				}
			}
		})();
		try {
			await runner.start(assignment.turnId, assignment.attempt, assignment.token);
		} catch (error) {
			onFailure(error);
		} finally {
			clearTimeout(deadline);
			ended.abort();
			await renewals;
		}
	}
	async function forget(attempt: Promise<void>) {
		try {
			await attempt;
		} finally {
			active.delete(attempt);
		}
	}
	try {
		while (!signal.aborted) {
			if (active.size >= maxTurns) {
				await Promise.race([
					...active,
					sleep(idleMs, undefined, { signal }).catch(() => undefined),
				]);
				continue;
			}
			try {
				const assignment = await gateway.pull(signal);
				if (assignment !== null) {
					const attempt = run(assignment);
					active.add(attempt);
					void forget(attempt);
					continue;
				}
			} catch (error) {
				if (!isAborted(signal)) {
					onFailure(error);
				}
			}
			await sleep(idleMs, undefined, { signal }).catch(() => undefined);
		}
	} finally {
		await Promise.all(active);
	}
}

/** Read through a call: a signal aborts while its loop awaits, which narrowing across an await would hide. */
function isAborted(signal: AbortSignal): boolean {
	return signal.aborted;
}
