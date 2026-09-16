import { formatDistanceToNow } from "date-fns";

import type { ConnectionSyncStatus, IntegrationCatalogEntry, SyncJob } from "@/api/types.gen";
import type { FreshnessTone } from "@/components/common/RelativeTime";
import { asDate } from "@/lib/dates";

/**
 * Poll cadence for sync status/resources/jobs queries. SSE hint invalidation is the primary live
 * channel, so polling is not a co-equal partner — it is either a slow backstop or the degraded
 * substitute for a stream that is down:
 *
 * - **Healthy stream, job running →** 30s backstop. Hint delivery is at-most-once, so a dropped
 *   terminal hint would otherwise strand a "Running" job on screen forever. 30s bounds that
 *   staleness without competing with the sub-second freshness the hints already provide.
 * - **Healthy stream, idle →** no interval at all. Idle changes arrive via mutation invalidation
 *   (user-initiated) or a hint (scheduled jobs); a dropped idle hint is healed by the next window
 *   focus, which `staleTime: 30_000` keeps cheap.
 * - **`livePushUnavailable` →** polling *is* the freshness channel, so pay the full 5s/60s.
 */
export function syncPollInterval(
	hasActiveJob: boolean,
	livePushUnavailable: boolean,
): number | false {
	if (livePushUnavailable) {
		return hasActiveJob ? 5000 : 60_000;
	}
	return hasActiveJob ? 30_000 : false;
}

/**
 * Human "5 minutes ago" phrasing for a timestamp. A missing/invalid value renders the {@link fallback}
 * dash — never "now" — so an absent timestamp can't masquerade as a fresh one.
 */
export function relativeTime(value: Date | string | undefined | null, fallback = "–"): string {
	const date = asDate(value);
	return date ? formatDistanceToNow(date, { addSuffix: true }) : fallback;
}

export type ConnectionHealth = ConnectionSyncStatus["health"];

export const HEALTH_LABEL: Record<ConnectionHealth, string> = {
	PENDING: "Pending",
	HEALTHY: "Healthy",
	DEGRADED: "Degraded",
	FAILED: "Failed",
	SUSPENDED: "Suspended",
};

export type ConnectionState = NonNullable<IntegrationCatalogEntry["connectionState"]>;

/**
 * The connection lifecycle as a word rather than a wire token — the {@link HEALTH_LABEL} treatment for
 * `connectionState`: a short label for inline use. Copy that must also explain the *consequence* and
 * the *next action* lives in `ConnectionStateNotice`, which is the only thing that should be explaining
 * a blocked state at length.
 */
export const CONNECTION_STATE_LABEL: Record<ConnectionState, string> = {
	PENDING: "finishing setup",
	ACTIVE: "connected",
	SUSPENDED: "suspended",
	UNINSTALLED: "removed",
};

export const JOB_STATUS_LABEL: Record<SyncJob["status"], string> = {
	PENDING: "Pending",
	RUNNING: "Running",
	SUCCEEDED: "Succeeded",
	SUCCEEDED_WITH_WARNINGS: "Succeeded with warnings",
	FAILED: "Failed",
	CANCELLED: "Cancelled",
};

/**
 * Both `SyncResourceState.state` and `BackfillSummary.state` are free-form, integration-defined
 * strings on the wire, so title-case whatever arrives rather than render a raw SCREAMING_SNAKE token:
 * `IN_PROGRESS` → "In Progress". There is deliberately no lookup table — each integration owns its own
 * vocabulary, so a table here could only drift out of date and fall back to this same rule anyway.
 */
export function stateLabel(state: string): string {
	return state
		.toLowerCase()
		.split(/[\s_]+/u)
		.filter(Boolean)
		.map((word) => word.charAt(0).toUpperCase() + word.slice(1))
		.join(" ");
}

/**
 * The job types an admin can trigger by hand. `INITIAL` is deliberately excluded: the server runs it
 * once when a connection is first established, and no UI offers it as a button.
 */
export type SyncTriggerType = Extract<SyncJob["type"], "RECONCILIATION" | "BACKFILL">;

export const JOB_TYPE_LABEL: Record<SyncJob["type"], string> = {
	INITIAL: "Initial sync",
	RECONCILIATION: "Reconciliation",
	BACKFILL: "Backfill",
};

export const JOB_TRIGGER_LABEL: Record<SyncJob["trigger"], string> = {
	SCHEDULED: "Scheduled",
	MANUAL: "Manual",
	LIFECYCLE: "Lifecycle",
	SYSTEM: "System",
};

/**
 * The `progress` JSONB narrowed to the shape the server's `SyncProgress` record writes.
 *
 * On the wire this is `{ [key: string]: unknown }` — OpenAPI models the column as a free-form object,
 * so TypeScript gives us no guarantee that any key is present or has the type we expect. Every read
 * goes through this one narrowing rather than casting at each call site: a runner that writes a
 * malformed value degrades to "field absent" instead of rendering `[object Object]` into the admin's
 * status line.
 */
export interface SyncJobProgress {
	phase?: string;
	currentStep?: string;
	currentRepository?: string;
	unitsCompleted?: number;
	unitsTotal?: number;
}

function asNonEmptyString(value: unknown): string | undefined {
	return typeof value === "string" && value.length > 0 ? value : undefined;
}

function asFiniteNumber(value: unknown): number | undefined {
	return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

export function jobProgress(job: Pick<SyncJob, "progress">): SyncJobProgress {
	const { progress } = job;
	if (progress == null) {
		return {};
	}
	return {
		phase: asNonEmptyString(progress.phase),
		currentStep: asNonEmptyString(progress.currentStep),
		currentRepository: asNonEmptyString(progress.currentRepository),
		unitsCompleted: asFiniteNumber(progress.unitsCompleted),
		unitsTotal: asFiniteNumber(progress.unitsTotal),
	};
}

export function jobCurrentStep(job: Pick<SyncJob, "progress">): string | undefined {
	return jobProgress(job).currentStep;
}

/**
 * Display names for the phase tokens `SyncPhase` emits. Unknown tokens are title-cased by
 * {@link phaseLabel} rather than dropped — an integration can add a phase without the UI shipping
 * first, and a readable guess beats hiding the chip.
 */
const PHASE_LABEL: Record<string, string> = {
	organization: "Organization",
	repositories: "Repositories",
	issues: "Issues",
	pullRequests: "Pull requests",
	comments: "Comments",
	commits: "Commits",
	teams: "Teams",
	channels: "Channels",
	collections: "Collections",
};

export function phaseLabel(token: string): string {
	return PHASE_LABEL[token] ?? stateLabel(token);
}

/**
 * A resource is legitimately "one cadence old" for the whole gap between two runs, so 1x would flag
 * the entire fleet right before every scheduled sync. 2x means a run was actually missed — the same
 * multiple `SyncStatusService.rollUp` uses, so a row tinted here is exactly a row counted in
 * `resourceCounts.stale`. 6x is a second band for "long past explaining away".
 */
const STALE_CADENCE_MULTIPLE = 2;
const VERY_STALE_CADENCE_MULTIPLE = 6;

export function freshnessTone(
	lastSyncedAt: Date | string | undefined | null,
	syncIntervalSeconds: number | undefined | null,
	now: Date = new Date(),
): FreshnessTone {
	const date = asDate(lastSyncedAt);
	if (!date) {
		return "never";
	}
	if (syncIntervalSeconds == null || syncIntervalSeconds <= 0) {
		return "unknown";
	}
	const ageSeconds = (now.getTime() - date.getTime()) / 1000;
	if (ageSeconds > syncIntervalSeconds * VERY_STALE_CADENCE_MULTIPLE) {
		return "veryStale";
	}
	if (ageSeconds > syncIntervalSeconds * STALE_CADENCE_MULTIPLE) {
		return "stale";
	}
	return "fresh";
}

/**
 * "next run in about 4 hours" for the connection's `nextScheduledSyncAt`. A freshness reading is
 * uninterpretable without the schedule behind it.
 */
export function nextRunLabel(
	nextScheduledSyncAt: Date | string | undefined | null,
	now: Date = new Date(),
): string | undefined {
	const date = asDate(nextScheduledSyncAt);
	if (!date) {
		return undefined;
	}
	// A schedule that is already due (or overdue — the worker may be busy or down) must not render as
	// "next run 5 minutes ago", which reads as a past event rather than a pending one.
	if (date.getTime() <= now.getTime()) {
		return "next run due";
	}
	return `next run ${formatDistanceToNow(date, { addSuffix: true })}`;
}
