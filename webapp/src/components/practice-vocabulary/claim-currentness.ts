import type { ResultList } from "@/api/types.gen";
import { asDate, formatDate, formatShortDay, formatTime } from "@/lib/dates";

export type Currentness = ResultList["currentness"];

/**
 * Whether an observation still describes the work. An unverifiable one also says why it cannot be
 * checked and when it was last verified against the work.
 */
export type Freshness = Pick<ResultList, "currentness" | "unverifiableReason" | "lastVerifiedAt">;

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

/** The record is there, but the current work could not be read on this server. */
function workUnreadable(lastVerifiedAt: Date | undefined): CurrentnessCopy {
	if (lastVerifiedAt === undefined) {
		return {
			badge: "Unverified observation",
			badgeVariant: "outline",
			description: `${WORK_UNREADABLE_NOTE} ${WORK_UNREADABLE_LIMIT}`,
			note: WORK_UNREADABLE_NOTE,
		};
	}
	const verified = `It was last verified against the work on ${formatDate(lastVerifiedAt)}, ${formatTime(lastVerifiedAt)}.`;
	return {
		badge: `Unverified since ${formatShortDay(lastVerifiedAt)}`,
		badgeVariant: "outline",
		description: `${WORK_UNREADABLE_NOTE} ${verified} ${WORK_UNREADABLE_LIMIT}`,
		note: `${WORK_UNREADABLE_NOTE} ${verified}`,
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
	if (freshness.unverifiableReason === "RECORD_MISSING") {
		return RECORD_MISSING;
	}
	if (freshness.unverifiableReason === "WORK_UNREADABLE") {
		return workUnreadable(asDate(freshness.lastVerifiedAt));
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
