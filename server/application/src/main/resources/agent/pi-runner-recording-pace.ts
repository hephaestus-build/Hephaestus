/**
 * When a review session is asked to record what it has already settled.
 *
 * Context is the resource a session actually spends: every file it opens stays in the window, the
 * window is finite, and recall degrades as it fills. So the ask is placed at shares of the session's
 * own context rather than on a clock or a count of calls. A clock scales with the review's time
 * budget, which is why a session on a long budget could read for a quarter of an hour before its only
 * reminder; a count of calls says nothing about how much a call brought back, and one read of a large
 * file can cost more of the window than twenty small ones.
 */

/** The prompt buckets pi-ai reports on an assistant message. */
export interface PromptUsage {
	readonly input: number;
	readonly cacheRead: number;
	readonly cacheWrite: number;
}

/**
 * How much of the window the prompt the model was just sent occupies.
 *
 * pi-ai splits the provider's one prompt count into three disjoint buckets — it derives `input` by
 * subtracting the cached and the newly written prefix from it — so the prompt is their sum, and a sum
 * that leaves one out reads as a smaller prompt than was sent. Leaving out `cacheWrite` undercounts
 * by exactly the prefix a turn added to the cache, which is largest on the turns where the window
 * fills fastest.
 */
export function promptTokens(usage: PromptUsage): number {
	return (usage.input || 0) + (usage.cacheRead || 0) + (usage.cacheWrite || 0);
}

/**
 * The shares of the window at which a session is asked. Early enough that what it has read is still
 * recalled well, spread so a session that only fills the window slowly is not asked repeatedly.
 */
export const RECORDING_CHECKPOINTS: readonly number[] = [0.4, 0.65, 0.85];

export interface RecordingPace {
	/**
	 * Takes the tokens of the prompt the model was just sent, and answers with the share of the window
	 * that crossing it reached, or null when no new share was reached.
	 */
	readonly checkpointReached: (promptSize: number) => number | null;
}

/**
 * @param inheritsContext whether the session opens on a window it did not read: an observer forked
 *   from the shared reconnaissance starts with that transcript, and the share it arrives holding is
 *   not a share it spent. A session that starts empty is asked from its very first turn, because
 *   everything in its window is something it went and read.
 */
export function createRecordingPace(
	contextWindow: number,
	inheritsContext: boolean,
	checkpoints: readonly number[] = RECORDING_CHECKPOINTS,
): RecordingPace {
	if (!Number.isFinite(contextWindow) || contextWindow <= 0) {
		throw new Error(`contextWindow must be a positive number, got: ${contextWindow}`);
	}
	if (checkpoints.length === 0) {
		throw new Error("at least one checkpoint is required");
	}
	let previous = 0;
	for (const checkpoint of checkpoints) {
		if (!(checkpoint > previous) || checkpoint > 1) {
			throw new Error(`checkpoints must ascend within (0, 1], got: ${checkpoints.join(", ")}`);
		}
		previous = checkpoint;
	}
	// How many leading shares the window is currently past. It rises as the session fills the window
	// and falls when the window empties, so a share is answered for the occupancy it describes rather
	// than once in the life of the session.
	let passed = 0;
	// The first prompt of a session that inherited its window says where the session began, not what
	// it read.
	let started = !inheritsContext;
	const at = (index: number) => checkpoints[index] ?? Number.POSITIVE_INFINITY;
	return {
		checkpointReached(promptSize: number): number | null {
			if (!Number.isFinite(promptSize) || promptSize <= 0) return null;
			const share = promptSize / contextWindow;
			// Compaction empties the window mid-review and the session reads on from a summary of what
			// it can no longer see, which is the situation this whole pace exists for. A share the
			// window has fallen back below is asked for again when the session fills it again.
			while (passed > 0 && share < at(passed - 1)) passed -= 1;
			let reached: number | null = null;
			// A single large read can cross more than one share at once, and the session is asked once
			// for the furthest it reached rather than once per share it stepped over.
			while (passed < checkpoints.length && share >= at(passed)) {
				reached = at(passed);
				passed += 1;
			}
			if (!started) {
				started = true;
				return null;
			}
			return reached;
		},
	};
}
