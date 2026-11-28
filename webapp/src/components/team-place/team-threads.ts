/** A version's state as its review discussion shows it. Anything but `AVAILABLE` means its content is gone. */
export type TeamVersionState = "AVAILABLE" | "WITHDRAWN" | "EXPIRED" | "ERASED";

/**
 * The review discussion of one review: one thread for each review, created when the review seals. January has no
 * other thread.
 */
export interface TeamThreadData {
	id: string;
	title: string;
	review: {
		itemTitle: string;
		versionSequence: number;
		versionState: TeamVersionState;
	};
	lastActivityAt: Date;
}

const GONE: Readonly<Record<Exclude<TeamVersionState, "AVAILABLE">, string>> = {
	WITHDRAWN: "was withdrawn",
	EXPIRED: "expired",
	ERASED: "was removed with its sender’s account",
};

/** Why a review's version can no longer open, such as "Version 1 was withdrawn", or nothing while it is available. */
export function versionGoneText(sequence: number, state: TeamVersionState): string | undefined {
	return state === "AVAILABLE" ? undefined : `Version ${sequence} ${GONE[state]}`;
}
