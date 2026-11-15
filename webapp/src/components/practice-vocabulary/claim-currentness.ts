import type { ResultList } from "@/api/types.gen";
import { asDate, formatDate, formatTime } from "@/lib/dates";

export type Currentness = ResultList["currentness"];

/**
 * Whether an observation still describes the work. An unverifiable one also says why it cannot be
 * checked, and when its capture read the work.
 */
export interface Freshness {
	currentness: ResultList["currentness"];
	/** A listed result leaves a missing value out; a detail read still sends null. */
	unverifiableReason?: ResultList["unverifiableReason"] | null;
	capturedAt?: ResultList["capturedAt"] | null;
}

export interface CurrentnessCopy {
	badge: string;
	badgeVariant: "warning" | "outline";
	description: string;
	note: string;
}

export const UNVERIFIED_HISTORY_NOTE =
	"Unverified history from an earlier review. It does not show whether the current standard is met.";

const STALE_NOTE = "The practice or the reviewed work changed after this observation.";

const STALE: CurrentnessCopy = {
	badge: "Historical observation",
	badgeVariant: "warning",
	description: `${STALE_NOTE} It stays in the record, but it does not describe the current state.`,
	note: STALE_NOTE,
};

const RECORD_MISSING: CurrentnessCopy = {
	badge: "Unverified observation",
	badgeVariant: "outline",
	description:
		"The record of what this observation was based on is missing, or it comes from an earlier version of the review. It cannot show whether the practice’s current standard is met.",
	note: "We cannot verify what this observation was based on.",
};

/** For a currentness that this client does not know: claim neither current nor a reason. */
export const UNKNOWN_CURRENTNESS: CurrentnessCopy = {
	badge: "Status unknown",
	badgeVariant: "outline",
	description:
		"This version of Hephaestus does not recognize how current this observation is. Treat it as not verified.",
	note: "This version of Hephaestus cannot tell whether this observation is current.",
};

/** For a reason this client does not know: say only what every reason shares. */
const UNVERIFIED: CurrentnessCopy = {
	badge: "Unverified observation",
	badgeVariant: "outline",
	description:
		"We cannot check this observation against the current work. It is not shown as current until we can.",
	note: "We cannot check this observation against the current work.",
};

const WORK_UNREADABLE_NOTE = "We could not read the current work to check this observation.";
const WORK_UNREADABLE_LIMIT = "It is not shown as current until we can check it again.";

/**
 * The record is there, but the current work could not be read on this server. The time is when the
 * capture read the work, not a later check: a check that found the work unchanged is not recorded.
 */
function workUnreadable(capturedAt: Date | undefined): CurrentnessCopy {
	const basis =
		capturedAt === undefined
			? ""
			: ` It is based on the work as of ${formatDate(capturedAt)}, ${formatTime(capturedAt)}.`;
	return {
		badge: "Cannot verify now",
		badgeVariant: "outline",
		description: `${WORK_UNREADABLE_NOTE}${basis} ${WORK_UNREADABLE_LIMIT}`,
		note: `${WORK_UNREADABLE_NOTE}${basis}`,
	};
}

/** What a surface says about an observation that may not describe the work; nothing for a current one. */
export function currentnessCopy(freshness: Freshness): CurrentnessCopy | undefined {
	if (freshness.currentness === "CURRENT") {
		return undefined;
	}
	if (freshness.currentness === "STALE") {
		return STALE;
	}
	if (freshness.currentness !== "UNVERIFIABLE") {
		return UNKNOWN_CURRENTNESS;
	}
	if (freshness.unverifiableReason === "RECORD_MISSING") {
		return RECORD_MISSING;
	}
	if (freshness.unverifiableReason === "WORK_UNREADABLE") {
		return workUnreadable(asDate(freshness.capturedAt));
	}
	return UNVERIFIED;
}

export function claimCurrentnessNote(freshness: Freshness): string | undefined {
	return currentnessCopy(freshness)?.note;
}

export const CURRENTNESS_VALUES = [
	"CURRENT",
	"STALE",
	"UNVERIFIABLE",
] as const satisfies readonly Currentness[];
