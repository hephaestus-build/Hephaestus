import { Link } from "@tanstack/react-router";
import { ArrowRightIcon, NetworkIcon, PlugZapIcon } from "lucide-react";

import type { ConnectionSyncStatus, IntegrationCatalogEntry } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { GithubIcon, GitlabIcon, OutlineIcon, SlackIcon } from "@/components/icons/brand";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardFooter, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { ActiveJobProgress } from "./ActiveJobProgress";
import { ConnectionHealthBadge } from "./ConnectionHealthBadge";
import { ConnectionStateNotice } from "./ConnectionStateNotice";
import { IntegrationCardHeading } from "./IntegrationCardHeading";
import { freshnessTone } from "./sync-format";
import { SyncNowButton } from "./SyncNowButton";

const DETAIL_ROUTE: Record<
	IntegrationCatalogEntry["kind"],
	| "/w/$workspaceSlug/admin/directory"
	| "/w/$workspaceSlug/admin/github-access"
	| "/w/$workspaceSlug/admin/integrations/scm"
	| "/w/$workspaceSlug/admin/integrations/slack"
	| "/w/$workspaceSlug/admin/integrations/outline"
> = {
	KEYCLOAK_DIRECTORY: "/w/$workspaceSlug/admin/directory",
	GITHUB_ACCESS: "/w/$workspaceSlug/admin/github-access",
	GITHUB: "/w/$workspaceSlug/admin/integrations/scm",
	GITLAB: "/w/$workspaceSlug/admin/integrations/scm",
	SLACK: "/w/$workspaceSlug/admin/integrations/slack",
	OUTLINE: "/w/$workspaceSlug/admin/integrations/outline",
};

const KIND_ICON: Record<IntegrationCatalogEntry["kind"], React.ReactNode> = {
	GITHUB_ACCESS: <GithubIcon className="size-5" />,
	KEYCLOAK_DIRECTORY: <NetworkIcon className="size-5" />,
	GITHUB: <GithubIcon className="size-5" />,
	GITLAB: <GitlabIcon className="size-5" />,
	SLACK: <SlackIcon className="size-5" />,
	OUTLINE: <OutlineIcon className="size-5" />,
};

/**
 * The two ways a connection can read healthy overall and still be failing its job: errored resources,
 * and the quieter, more common stale ones — a connection whose scheduler has stopped reports HEALTHY
 * forever. Plain tinted text rather than badges: the health badge is the one badge a card gets, and
 * two more would out-shout it.
 */
function ResourceHealthLine({ counts }: { counts: ConnectionSyncStatus["resourceCounts"] }) {
	if (counts.errored === 0 && counts.stale === 0) return null;

	return (
		<p className="flex flex-wrap items-center gap-x-1.5">
			{counts.errored > 0 && <span className="text-destructive">{counts.errored} errored</span>}
			{counts.errored > 0 && counts.stale > 0 && <span className="text-muted-foreground">·</span>}
			{counts.stale > 0 && <span className="text-warning">{counts.stale} stale</span>}
			<span className="text-muted-foreground">of {counts.total} resources</span>
		</p>
	);
}

export interface IntegrationOverviewCardProps {
	workspaceSlug: string;
	entry: IntegrationCatalogEntry;
	status?: ConnectionSyncStatus;
	isStatusLoading?: boolean;
	isStatusError?: boolean;
	/** The thrown status error, so the alert can name what went wrong and judge whether Retry helps. */
	statusError?: unknown;
	/** Refetches the status query. Omit only where no refetch handle exists. */
	onRetryStatus?: () => void;
	isTriggering?: boolean;
	onSync: () => void;
}

export function IntegrationOverviewCard({
	workspaceSlug,
	entry,
	status,
	isStatusLoading = false,
	isStatusError = false,
	statusError,
	onRetryStatus,
	isTriggering = false,
	onSync,
}: IntegrationOverviewCardProps) {
	const detailTo = DETAIL_ROUTE[entry.kind];
	const isConnectionActive = entry.connectionState === "ACTIVE";
	const isScm = entry.kind === "GITHUB" || entry.kind === "GITLAB";

	return (
		// `h-full` so a grid of these stretches to one row height and the footer controls line up
		// across the row instead of tracking each card's own content height.
		<Card className="h-full">
			<CardHeader>
				<IntegrationCardHeading className="flex items-center gap-2">
					{KIND_ICON[entry.kind]}
					{entry.displayName}
				</IntegrationCardHeading>
				{/* Health is what sync reports; beside a token the server cannot read it would only mislead. */}
				{entry.connected && status && !entry.credentialsUnreadableSince && (
					<CardAction>
						<ConnectionHealthBadge health={status.health} isSyncing={status.activeJob != null} />
					</CardAction>
				)}
			</CardHeader>
			<CardContent className="space-y-3">
				{!entry.connected ? (
					<div className="space-y-3">
						<p className="flex items-center gap-1.5 text-muted-foreground text-sm">
							<PlugZapIcon className="size-4" />
							Not connected
						</p>
						{isScm ? (
							<p className="text-muted-foreground text-sm">
								Source control is selected when the workspace is created.
							</p>
						) : (
							<Link
								to={detailTo}
								params={{ workspaceSlug }}
								className={buttonVariants({ size: "sm" })}
							>
								Connect
								<ArrowRightIcon className="size-3.5" />
							</Link>
						)}
					</div>
				) : !isConnectionActive || entry.credentialsUnreadableSince ? (
					<ConnectionStateNotice
						connectionState={entry.connectionState}
						credentialsUnreadableSince={entry.credentialsUnreadableSince}
						displayName={entry.displayName}
					/>
				) : isStatusLoading ? (
					/* Two text lines, matching the status strip this resolves into, so the card holds its
					   height across the load. */
					<div className="space-y-2">
						<Skeleton className="h-4 w-56" />
						<Skeleton className="h-4 w-32" />
					</div>
				) : isStatusError ? (
					<QueryErrorAlert
						error={statusError}
						title="We couldn't load sync status"
						onRetry={onRetryStatus}
					/>
				) : (
					status && (
						<div className="space-y-2 text-sm">
							<div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-muted-foreground">
								{/* Tinted against this connection's own cadence, so a card is picked out of the
								    triage grid by colour rather than by reading four dates. */}
								<span>
									{status.lastSuccessfulSyncAt ? (
										<>
											Last synced{" "}
											<RelativeTime
												value={status.lastSuccessfulSyncAt}
												tone={freshnessTone(
													status.lastSuccessfulSyncAt,
													status.syncIntervalSeconds,
												)}
											/>
										</>
									) : (
										"Never synced"
									)}
								</span>
								<span>
									{status.lastEventProcessedAt ? (
										<>
											Last event <RelativeTime value={status.lastEventProcessedAt} />
										</>
									) : (
										"No events received yet"
									)}
								</span>
							</div>
							<ResourceHealthLine counts={status.resourceCounts} />
							<ActiveJobProgress job={status.activeJob} />
						</div>
					)
				)}
			</CardContent>
			{isConnectionActive && (
				<CardFooter
					className={
						entry.credentialsUnreadableSince
							? "mt-auto justify-end gap-2"
							: "mt-auto justify-between gap-2"
					}
				>
					{/* This card's trigger only ever enqueues a reconciliation, so that is what it announces.
					    A sync cannot use a token the server cannot read, so the trigger is not offered then. */}
					{!entry.credentialsUnreadableSince && (
						<SyncNowButton
							onClick={onSync}
							triggeringType={isTriggering ? "RECONCILIATION" : null}
							activeJob={status?.activeJob}
						/>
					)}
					<Link
						to={detailTo}
						params={{ workspaceSlug }}
						className={buttonVariants({ size: "sm", variant: "ghost" })}
					>
						View details
						<ArrowRightIcon className="size-3.5" />
					</Link>
				</CardFooter>
			)}
		</Card>
	);
}
