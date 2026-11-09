import { setTimeout as sleep } from "node:timers/promises";

import {
	type Client,
	CompositionHaltError,
	compositionClient,
	type Destination,
	DESTINATIONS,
	type Ending,
	type Gateway,
	type Next,
	type Passing,
	type Session,
	type Stop,
	type ToolAnswer,
} from "./review-composer-gateway.ts";
import { type Failure, ModelSession, type ToolResult } from "./review-composer-model.ts";

/**
 * The feedback composer loop of a `review_compose` run: the review on the work first, then the private feedback,
 * which reads the review it follows. The loop owns only its calls, retries and stop causes. The server renders each
 * session and runs every tool behind the gateway's composition routes, and it keeps what each tool call decided, so
 * nothing this loop remembers decides a guardrail. Each destination ends with prepared feedback or a withholding the
 * server records. A passing cause hands the run on instead, so another attempt composes what is open.
 */

export type { Gateway, Passing, Session, Stop } from "./review-composer-gateway.ts";

/** The causes that another attempt may compose past. */
const PASSING = new Set<Stop>(["DEADLINE", "PROVIDER", "CAPACITY", "UNEXPECTED"]);

/** At most this many model calls for each composition. */
export const MAX_COMPOSITION_CALLS = 12;
/** Fresh sessions after the first, only when the history under the review changed while it was composed. */
export const MAX_PUBLIC_RECOMPOSITIONS = 2;

const TOOLS = new Set(["check_package", "publish_package", "edit_own"]);
/** The answer to a tool call that follows the call that ended its session, in the same reply. */
const REVIEW_FINAL = "The review on this work is final; this composition accepts nothing more.";

/**
 * What the composition ended with: the passing cause that hands the run on, or nothing to end because the run lost its
 * lease or the driver aborted it. The driver alone renews and ends the run.
 */
export type Outcome =
	| { leaseLost: true }
	| { leaseLost: false; handedOn: Passing | null; failure: unknown };

/** Composes every eligible destination of the run's admitted review, then closes each destination left open. */
export async function composeReview(gateway: Gateway): Promise<Outcome> {
	const client = compositionClient(gateway);
	const stops = new Map<Destination, Stop>();
	const stopped = (error: unknown) =>
		(error instanceof CompositionHaltError && error.halt === "LEASE_LOST") ||
		gateway.signal?.aborted === true;
	let failure: unknown = null;
	try {
		await new Run(client, stops, gateway.retryPauseMs ?? 1000, gateway.signal).compose();
	} catch (error) {
		if (stopped(error)) {
			return { leaseLost: true };
		}
		if (error instanceof CompositionHaltError && error.halt !== "LEASE_LOST") {
			await client.stop(error.halt);
			for (const destination of DESTINATIONS) {
				stops.set(destination, error.halt);
			}
		} else {
			for (const destination of DESTINATIONS) {
				if (!stops.has(destination)) {
					stops.set(destination, "UNEXPECTED");
				}
			}
			failure = error;
		}
	}
	try {
		const handedOn = await client.close(stops);
		return { leaseLost: false, handedOn, failure };
	} catch (error) {
		if (stopped(error)) {
			return { leaseLost: true };
		}
		throw error;
	}
}

/** The cause a refused or failed model call ends its session with. */
function causeOf(failure: Failure): Stop {
	switch (failure) {
		case "OWNERSHIP_LOST": {
			throw new CompositionHaltError("LEASE_LOST");
		}
		case "AI_CHOICE": {
			return "NO_AI";
		}
		case "BUDGET": {
			return "BUDGET";
		}
		case "WORK_TIME": {
			return "DEADLINE";
		}
		case "REFUSED": {
			return "MODEL_REFUSED";
		}
		case "PROVIDER": {
			return "PROVIDER";
		}
		case "CAPACITY": {
			return "CAPACITY";
		}
	}
}

/** One composition: its run's deadline and what its sessions decided so far. */
class Run {
	readonly #client: Client;
	readonly #stops: Map<Destination, Stop>;
	readonly #retryPauseMs: number;
	readonly #signal: AbortSignal | undefined;
	readonly #finished = new Set<Destination>();
	#deadline = Number.POSITIVE_INFINITY;
	#deadlineText = "";
	#lastAttempt = false;
	#calls = 0;
	/** Why the current session ended without deciding everything it was given. */
	#stop: Stop | null = null;

	constructor(
		client: Client,
		stops: Map<Destination, Stop>,
		retryPauseMs: number,
		signal: AbortSignal | undefined,
	) {
		this.#client = client;
		this.#stops = stops;
		this.#retryPauseMs = retryPauseMs;
		this.#signal = signal;
	}

	async #next(): Promise<Next | null> {
		const next = await this.#client.next(this.#finished);
		if (next !== null && this.#deadlineText === "") {
			// The first answer fixes the composition's deadline; later sessions keep it.
			this.#deadlineText = next.deadline;
			this.#deadline = Date.parse(next.deadline);
			this.#lastAttempt = next.lastAttempt;
		}
		return next;
	}

	async compose(): Promise<void> {
		let next = await this.#next();
		const unavailable = next?.unavailable ?? null;
		if (unavailable !== null) {
			for (const destination of DESTINATIONS) {
				this.#stops.set(destination, unavailable);
			}
			return;
		}
		if (next?.session?.destination === "ON_WORK") {
			let reviewed: boolean;
			try {
				reviewed = await this.#composeReview(next.session);
			} catch (error) {
				if (error instanceof CompositionHaltError || this.#signal?.aborted === true) {
					throw error;
				}
				// A failure of the loop itself, such as an unreadable answer: no attempt starts another session.
				await this.#client.stop("UNEXPECTED");
				throw error;
			}
			this.#finished.add("ON_WORK");
			if (!reviewed) {
				const ended = this.#ended();
				this.#stops.set("ON_WORK", ended);
				// The private feedback reads the review it follows: a review that another attempt may still compose
				// comes first.
				if (PASSING.has(ended) && !this.#lastAttempt) {
					this.#stops.set("PRACTICE_PAGE", ended);
					this.#stops.set("CONVERSATION", ended);
					return;
				}
			}
			next = await this.#next();
		}
		if (next === null) {
			return;
		}
		// A review that stopped for a passing cause also spent the calls the private sessions would use.
		if (this.#stop !== null && !PASSING.has(this.#stop)) {
			this.#stop = null;
		}
		for (;;) {
			if (this.#calls >= MAX_COMPOSITION_CALLS || this.#expired()) {
				this.#stopFor(this.#outOfRoom());
				break;
			}
			const session = next?.session ?? null;
			if (session === null) {
				break;
			}
			await this.#composePrivateDestination(session);
			this.#finished.add(session.destination);
			next = await this.#next();
		}
		const ended = this.#ended();
		this.#stops.set("PRACTICE_PAGE", ended);
		this.#stops.set("CONVERSATION", ended);
	}

	/** The cause a session recorded, or, when none ran or none recorded one, whether time or the model ended it. */
	#ended(): Stop {
		return this.#stop ?? this.#outOfRoom();
	}

	/**
	 * Ends the session for a cause that left something undecided. A cause that another attempt may compose past stays
	 * recorded: a later destination that found the run's calls spent must not turn it into a withholding.
	 */
	#stopFor(cause: Stop): void {
		if (this.#stop !== null && PASSING.has(this.#stop) && !PASSING.has(cause)) {
			return;
		}
		if (this.#stop === "PUBLICATION_REFUSED" && cause === "PUBLICATION_REFUSED") {
			return;
		}
		this.#stop = cause;
	}

	#outOfRoom(): Stop {
		if (this.#expired()) {
			return "DEADLINE";
		}
		return this.#calls >= MAX_COMPOSITION_CALLS ? "STEP_LIMIT" : "NO_DECISION";
	}

	#limitReached(): Stop {
		return this.#expired() ? "DEADLINE" : "STEP_LIMIT";
	}

	#expired(): boolean {
		return Date.now() >= this.#deadline;
	}

	/**
	 * Waits before the call after a provider failure, twice as long after each, so that a short outage does not spend
	 * the session's calls at once. Never waits past the deadline.
	 */
	async #pause(failures: number): Promise<boolean> {
		const wait = this.#retryPauseMs * 2 ** Math.min(failures - 1, 5);
		const nap = Math.min(wait, this.#deadline - Date.now());
		if (nap > 0) {
			await sleep(nap, undefined, { signal: this.#signal });
		}
		return !this.#expired();
	}

	async #stopped(cause: Stop): Promise<Ending> {
		this.#stopFor(cause);
		await this.#client.stop(cause);
		return "STOPPED";
	}

	#bound(session: Session): number {
		return Math.min(MAX_COMPOSITION_CALLS - this.#calls, session.calls);
	}

	#model(session: Session): ModelSession {
		return new ModelSession(
			async (protocol, body) => {
				this.#calls += 1;
				return this.#client.model(protocol, body);
			},
			session.model,
			session.system,
			session.tools,
		);
	}

	async #tool(
		session: Session,
		call: { id: string; name: string; arguments: unknown },
	): Promise<ToolAnswer> {
		return this.#client.tool(
			call.name,
			{
				destination: session.destination,
				callId: call.id,
				arguments: call.arguments,
				publicationContext: session.publicationContext,
			},
			this.#deadlineText,
		);
	}

	// ------------------------------------------------------------ the review on the work

	/** Whether the review on the work is accepted, by this composition or an earlier attempt. */
	async #composeReview(first: Session): Promise<boolean> {
		let session = first;
		let { accepted } = first;
		for (let recomposed = 0; recomposed <= MAX_PUBLIC_RECOMPOSITIONS; recomposed += 1) {
			if (this.#expired()) {
				await this.#stopped("DEADLINE");
				break;
			}
			if (recomposed > 0) {
				// A fresh read: the history changed under the review.
				const fresh = await this.#next();
				const again = fresh?.session ?? null;
				if (again?.destination !== "ON_WORK") {
					break;
				}
				session = again;
			}
			const outcome = await this.#reviewSession(session);
			accepted ||= outcome.published;
			if (outcome.ending !== "RECOMPOSE") {
				break;
			}
			if (recomposed === MAX_PUBLIC_RECOMPOSITIONS) {
				await this.#stopped("PUBLICATION_REFUSED");
			}
		}
		return accepted;
	}

	async #reviewSession(session: Session): Promise<{ ending: Ending; published: boolean }> {
		let { accepted } = session;
		let published = false;
		if (this.#calls >= MAX_COMPOSITION_CALLS) {
			return { ending: await this.#stopped("STEP_LIMIT"), published };
		}
		if (this.#expired()) {
			return { ending: await this.#stopped("DEADLINE"), published };
		}
		if (!session.fits) {
			return { ending: await this.#stopped("CONTEXT_WINDOW"), published };
		}
		const model = this.#model(session);
		const bound = this.#bound(session);
		let reply = await model.send(session.input);
		let askedOnceMore = false;
		let nudged = false;
		let providerFailures = 0;
		for (;;) {
			const { failure } = reply;
			const room = model.calls < bound && !this.#expired();
			if (failure === "PROVIDER") {
				providerFailures += 1;
				if (!room || !(await this.#pause(providerFailures))) {
					return { ending: await this.#stopped("PROVIDER"), published };
				}
				reply = await model.retry();
				continue;
			}
			if (failure !== null) {
				return { ending: await this.#stopped(causeOf(failure)), published };
			}
			if (reply.toolCalls.length === 0) {
				if (accepted) {
					await this.#client.stop("COMPLETE");
					return { ending: "STORED", published };
				}
				if (!room) {
					return { ending: await this.#stopped(this.#limitReached()), published };
				}
				if (askedOnceMore || session.finish === null) {
					return { ending: await this.#stopped("NO_DECISION"), published };
				}
				askedOnceMore = true;
				reply = await model.send(session.finish);
				continue;
			}
			const results: ToolResult[] = [];
			let ending: Ending | null = null;
			for (const call of reply.toolCalls) {
				if (ending !== null && TOOLS.has(call.name)) {
					results.push({ callId: call.id, text: REVIEW_FINAL });
					continue;
				}
				const result = await this.#tool(session, call);
				results.push({ callId: call.id, text: result.text });
				if (result.published) {
					accepted = true;
					published = true;
				}
				if (result.stop !== null) {
					this.#stop = result.stop;
				}
				ending ??= result.ending;
			}
			if (ending !== null) {
				return { ending, published };
			}
			if (!room || this.#expired()) {
				return { ending: await this.#stopped(this.#limitReached()), published };
			}
			let then: string | null = null;
			if (!nudged && model.calls >= bound - 2) {
				nudged = true;
				then = session.nudge;
			}
			reply = await model.answer(results, then);
		}
	}

	// ------------------------------------------------------------ private feedback

	async #composePrivateDestination(session: Session): Promise<void> {
		if (this.#expired()) {
			this.#stopFor("DEADLINE");
			return;
		}
		if (!session.fits) {
			this.#stopFor("CONTEXT_WINDOW");
			return;
		}
		const model = this.#model(session);
		const bound = this.#bound(session);
		let reply = await model.send(session.input);
		let accepted = false;
		let askedOnceMore = false;
		let nudged = false;
		let providerFailures = 0;
		for (;;) {
			const { failure } = reply;
			const room = model.calls < bound && !this.#expired();
			if (failure === "PROVIDER") {
				providerFailures += 1;
				if (!room || !(await this.#pause(providerFailures))) {
					this.#stopFor("PROVIDER");
					break;
				}
				reply = await model.retry();
				continue;
			}
			if (failure !== null) {
				this.#stopFor(causeOf(failure));
				break;
			}
			if (reply.toolCalls.length === 0) {
				if (session.finish === null) {
					break;
				}
				if (!room) {
					this.#stopFor(this.#limitReached());
					break;
				}
				if (askedOnceMore) {
					break;
				}
				askedOnceMore = true;
				reply = await model.send(session.finish);
				continue;
			}
			const results: ToolResult[] = [];
			for (const call of reply.toolCalls) {
				const result = await this.#tool(session, call);
				results.push({ callId: call.id, text: result.text });
				accepted ||= result.published;
			}
			if (accepted) {
				return;
			}
			if (!room) {
				this.#stopFor(this.#limitReached());
				break;
			}
			let then: string | null = null;
			if (!nudged && model.calls >= bound - 2) {
				nudged = true;
				then = session.nudge;
			}
			reply = await model.answer(results, then);
		}
	}
}
