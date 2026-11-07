import type { ItemRef, ScanSummary } from "@/api/types.gen";
import { accountName } from "@/components/admin/users/account-labels";
import { definitionFor } from "@/components/common/status-def";
import { REVIEW_ORIGIN_DEFS } from "@/components/practice-vocabulary/review-origin-defs";
import {
	isActiveReview,
	reviewReasonSentence,
} from "@/components/practice-vocabulary/review-status-defs";

import { NOT_REVIEWED_SENTENCE } from "@/components/practice-vocabulary/scan-entry-defs";
import { itemName } from "@/lib/item-kinds";
import { hasText } from "@/lib/text";

export const SCAN_POLL_MS = 5000;

export const isActiveScan = (status: ScanSummary["status"]) => isActiveReview(status);

/** Where a scan stands: still going, through every file, or stopped before it was. */
export type ScanProgress = "active" | "complete" | "stopped";

export function scanProgress(status: ScanSummary["status"]): ScanProgress {
	if (isActiveScan(status)) {
		return "active";
	}
	return status === "COMPLETE" ? "complete" : "stopped";
}

/** A stopped scan that counted no practice and file pair. Its zeros would read as a clean result. */
export function countedNothing(scan: Pick<ScanSummary, "status" | "counts">): boolean {
	const { reviewed, withheld, notReviewed } = scan.counts;
	return scanProgress(scan.status) === "stopped" && reviewed + withheld + notReviewed === 0;
}

export function scanStartedBy(scan: Pick<ScanSummary, "requestedBy" | "origin">): string {
	return scan.requestedBy === null
		? definitionFor(REVIEW_ORIGIN_DEFS, scan.origin).label
		: accountName(scan.requestedBy);
}

export function repositoryName(repository: Pick<ItemRef, "kind" | "provider" | "label">): string {
	return hasText(repository.label) ? repository.label : itemName(repository);
}

export function shortVersion(version: string): string {
	return /^[0-9a-f]{12,}$/iu.test(version) ? version.slice(0, 7) : version;
}

export function ofTotal(count: number, total: number | null): string {
	return total === null ? String(count) : `${count} of ${total}`;
}

const WITHHELD_FALLBACK = "Withheld for a recorded reason that this page cannot describe yet.";

// Unknown reason codes must not become UI copy; use the generic explanation.
export function knownReasonSentence(code: string | null): string | undefined {
	return reviewReasonSentence(code);
}

export function withheldSentence(code: string | null): string {
	return knownReasonSentence(code) ?? WITHHELD_FALLBACK;
}

export function notReviewedSentence(code: string | null): string {
	const known = knownReasonSentence(code);
	return known === undefined ? NOT_REVIEWED_SENTENCE : `${NOT_REVIEWED_SENTENCE} ${known}`;
}
