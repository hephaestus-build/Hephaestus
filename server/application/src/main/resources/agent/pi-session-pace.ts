import { readFileSync } from "node:fs";
import { getHeapStatistics } from "node:v8";

/**
 * A session gives its start credit back this long after it began, even with no model call yet, so
 * that one slow script cannot hold every other practice back.
 */
export const START_CREDIT_MS = 10_000;

/** What a session is waiting on: its own start, or the first byte of a model call. */
export interface ModelWait {
	/** A new session has not shown yet that the Pod and the provider have room for it. */
	start: (creditMs: number) => void;
	/** The session has shown it: it reached the model, or its credit ran out. */
	started: () => void;
	/** A model call waits for its first byte. The returned function records the byte, once. */
	call: () => () => void;
	/** A session that waits spends no work: its call may be behind the whole pool. */
	waitsForModel: () => boolean;
	holdsStarts: () => boolean;
	onChange: () => void;
}

export function newModelWait(): ModelWait {
	let calls = 0;
	let starting: NodeJS.Timeout | undefined;
	const wait: ModelWait = {
		start: (creditMs) => {
			starting = setTimeout(() => wait.started(), creditMs);
			starting.unref();
		},
		started: () => {
			if (starting !== undefined) {
				clearTimeout(starting);
				starting = undefined;
				wait.onChange();
			}
		},
		call: () => {
			calls += 1;
			wait.started();
			let open = true;
			return () => {
				if (open) {
					open = false;
					calls -= 1;
					wait.onChange();
				}
			};
		},
		waitsForModel: () => calls > 0,
		holdsStarts: () => calls > 0 || starting !== undefined,
		onChange: () => undefined,
	};
	return wait;
}

/**
 * When the next practice session may start. A session that is being served shows that the provider
 * has room, as an acknowledgement does in a congestion window. So a review widens while the provider
 * answers, and it stops widening when a call has to wait. No number sets the width.
 */
export class SessionPace {
	private readonly running = new Map<ModelWait, number>();
	private finished = { sessions: 0, ms: 0 };
	private wake: (() => void) | undefined;
	/** The starts that the provider allowed and the Pod's memory held back. */
	heldForMemory = 0;
	private readonly hasRoom: (running: number) => boolean;
	private readonly startCreditMs: number;
	private readonly now: () => number;

	constructor(
		hasRoom: (running: number) => boolean,
		startCreditMs = START_CREDIT_MS,
		now: () => number = Date.now,
	) {
		this.hasRoom = hasRoom;
		this.startCreditMs = startCreditMs;
		this.now = now;
	}

	/**
	 * Waits until a session may start. An empty Pod may always start one, or a review could never
	 * finish. False if `signal` ended the wait, or if the time left before `deadline` is shorter than
	 * the sessions so far took: a session that the deadline stops spends its work for nothing. Before
	 * any session has finished there is no estimate, so the session starts.
	 */
	async nextStart(signal: AbortSignal, deadline = Number.POSITIVE_INFINITY): Promise<boolean> {
		while (!signal.aborted && !this.mayStart()) {
			if (!this.timeFor(deadline)) {
				return false;
			}
			const turn = Promise.withResolvers<undefined>();
			const stop = () => turn.resolve(undefined);
			this.wake = stop;
			signal.addEventListener("abort", stop, { once: true });
			await turn.promise;
			signal.removeEventListener("abort", stop);
			this.wake = undefined;
		}
		return !signal.aborted && this.timeFor(deadline);
	}

	/** Whether the time left holds a session as long as the sessions so far took. */
	timeFor(deadline: number): boolean {
		const { sessions, ms } = this.finished;
		return sessions === 0 || deadline - this.now() >= ms / sessions;
	}

	begin(wait: ModelWait): void {
		wait.start(this.startCreditMs);
		wait.onChange = () => this.wake?.();
		this.running.set(wait, this.now());
	}

	end(wait: ModelWait): void {
		wait.started();
		wait.onChange = () => undefined;
		const startedAt = this.running.get(wait);
		if (startedAt !== undefined) {
			this.finished = {
				sessions: this.finished.sessions + 1,
				ms: this.finished.ms + this.now() - startedAt,
			};
		}
		this.running.delete(wait);
		this.wake?.();
	}

	get sessions(): number {
		return this.running.size;
	}

	private mayStart(): boolean {
		if (this.running.size === 0) {
			return true;
		}
		for (const wait of this.running.keys()) {
			if (wait.holdsStarts()) {
				return false;
			}
		}
		if (this.hasRoom(this.running.size)) {
			return true;
		}
		this.heldForMemory += 1;
		return false;
	}
}

/**
 * The Pod's own memory that a new session could still use. The kernel reclaims inactive file cache
 * before it kills the Pod, so that cache counts as free. Outside a cgroup v2 limit nothing bounds it.
 */
export function podMemoryAvailable(
	read: (file: string) => string = (file) => readFileSync(`/sys/fs/cgroup/${file}`, "utf8"),
): number {
	try {
		const limit = read("memory.max").trim();
		if (limit === "max") {
			return Number.POSITIVE_INFINITY;
		}
		const reclaimable = /^inactive_file (?<bytes>\d+)$/mu.exec(read("memory.stat"))?.groups?.bytes;
		return Number(limit) - Number(read("memory.current")) + Number(reclaimable ?? 0);
	} catch {
		return Number.POSITIVE_INFINITY;
	}
}

/**
 * Room for one more session: the Pod can hold a script process at its heap limit, and this process's
 * heap can hold one more average session. The heap at the first start is the part no session owns.
 */
export function roomForSession(options: {
	running: number;
	scriptHeapBytes: number;
	baseHeapBytes: number;
	available?: () => number;
	heap?: () => { used_heap_size: number; heap_size_limit: number };
}): boolean {
	const { used_heap_size: used, heap_size_limit: limit } = (options.heap ?? getHeapStatistics)();
	const perSession = Math.max(0, used - options.baseHeapBytes) / Math.max(1, options.running);
	return (
		(options.available ?? podMemoryAvailable)() >= options.scriptHeapBytes &&
		used + perSession < limit
	);
}

/**
 * Whether measuring ends: two sessions in a row ended unanswered, so a later session would wait as long. A session
 * that ends any other way resets the count.
 */
export function newStallRule(): (stop: string | null) => boolean {
	let stalls = 0;
	return (stop) => {
		stalls = stop === "stall" ? stalls + 1 : 0;
		return stalls >= 2;
	};
}
