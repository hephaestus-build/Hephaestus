import {
	CirclePlayIcon,
	ClockIcon,
	EyeOffIcon,
	HourglassIcon,
	NotebookPenIcon,
} from "lucide-react";

import type { ItemEvent } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

import { REVIEW_ORIGIN_DEFS } from "./review-origin-defs";

export type ItemEventState = ItemEvent["state"];
export type ItemEventReason = NonNullable<ItemEvent["reason"]>;
export type ItemEventFoundBy = ItemEvent["foundBy"];

// Withheld events record a decision and reason, never a practice verdict.
export const ITEM_EVENT_STATE_DEFS: StatusDefs<ItemEventState> = {
	RECORDED: {
		label: "Recorded",
		icon: NotebookPenIcon,
		badgeVariant: "outline",
		description: "The event was recorded. No practice reviews on it.",
	},
	WAITING: {
		label: "Waiting",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "The event is waiting before a review starts.",
	},
	STARTED: {
		label: "Started a review",
		icon: CirclePlayIcon,
		badgeVariant: "success",
		description: "The event started a review.",
	},
	WITHHELD: {
		label: "Withheld",
		icon: EyeOffIcon,
		badgeVariant: "warning",
		description: "A check decided not to review on this event. The reason says which.",
	},
	EXPIRED: {
		label: "Expired",
		icon: HourglassIcon,
		badgeVariant: "outline",
		description: "The event waited too long and no review started.",
	},
};

export const ITEM_EVENT_REASON_SENTENCES: Record<ItemEventReason, string> = {
	// One code for a start switch turned off and for older records of no usable model or
	// practice, so it names the settings without saying which one.
	GATE_WITHHELD: "The workspace’s settings did not allow a review to start.",
	COOLDOWN_ACTIVE: "The work was reviewed recently, so it waits before another review.",
	REQUEST_COOLDOWN_ACTIVE: "A review was requested recently, so another request waits.",
	REQUESTER_QUOTA_EXHAUSTED: "The person who asked has used their review requests for now.",
	CONCURRENT_DUPLICATE: "Another review of the same work was already starting.",
	COALESCED: "It was joined with another event on the same work into one review.",
	OUT_OF_REVIEW_SCOPE: "The work is outside the workspace’s review scope.",
	BOT_AUTHOR: "A bot wrote the work.",
	BOT_REVIEWER: "A bot submitted the review.",
	STALE_ROLLOUT_REVISION: "Review settings changed before the review could start.",
	WORKSPACE_INACTIVE: "The workspace is not active.",
	PRACTICES_DISABLED: "Practice review is turned off for this workspace.",
	NO_ACTIVE_PRACTICE: "No active practice reviews on this event.",
	REVIEW_MODEL_UNBOUND: "No AI model is assigned to practice review.",
	MEMBER_AI_DECLINED: "The developer declined AI review.",
	PERSON_DATA_ERASED: "The person’s data was erased.",
	FEEDBACK_MODE_OFF: "Every practice that reviews on this event has feedback mode Off.",
	SUBJECT_UNLINKED: "The person the work is about has no account in this workspace.",
	MERGE_ACTOR_UNAVAILABLE: "Whoever merged the work could not be identified.",
	MODEL_UNAVAILABLE: "The assigned AI model was unavailable.",
	BUDGET_EXHAUSTED: "The workspace’s review budget is used up.",
	ITEM_NOT_VISIBLE: "Hephaestus can no longer see the work.",
	VERSION_SUPERSEDED: "The work changed before this version could be reviewed.",
	PENDING_DEADLINE_EXCEEDED: "The review did not start before its deadline.",
	ITEM_GONE: "The work no longer exists.",
	LEGACY_REVIEW_UNBOUND:
		"An earlier version recorded this review without naming which review it was, so it cannot start.",
	OCCASION_EVIDENCE_UNAVAILABLE: "The original work for this event is unavailable.",
	QUIET_WINDOW:
		"It waits briefly for more changes to the same work, so that they are decided together.",
	REVIEW_IN_PROGRESS:
		"An earlier review of the same work, queued or running, still includes a practice that this event selects. This event is decided again after that review ends.",
	ALREADY_CURRENT:
		"Every practice that reviews on this event already has a current observation of this work.",
};

// Event discovery determines timestamp precision; shared origins use the review-origin wording.
export const ITEM_EVENT_FOUND_BY: Record<ItemEventFoundBy, { label: string; description: string }> =
	{
		WEBHOOK: {
			label: "Live event",
			description: "Reported by the provider as it happened, so the time is exact.",
		},
		SYNC: {
			label: "Noticed during a sync",
			description: "Spotted by a scheduled sync, so the time is only as precise as the sync.",
		},
		REQUEST: REVIEW_ORIGIN_DEFS.REQUEST,
		BACKFILL: REVIEW_ORIGIN_DEFS.BACKFILL,
		SCHEDULE: REVIEW_ORIGIN_DEFS.SCHEDULE,
	};
