import { ExternalLinkIcon, PackageIcon, RefreshCwIcon } from "lucide-react";
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

/** The manual check the reader last asked for; `success` is what the live region announces. */
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
			return `development build ${running.version}`;
	}
}

/**
 * What this server reports it runs, and what GitHub last said about newer releases. Every state the
 * server distinguishes stays distinguishable here: a failed or never-performed check is never
 * folded into "up to date".
 */
export function InstanceReleaseCard({ state }: InstanceReleaseCardProps) {
	const now = useNow();
	const release = state.status === "ready" ? state.release : undefined;
	const def = release ? RELEASE_CHECK_STATUS_DEFS[release.status] : undefined;
	const nextCheck = asDate(release?.nextCheck);
	const rateLimited =
		release?.failure === "RATE_LIMITED" && nextCheck !== undefined && nextCheck.getTime() > now;
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
					What this instance runs, and whether a newer release is published.
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
				{/* Mounted empty so the role exists before the message (ARIA22). */}
				<p role="status" className="sr-only">
					{state.status === "ready" && state.check.status === "success" && def
						? `Check completed: ${def.label}.`
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
							{release.status === "CURRENT" ? (
								<>
									GitHub reported no newer release{" "}
									<RelativeTime value={release.lastSuccess} fallback="at an unknown time" />.
								</>
							) : release.status === "UPDATE_AVAILABLE" && latest ? (
								<>
									v{latest.version} was published <RelativeTime value={latest.publishedAt} />;
									checked <RelativeTime value={release.lastSuccess} fallback="at an unknown time" />
									.
								</>
							) : release.status === "FAILED" && release.failure ? (
								<>
									{RELEASE_CHECK_FAILURE_LABELS[release.failure]}{" "}
									<RelativeTime value={release.lastAttempt} fallback="at an unknown time" />
									{nextCheck ? (
										<>
											; the next attempt is <RelativeTime value={nextCheck} />
										</>
									) : null}
									.
									{latest ? (
										<>
											{" "}
											The last completed check,{" "}
											<RelativeTime value={release.lastSuccess} fallback="at an unknown time" />,
											found v{latest.version}.
										</>
									) : null}
								</>
							) : release.status === "NEVER_CHECKED" ? (
								<>{def.description} The first one runs a minute after start.</>
							) : release.status === "DISABLED" ? (
								<>
									Outbound checks are off (<code>HEPHAESTUS_RELEASE_CHECK_ENABLED=false</code>).
									Compare with the{" "}
									<ExternalLink href={RELEASES_URL}>published releases</ExternalLink> yourself.
								</>
							) : (
								def.description
							)}
						</p>

						{newer && (
							<Alert variant={latest.schemaMigrations ? "warning" : "default"}>
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
								Deployment identity
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
									<dd>{release.running.roles.join(", ")}</dd>
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
