import {
	ExternalLinkIcon,
	InfoIcon,
	PackageIcon,
	RefreshCwIcon,
	TriangleAlertIcon,
} from "lucide-react";
import type { ReactNode } from "react";

import type { ReleaseStatus } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { useNow } from "@/components/common/use-now";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
	Card,
	CardAction,
	CardContent,
	CardDescription,
	CardHeader,
	CardTitle,
} from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { asDate } from "@/lib/dates";

import {
	RELEASE_CHECK_FAILURE_LABELS,
	RELEASE_CHECK_STATUS_DEFS,
} from "./release-check-status-defs";

const RELEASES_URL = "https://github.com/hephaestus-build/Hephaestus/releases";
const UPGRADE_GUIDE_URL = "https://docs.hephaestus.build/admin/install#upgrades";

export type ReleaseCheckRequest =
	| { status: "idle" | "pending" | "success" }
	| { status: "error"; error: unknown };

export type InstanceReleaseCardState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			release: ReleaseStatus;
			check: ReleaseCheckRequest;
			onCheck: () => void;
	  };

export interface InstanceReleaseCardProps {
	state: InstanceReleaseCardState;
}

function ExternalLink({ href, children }: { href: string; children: ReactNode }) {
	return (
		<a
			href={href}
			target="_blank"
			rel="noopener noreferrer"
			className="inline-flex items-center gap-1 underline underline-offset-4"
		>
			{children}
			<ExternalLinkIcon className="size-3 shrink-0" aria-hidden />
			<span className="sr-only"> (opens in a new tab)</span>
		</a>
	);
}

function runningLabel(running: ReleaseStatus["running"]): string {
	switch (running.channel) {
		case "RELEASE":
			return `v${running.version}`;
		case "COMMIT":
			return `commit ${running.version.slice(0, 7)}`;
		case "DEVELOPMENT":
			return running.version;
	}
}

function CheckSummary({ release }: { release: ReleaseStatus }) {
	const description = RELEASE_CHECK_STATUS_DEFS[release.status].description;
	const checked = <RelativeTime value={release.lastSuccess} fallback="at an unknown time" />;
	switch (release.status) {
		case "CURRENT":
			return (
				<>
					{description} Checked {checked}.
				</>
			);
		case "UPDATE_AVAILABLE":
			return (
				<>
					{description}
					{release.latest ? (
						<>
							{" "}
							v{release.latest.version} was published{" "}
							<RelativeTime value={release.latest.publishedAt} />; checked {checked}.
						</>
					) : null}
				</>
			);
		case "FAILED":
			return (
				<>
					{description}
					{release.failure ? (
						<>
							{" "}
							{RELEASE_CHECK_FAILURE_LABELS[release.failure]}{" "}
							<RelativeTime value={release.lastAttempt} fallback="at an unknown time" />
							{release.nextCheck ? (
								<>
									; next automatic check <RelativeTime value={release.nextCheck} />
								</>
							) : null}
							.
						</>
					) : null}
					{release.latest ? (
						<>
							{" "}
							The last completed check, {checked}, found v{release.latest.version}.
						</>
					) : null}
				</>
			);
		case "NEVER_CHECKED":
			return <>{description} The first one runs a minute after start.</>;
		case "DISABLED":
			return (
				<>
					{description} Set <code>HEPHAESTUS_RELEASE_CHECK_ENABLED=true</code> to resume, or compare
					with the <ExternalLink href={RELEASES_URL}>published releases</ExternalLink> yourself.
				</>
			);
		case "NOT_APPLICABLE":
			return <>{description}</>;
	}
}

/** A failed or never-performed check is never folded into "up to date"; the server's verdict is shown as is. */
export function InstanceReleaseCard({ state }: InstanceReleaseCardProps) {
	const now = useNow();
	const release = state.status === "ready" ? state.release : undefined;
	const def = release ? RELEASE_CHECK_STATUS_DEFS[release.status] : undefined;
	const retryUntil = asDate(release?.retryUntil);
	const rateLimited = retryUntil !== undefined && retryUntil.getTime() > now;
	const checking = state.status === "ready" && state.check.status === "pending";
	const canCheck =
		release !== undefined && release.status !== "DISABLED" && release.status !== "NOT_APPLICABLE";
	const latest = release?.latest;
	const newer = latest !== undefined && release?.status === "UPDATE_AVAILABLE";

	return (
		<Card>
			<CardHeader>
				<CardTitle className="flex items-center gap-2">
					<PackageIcon className="size-4 text-muted-foreground" aria-hidden />
					Release
				</CardTitle>
				<CardDescription>
					What this server runs, and whether a newer release is published.
				</CardDescription>
				{canCheck && state.status === "ready" && (
					<CardAction>
						<Button
							variant="outline"
							size="sm"
							disabled={checking || rateLimited}
							onClick={state.onCheck}
						>
							{checking ? <Spinner /> : <RefreshCwIcon aria-hidden />}
							{checking ? "Checking…" : "Check now"}
						</Button>
					</CardAction>
				)}
			</CardHeader>
			<CardContent className="space-y-3">
				{/* Mounted empty so the role exists before a message (ARIA22); the visible copy cannot be
				    the live region because its relative times re-render every tick. */}
				<p role="status" aria-live="polite" className="sr-only">
					{state.status !== "ready" || !def
						? ""
						: state.check.status === "pending"
							? "Checking for a newer release"
							: state.check.status === "success"
								? def.label
								: ""}
				</p>
				{state.status === "loading" ? (
					<div className="space-y-2">
						<Skeleton className="h-6 w-56" />
						<Skeleton className="h-4 w-full" />
						<Skeleton className="h-4 w-2/3" />
					</div>
				) : state.status === "error" ? (
					<QueryErrorAlert
						title="Release information is unavailable"
						error={state.error}
						onRetry={state.onRetry}
					/>
				) : release && def ? (
					<>
						<div className="flex flex-wrap items-center gap-2">
							<StatusBadge def={def} />
							<span className="font-mono text-sm">{runningLabel(release.running)}</span>
						</div>

						<p className="text-sm text-muted-foreground">
							<CheckSummary release={release} />
						</p>

						{newer && (
							<Alert variant={latest.schemaMigrations === true ? "warning" : "default"}>
								{latest.schemaMigrations === true ? <TriangleAlertIcon /> : <InfoIcon />}
								<AlertTitle>v{latest.version}</AlertTitle>
								<AlertDescription>
									<p>
										{latest.schemaMigrations === true
											? "Includes schema migrations: back up before upgrading and read the migration guide."
											: latest.schemaMigrations === false
												? "No schema migrations in this release; releases in between may still carry some."
												: "Read the release notes for migrations and operator actions before upgrading."}
									</p>
									<p className="flex flex-wrap gap-x-4">
										<ExternalLink href={latest.notesUrl}>Release notes</ExternalLink>
										<ExternalLink href={UPGRADE_GUIDE_URL}>Upgrade guide</ExternalLink>
									</p>
								</AlertDescription>
							</Alert>
						)}

						{state.check.status === "error" && (
							<QueryErrorAlert title="Could not check for updates" error={state.check.error} />
						)}

						<Collapsible>
							<CollapsibleTrigger
								render={
									<Button
										type="button"
										variant="ghost"
										size="sm"
										className="px-0 text-xs text-muted-foreground"
									/>
								}
							>
								Show deployment identity
							</CollapsibleTrigger>
							<CollapsibleContent className="mt-2">
								<dl className="grid gap-x-4 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
									<dt className="text-muted-foreground">Version</dt>
									<dd className="break-all font-mono">{release.running.version}</dd>
									<dt className="text-muted-foreground">Commit</dt>
									<dd className="break-all font-mono">
										{release.running.commit ?? "not reported"}
									</dd>
									<dt className="text-muted-foreground">Image</dt>
									<dd className="break-all font-mono">{release.running.image ?? "not reported"}</dd>
									<dt className="text-muted-foreground">Roles</dt>
									<dd>{release.running.roles.map((role) => role.toLowerCase()).join(", ")}</dd>
								</dl>
								<p className="mt-2 text-xs text-muted-foreground">
									Reported by this server from its release lock, not observed from the container.
									Other roles report their own identity under <code>release</code> in{" "}
									<code>/actuator/info</code>.
								</p>
							</CollapsibleContent>
						</Collapsible>
					</>
				) : null}
			</CardContent>
		</Card>
	);
}
