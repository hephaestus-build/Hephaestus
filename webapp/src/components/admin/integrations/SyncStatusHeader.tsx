import { ChevronDownIcon, DatabaseIcon, GaugeIcon, WebhookIcon, ZapOffIcon } from "lucide-react";
import { Fragment, type ReactElement, type ReactNode } from "react";

import type { ConnectionSyncStatus, RateLimitSnapshot } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { ButtonGroup } from "@/components/ui/button-group";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuItem,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Item, ItemContent, ItemGroup, ItemMedia, ItemTitle } from "@/components/ui/item";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { asDate } from "@/lib/dates";
import { rendersContent } from "@/lib/react-node";
import { hasText } from "@/lib/text";

import { ActiveJobProgress } from "./ActiveJobProgress";
import { ConnectionHealthBadge } from "./ConnectionHealthBadge";
import { IntegrationCardHeading } from "./IntegrationCardHeading";
import {
	freshnessTone,
	nextRunLabel,
	relativeTime,
	type SyncTriggerType,
	stateLabel,
} from "./sync-format";
import { SyncNowButton } from "./SyncNowButton";

/** Under this share of the budget a rate limit is a thing that will break the next sync, not a stat. */
const RATE_LIMIT_WARNING_FRACTION = 0.1;

/**
 * One diagnostics fact: an icon, a label and a value. Deliberately not a metric tile — these are four
 * scalars an admin scans past on the way to the freshness sentence above, and tile chrome would give
 * them more weight than the reading they exist to qualify.
 */
function DiagnosticItem({
	icon,
	label,
	children,
}: {
	icon: ReactNode;
	label: string;
	children: ReactNode;
}) {
	return (
		<Item size="sm" role="listitem" className="w-auto gap-2 p-0">
			<ItemMedia variant="icon" className="text-muted-foreground">
				{icon}
			</ItemMedia>
			<ItemContent className="gap-0">
				<span className="text-xs text-muted-foreground">{label}</span>
				<ItemTitle className="text-sm font-normal">{children}</ItemTitle>
			</ItemContent>
		</Item>
	);
}

/**
 * The rate-limit diagnostic, rendered from one observed snapshot in strict priority order. Every branch
 * shows only measured fields — a value an admin reads here was reported by the vendor, never seeded or
 * derived by optimistic bookkeeping. When nothing measured is renderable the reading is `null` and the
 * caller drops the row entirely; the absence of the row *is* the "not reported by this instance" state.
 *
 * The order is deliberate:
 *  1. A live back-off (`throttledUntil` in the future) is the state an admin most needs to see — it is
 *     Slack's and any 429'd vendor's only real signal — so it wins over a stale gauge. No bar: there is
 *     no budget to show at that instant.
 *  2. A full `remaining / limit` gauge, but only when both were measured and are still current. It is
 *     rendered as a bare number rather than a bar: a "full = healthy" budget bar beside the job bar's
 *     "full = done" would put two bars on one card filling for opposite reasons. Only a budget about to
 *     run out earns colour, and the reset window lives one hover away.
 *  3. Ceiling only: the window budget is a real observation but the live remaining is not (reported once
 *     and since rolled over, or a vendor that never sends it). Show the ceiling — never a fabricated
 *     remaining, and never `— / N` which reads as a gauge.
 *  4. Nothing renderable → `null`.
 *
 * `now` decides only whether branch 1 is still live.
 */
function rateLimitReading(rateLimit: RateLimitSnapshot, now: number): ReactNode {
	const throttledUntil = asDate(rateLimit.throttledUntil);
	if (throttledUntil && throttledUntil.getTime() > now) {
		return <span className="text-warning">Throttled · retry {relativeTime(throttledUntil)}</span>;
	}

	if (rateLimit.limit != null && rateLimit.remaining != null) {
		const isLow =
			rateLimit.limit > 0 && rateLimit.remaining / rateLimit.limit < RATE_LIMIT_WARNING_FRACTION;
		const value = (
			<span className={isLow ? "text-warning tabular-nums" : "tabular-nums"}>
				{rateLimit.remaining.toLocaleString()}
				<span className="text-muted-foreground"> / {rateLimit.limit.toLocaleString()}</span>
			</span>
		);
		if (!rateLimit.resetAt) {
			return value;
		}
		return (
			<Tooltip>
				<TooltipTrigger className="cursor-help">{value}</TooltipTrigger>
				<TooltipContent>Resets {relativeTime(rateLimit.resetAt)}</TooltipContent>
			</Tooltip>
		);
	}

	if (rateLimit.limit != null) {
		return (
			<span className="text-muted-foreground tabular-nums">
				limit {rateLimit.limit.toLocaleString()}
			</span>
		);
	}

	return null;
}

function ConnectionDiagnostics({ status }: { status: ConnectionSyncStatus }) {
	// Ticks so that a back-off lapsing while the card is on screen re-renders it out of branch 1.
	const now = useNow();

	// A thin vertical rule separates each fact so the diagnostics scan as one grouped row. The rule is
	// decorative, so it stays out of the assistive-tech list.
	const diagnostics: ReactElement[] = [];

	// Gated like the rate limit below, and for the same reason: a diagnostic is only honest when
	// something was actually observed. `webhookRegistered` is nullable and null means "this connection
	// tracks no registration" — Slack's events arrive through the app's own subscription, a PAT-backed
	// SCM connection registers nothing — so `null` with no event ever seen is not a fault, it is silence.
	// Rendering "Webhook — No events yet" there accuses a connection of a gap it was never watched for.
	// A `false` registration IS a measured fact ("we should have one and don't"), so it still shows.
	const tracksWebhook = status.webhookRegistered != null || status.lastEventProcessedAt != null;
	if (tracksWebhook) {
		diagnostics.push(
			<DiagnosticItem
				key="webhook"
				icon={status.webhookRegistered === false ? <ZapOffIcon /> : <WebhookIcon />}
				label="Webhook"
			>
				{status.webhookRegistered === false ? (
					<span className="text-muted-foreground">Not registered</span>
				) : status.lastEventProcessedAt ? (
					<RelativeTime value={status.lastEventProcessedAt} />
				) : (
					<span className="text-muted-foreground">No events yet</span>
				)}
			</DiagnosticItem>,
		);
	}

	// A snapshot exists only when the vendor was observed, but an observed snapshot can still carry
	// nothing renderable (a lapsed throttle with no known ceiling), so the row is gated on the reading
	// itself — not merely on the snapshot's presence — to keep the "Rate limit" label from orphaning.
	const rateLimit = status.rateLimit === undefined ? null : rateLimitReading(status.rateLimit, now);
	if (rendersContent(rateLimit)) {
		diagnostics.push(
			<DiagnosticItem key="rateLimit" icon={<GaugeIcon />} label="Rate limit">
				{rateLimit}
			</DiagnosticItem>,
		);
	}

	if (status.backfill) {
		diagnostics.push(
			/* The scheduled cycle's state — "Disabled" here means the background cycle is off, not that
			   the Run backfill action below (a manual run) is unavailable. */
			<DiagnosticItem key="backfill" icon={<DatabaseIcon />} label="Scheduled backfill">
				{stateLabel(status.backfill.state)}
				{status.backfill.percent != null && (
					<span className="text-muted-foreground tabular-nums"> · {status.backfill.percent}%</span>
				)}
			</DiagnosticItem>,
		);
	}

	// Every row is gated on a real observation, so a connection that reports none (a fresh Slack
	// workspace before its first event) has nothing to qualify — render no empty row rather than an
	// invisible flex box the surrounding `space-y-4` would still pad around.
	if (diagnostics.length === 0) {
		return null;
	}

	return (
		<ItemGroup className="flex-row flex-wrap items-center gap-x-4 gap-y-2">
			{diagnostics.map((diagnostic, index) => (
				<Fragment key={diagnostic.key ?? index}>
					{index > 0 && (
						<Separator
							orientation="vertical"
							aria-hidden
							className="h-8 self-center max-sm:hidden"
						/>
					)}
					{diagnostic}
				</Fragment>
			))}
		</ItemGroup>
	);
}

export interface SyncStatusHeaderProps {
	/** The integration's name, for copy that has to name it ("No GitHub connection found"). */
	label: string;
	status?: ConnectionSyncStatus;
	isLoading?: boolean;
	error?: unknown;
	isConnectionActive: boolean;
	/**
	 * When the stored credential was first found unreadable. Health is what sync reports and a trigger
	 * would run on the token nothing can read, so both are withheld while this is set; cancelling a
	 * job that is already running needs no credential and stays. The notice above the header explains.
	 */
	credentialsUnreadableSince?: Date | null;
	/**
	 * Which trigger the admin just pressed, or `null` when none is in flight. Sync and Backfill are
	 * served by one mutation, so a bare `isPending` cannot name the operation the button should
	 * announce; the caller discriminates on the in-flight variables and passes the answer here.
	 */
	triggeringType?: SyncTriggerType | null;
	isCancelling?: boolean;
	onRetry: () => void;
	onSync: () => void;
	onBackfill?: () => void;
	onCancel: () => void;
	/** Integration-specific trailing controls, e.g. GitHub's "Manage installation" link. */
	actions?: ReactElement | undefined;
}

/**
 * The connection plane: health, freshness and the controls that change them, for every integration.
 *
 * The headline row is one sentence — health badge, when the mirror last completed, when it will next
 * run — because that is the whole question this page is opened to answer. The freshness reading is
 * tinted against the connection's own cadence, so "4 hours ago" reads as fine on a six-hourly
 * schedule and as a missed run on an hourly one.
 *
 * Everything below it qualifies that sentence: diagnostics that explain a bad reading, the running
 * job's progress, and one trigger, with the rare backfill operation as a split-button menu item.
 */
export function SyncStatusHeader({
	label,
	status,
	isLoading = false,
	error,
	isConnectionActive,
	credentialsUnreadableSince,
	triggeringType = null,
	isCancelling = false,
	onRetry,
	onSync,
	onBackfill,
	onCancel,
	actions,
}: SyncStatusHeaderProps) {
	const activeJob = status?.activeJob;
	const canBackfill = status?.backfillSupported === true && onBackfill != null;
	const isTriggerBusy = triggeringType != null || activeJob != null;
	const failed = error != null;

	return (
		<Card>
			<CardHeader>
				<IntegrationCardHeading>Connection</IntegrationCardHeading>
			</CardHeader>
			<CardContent className="space-y-4">
				{failed ? (
					<QueryErrorAlert
						error={error}
						title={`We couldn't load the ${label} connection`}
						onRetry={onRetry}
					/>
				) : isLoading ? (
					/* Mirrors the loaded layout — a headline line, a diagnostics row, a button row — so
					   resolving swaps text into boxes already the right size instead of shifting the page. */
					<div className="space-y-4">
						<Skeleton className="h-5 w-72" />
						<div className="flex flex-wrap gap-8">
							{Array.from({ length: 3 }, (_, index) => (
								<div key={index} className="space-y-1">
									<Skeleton className="h-3 w-20" />
									<Skeleton className="h-4 w-28" />
								</div>
							))}
						</div>
						<Skeleton className="h-8 w-40" />
					</div>
				) : status ? (
					<>
						<div className="flex flex-wrap items-center gap-x-3 gap-y-2">
							{!credentialsUnreadableSince && (
								<ConnectionHealthBadge health={status.health} isSyncing={activeJob != null} />
							)}
							<p className="text-sm">
								{status.lastSuccessfulSyncAt ? (
									<>
										Last synced{" "}
										<RelativeTime
											value={status.lastSuccessfulSyncAt}
											tone={freshnessTone(status.lastSuccessfulSyncAt, status.syncIntervalSeconds)}
										/>
									</>
								) : (
									/* "Never synced" on a connection with nothing to sync yet is an accusation the
									   facts don't support — a fresh Slack workspace has no activated channels. */
									<span className="text-muted-foreground">
										{status.resourceCounts.total === 0
											? "No resources to sync yet"
											: "Never synced"}
									</span>
								)}
								{hasText(nextRunLabel(status.nextScheduledSyncAt)) && (
									<span className="text-muted-foreground">
										{" · "}
										{nextRunLabel(status.nextScheduledSyncAt)}
									</span>
								)}
							</p>
						</div>

						<ConnectionDiagnostics status={status} />

						<ActiveJobProgress job={activeJob} />

						{isConnectionActive && (!credentialsUnreadableSince || activeJob) && (
							<div className="flex flex-wrap items-center gap-2 pt-2">
								{/* Sync / Backfill / Cancel act on one connection, so they read as one control. */}
								<ButtonGroup>
									{/* A trigger would run on the credential nothing can read; cancelling a running job needs none. */}
									{!credentialsUnreadableSince && (
										<SyncNowButton
											onClick={onSync}
											triggeringType={triggeringType}
											activeJob={activeJob}
										/>
									)}
									{canBackfill && !credentialsUnreadableSince && (
										<DropdownMenu>
											<DropdownMenuTrigger
												render={
													<Button
														variant="outline"
														size="sm"
														disabled={isTriggerBusy}
														aria-label="More sync options"
													>
														<ChevronDownIcon className="size-4" />
													</Button>
												}
											/>
											{/* The popup is anchored to a chevron, so it must opt out of the default
											    trigger-width sizing or "Run backfill" lands in a 32px column. */}
											<DropdownMenuContent align="end" className="w-auto min-w-40">
												<DropdownMenuItem onClick={onBackfill}>Run backfill</DropdownMenuItem>
											</DropdownMenuContent>
										</DropdownMenu>
									)}
									{activeJob && (
										<Button
											variant="outline"
											size="sm"
											disabled={isCancelling || activeJob.cancelRequested}
											onClick={onCancel}
										>
											{activeJob.cancelRequested ? "Stopping after current step…" : "Cancel"}
										</Button>
									)}
								</ButtonGroup>
								{actions !== undefined && <div className="ml-auto">{actions}</div>}
							</div>
						)}
					</>
				) : (
					<p className="text-sm text-muted-foreground">
						No {label} connection found for this workspace.
					</p>
				)}
			</CardContent>
		</Card>
	);
}
