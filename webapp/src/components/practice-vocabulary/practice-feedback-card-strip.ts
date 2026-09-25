import type { ReviewedWorkRef } from "@/api/types.gen";
import type { KnownWorkProvider } from "@/components/icons/reviewed-work-icon";

import { count, countedWork } from "./feedback-text";
import type { CleanWork, ReviewedWorkOutcome } from "./PracticeFeedbackCard";

/**
 * The strip is a window on the newest reviews of this developer's work, at most this many, the
 * evidence and the clean work alike: a glance at the pattern, not a log of every place it was
 * seen. The label counts the distinct work on show, never more.
 */
const STRIP_WORK_LIMIT = 5;

/** One item of the strip, in the order it is drawn: a piece of work, what the review made of it, and when. */
export interface StripPiece extends ReviewedWorkOutcome {
	key: string;
}

/**
 * The strip: evidence and clean work in one run, left to right in time, so a clean piece reviewed
 * before the newest evidence stands where its date puts it rather than after it. The window keeps
 * the newest.
 */
export function stripPieces(
	reviewedWork: ReviewedWorkOutcome[],
	cleanWork: CleanWork[],
): StripPiece[] {
	return [
		// The same thread can be reviewed twice on different days.
		...reviewedWork.map((work) => ({
			...work,
			key: `${work.ref.kind} ${work.ref.id} ${work.date.getTime()}`,
		})),
		...cleanWork.map((clean) => ({
			...clean,
			key: `${clean.ref.kind} ${clean.ref.id} clean`,
			outcome: "DEMONSTRATED_STRENGTH" as const,
		})),
	]
		.sort((a, b) => a.date.getTime() - b.date.getTime())
		.slice(-STRIP_WORK_LIMIT);
}

/**
 * The work the strip shows — evidence and clean pieces alike — counted by what its pieces are:
 * "three pull requests", "three merge requests" at GitLab, and "four pieces of work" when the
 * pieces are not all of one kind, since the noun is claimed only where every piece bears it out.
 * The kind and the provider are the ones they share, for the glyph beside the label.
 */
export function countedStripWork(refs: ReviewedWorkRef[]): {
	kind?: string;
	provider?: KnownWorkProvider;
	text: string;
} {
	const [first] = refs;
	const oneKind = first !== undefined && refs.every((ref) => ref.kind === first.kind);
	const provider =
		first !== undefined && refs.every((ref) => ref.provider === first.provider)
			? first.provider
			: undefined;
	// The same piece reviewed twice is on show twice and counted once.
	const distinct = new Set(refs.map((ref) => `${ref.kind} ${ref.id}`)).size;
	const counted = oneKind
		? countedWork(first.kind, distinct, provider)
		: count(distinct, "piece of work", "pieces of work");
	// "Newest three pull requests" is also right when three is all there ever were; one piece is
	// simply where it was seen.
	return {
		kind: oneKind ? first.kind : undefined,
		provider,
		text: distinct > 1 ? `Newest ${counted}` : `Seen on ${counted}`,
	};
}
