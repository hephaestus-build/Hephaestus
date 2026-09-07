import type { ReleaseStatus } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { useNow } from "@/components/common/use-now";
import {
	Accordion,
	AccordionItem,
	AccordionTrigger,
	AccordionContent,
} from "@/components/ui/accordion";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { asDate } from "@/lib/dates";

const STATUS_LABELS: Record<ReleaseStatus["status"], string> = {
	CURRENT: "Current release",
	UPDATE_AVAILABLE: "Update available",
	CHECK_FAILED: "Update check failed",
	NEVER_CHECKED: "Never checked",
	UNSUPPORTED: "Update comparison unavailable",
	DISABLED: "Update checks disabled",
	STALE: "Update information is stale",
};

type CheckState = { status: "idle" | "pending" | "success" } | { status: "error"; error: unknown };

export type ReleaseStatusCardState =
	| { status: "pending" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			data: ReleaseStatus;
			check: CheckState;
			onCheck: () => void;
			refreshError?: { error: unknown; onRetry: () => void };
	  };

export interface ReleaseStatusCardProps {
	state: ReleaseStatusCardState;
}

export function ReleaseStatusCard({ state }: ReleaseStatusCardProps) {
	const data = state.status === "ready" ? state.data : undefined;
	const isChecking = state.status === "ready" && state.check.status === "pending";
	const now = useNow();
	const nextCheck = asDate(data?.nextCheck);
	const waiting = nextCheck !== undefined && nextCheck.getTime() > now;
	const stale = nextCheck !== undefined && nextCheck.getTime() <= now;
	const status =
		data && stale && ["CURRENT", "UPDATE_AVAILABLE"].includes(data.status) ? "STALE" : data?.status;
	return (
		<Card>
			<CardHeader>
				<CardTitle>Release and updates</CardTitle>
				<CardDescription>
					Running identity and advisory update information. Upgrades remain operator-controlled.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-4">
				<p role="status" className="sr-only">
					{state.status === "ready" && state.check.status === "success" && status
						? `Update check completed: ${STATUS_LABELS[status]}.`
						: ""}
				</p>
				{state.status === "pending" ? (
					<div className="space-y-2">
						<Skeleton className="h-6 w-48" />
						<Skeleton className="h-20 w-full" />
						<Skeleton className="h-20 w-full" />
						<Skeleton className="h-9 w-40" />
					</div>
				) : state.status === "error" ? (
					<QueryErrorAlert
						title="Release information is unavailable"
						error={state.error}
						onRetry={state.onRetry}
					/>
				) : data ? (
					<>
						{state.refreshError && (
							<QueryErrorAlert
								title="Could not refresh release information"
								{...state.refreshError}
							/>
						)}
						<p className="font-medium">
							{status === undefined ? "Unknown update state" : STATUS_LABELS[status]}
						</p>
						<dl className="grid gap-2 text-sm sm:grid-cols-[auto_1fr]">
							<dt>Running version</dt>
							<dd>
								{data.running.version} · {data.running.channel}
							</dd>
							<dt>Last attempt</dt>
							<dd>
								<RelativeTime value={data.lastAttempt} fallback="Never" />
							</dd>
							<dt>Last successful check</dt>
							<dd>
								<RelativeTime value={data.lastSuccess} fallback="Never" />
							</dd>
						</dl>
						{data.running.identityStatus === "MISMATCH" && (
							<p className="text-sm text-destructive">
								Build and deployment metadata disagree. Verify each runtime role before upgrading.
							</p>
						)}

						{data.failureReason && (
							<p className="text-sm">
								{data.failureReason === "RATE_LIMITED"
									? "GitHub rate-limited the check. The next check respects its retry window."
									: data.failureReason === "MALFORMED_RELEASE"
										? "The release response could not be validated."
										: "The release source could not be reached or returned an error."}{" "}
								Last-known results are not a current verdict.
							</p>
						)}
						{data.available && (
							<div className="space-y-2 border-t pt-3 text-sm">
								<p>
									Last checked published release:{" "}
									<a className="underline" href={data.available.notesUrl}>
										{data.available.version} — release notes
									</a>
								</p>
								<p>
									{data.available.schemaMigrations === "REQUIRED"
										? "Schema migrations: required. Back up before upgrading."
										: data.available.schemaMigrations === "NONE"
											? "No schema migrations in this release. Intervening releases may still require migrations."
											: "Schema migration requirements: unknown. Review the migration guide."}
								</p>
								<p>
									Security relevance: unknown to this checker. Review release notes and the security
									policy.
								</p>
								<p>{data.available.operatorActions}</p>
							</div>
						)}
						<Accordion>
							<AccordionItem value="identity">
								<AccordionTrigger>Build and deployment identity</AccordionTrigger>
								<AccordionContent>
									<dl className="grid gap-2 text-sm sm:grid-cols-[auto_1fr]">
										<dt>Build commit</dt>
										<dd className="break-all font-mono">{data.running.commit}</dd>
										<dt>Runtime roles in this process</dt>
										<dd>{data.running.roles.join(", ")}</dd>
										<dt>Deployment identity</dt>
										<dd>
											{data.running.identityStatus === "DEPLOYMENT_REPORTED"
												? "Build matches deployment metadata"
												: data.running.identityStatus === "MISMATCH"
													? "Build and deployment metadata disagree"
													: "Unknown or invalid deployment metadata"}
										</dd>
									</dl>
									<p className="text-sm text-muted-foreground">
										Image references describe the consumed deployment lock, not a live inventory of
										every container. Compare each role’s identity after upgrading.
									</p>
									{Object.keys(data.running.images).length > 0 && (
										<div>
											<p className="font-medium">Released image digests</p>
											<dl className="mt-2 space-y-2 text-sm">
												{Object.entries(data.running.images).map(([name, reference]) => (
													<div key={name}>
														<dt className="font-medium">{name}</dt>
														<dd className="break-all font-mono">{reference}</dd>
													</div>
												))}
											</dl>
										</div>
									)}
								</AccordionContent>
							</AccordionItem>
							<AccordionItem value="upgrade">
								<AccordionTrigger>Upgrade safely</AccordionTrigger>
								<AccordionContent>
									<p className="text-sm">
										Backup and verified restore status: unknown. Confirm a recoverable backup before
										changing the deployment.
									</p>
									<div className="flex flex-wrap gap-4 text-sm">
										<a className="underline" href={data.upgradeGuideUrl}>
											Verified upgrade and rollback instructions
										</a>
										<a
											className="underline"
											href="https://github.com/hephaestus-build/Hephaestus/security/policy"
										>
											Security policy
										</a>
										<a
											className="underline"
											href="https://docs.hephaestus.build/admin/backup-restore"
										>
											Backup and restore
										</a>
										<a
											className="underline"
											href="https://docs.hephaestus.build/admin/configuration-readiness"
										>
											Health and readiness verification
										</a>
									</div>
									<p className="text-sm text-muted-foreground">
										After upgrading, reload this page and compare the detected build and digests
										with the verified lock. A successful command alone does not prove a healthy
										upgrade.
									</p>
								</AccordionContent>
							</AccordionItem>
						</Accordion>

						{data.enabled ? (
							<div className="space-y-2">
								<Button
									variant="outline"
									disabled={isChecking || waiting || data.status === "UNSUPPORTED"}
									onClick={state.onCheck}
								>
									{isChecking && <Spinner />}
									{isChecking ? "Checking releases…" : "Check for updates"}
								</Button>
								{waiting && (
									<p className="text-sm text-muted-foreground">
										Cached or backing off; next check <RelativeTime value={data.nextCheck} />.
									</p>
								)}
							</div>
						) : (
							<p className="text-sm">
								Outbound checks are disabled by instance configuration. Enable
								HEPHAESTUS_RELEASE_CHECK_ENABLED to resume them.
							</p>
						)}
						{state.check.status === "error" && (
							<QueryErrorAlert
								title="Could not request an update check"
								error={state.check.error}
							/>
						)}
					</>
				) : null}
			</CardContent>
		</Card>
	);
}
