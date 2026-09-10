import { Link } from "@tanstack/react-router";
import { ShieldCheck } from "lucide-react";
import { useId, useState } from "react";
import { useSpinDelay } from "spin-delay";

import type {
	GitHubAccessConfiguration,
	GitHubAccessDecision,
	GitHubAccessState,
	GitHubAccessTarget,
	SyncJob,
} from "@/api/types.gen";
import { ActiveJobProgress } from "@/components/admin/integrations/ActiveJobProgress";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button, buttonVariants } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";

export type GithubAccessPageState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			data: GitHubAccessState;
			approvedGroups: string[];
			directoryError?: unknown;
	  };

export interface WorkspaceGithubAccessPageProps {
	workspaceSlug: string;
	state: GithubAccessPageState;
	isOwner: boolean;
	busy: boolean;
	savingPolicy: boolean;
	changingTargetId?: number;
	handoffUrl?: string;
	jobs: Record<number, { activeJob?: SyncJob; error?: unknown }>;
	onCancelJob: (target: GitHubAccessTarget, job: SyncJob) => void;
	onConfigure: (targetId: number | undefined, input: GitHubAccessConfiguration) => Promise<void>;
	onRenew: (targetId: number, installationId: number) => void;
	onPreview: (targetId: number) => void;
	onApprove: (target: GitHubAccessTarget) => void;
	onReconcile: (targetId: number) => void;
	onPause: (targetId: number, paused: boolean) => void;
	onEnd: (targetId: number) => void;
	onDecide: (targetId: number, githubUserId: number, decision: GitHubAccessDecision) => void;
}

const statusLabels = {
	DRAFT: "Awaiting approval",
	ACTIVE: "Managing approved access",
	ENDING: "Ending management",
	ENDED: "Management ended",
};
const externalLabels = {
	ABSENT: "No access confirmed",
	PENDING: "Invitation pending",
	ACTIVE: "Active membership",
	WAITING_ORGANIZATION: "Accept organization membership first",
	PROTECTED: "Protected access",
};

/** Setup and policy belong to the owner; operational controls are also available to administrators. */
export function WorkspaceGithubAccessPage(props: WorkspaceGithubAccessPageProps) {
	const { state, workspaceSlug, isOwner, busy } = props;
	const [editing, setEditing] = useState<GitHubAccessTarget | "new" | null>(null);
	const [ending, setEnding] = useState<GitHubAccessTarget | null>(null);
	const [decision, setDecision] = useState<{
		targetId: number;
		userId: number;
		name: string;
	} | null>(null);
	const [reason, setReason] = useState("");
	const id = useId();
	const showBusy = useSpinDelay(busy, { delay: 1000, minDuration: 500 });
	return (
		<PageLayout>
			<PageHeader
				icon={<ShieldCheck />}
				title="GitHub access"
				description="Approve and track access across organizations and teams without losing pending removals."
				actions={
					<Link
						className={buttonVariants({ variant: "outline" })}
						to="/w/$workspaceSlug/admin/access"
						params={{ workspaceSlug }}
					>
						Access requests
					</Link>
				}
			/>
			{state.status === "loading" ? (
				<div role="group" aria-label="Loading GitHub access" className="space-y-6">
					<Skeleton className="h-24 w-full" />
					<Skeleton className="h-64 w-full" />
					<Skeleton className="h-64 w-full" />
				</div>
			) : state.status === "error" ? (
				<QueryErrorAlert
					title="Couldn't load GitHub access"
					error={state.error}
					onRetry={state.onRetry}
				/>
			) : (
				<div className="space-y-6">
					<p className="text-sm text-muted-foreground">
						A separate Hephaestus Access App and a GitHub organization owner's consent are required.
						The normal repository integration gains no membership permissions. GitLab, Slack and
						Outline provisioning is not supported.
					</p>
					{!state.data.configured && (
						<p className="rounded-lg border p-4 text-sm">
							Ask your instance operator to configure Hephaestus Access. Existing external
							memberships are not revoked by missing App configuration.
						</p>
					)}
					{state.directoryError !== undefined && (
						<QueryErrorAlert
							title="Couldn't load approved directory groups"
							error={state.directoryError}
						/>
					)}
					{isOwner && (
						<div className="flex flex-wrap gap-2">
							<Button disabled={busy || !state.data.configured} onClick={() => setEditing("new")}>
								Add GitHub target
							</Button>
							{state.data.installationUrl && (
								<a
									className={buttonVariants({ variant: "outline" })}
									href={state.data.installationUrl}
									target="_blank"
									rel="noreferrer"
								>
									Install Access App
								</a>
							)}
							{state.approvedGroups.length === 0 && (
								<p className="w-full text-sm text-muted-foreground">
									Access requests work without a directory. Directory eligibility is optional.
								</p>
							)}
						</div>
					)}
					{props.handoffUrl && (
						<section
							className="space-y-2 rounded-lg border p-4"
							aria-label="Organization owner approval link"
						>
							<h2 className="font-semibold">Share this one-use approval link</h2>
							<p className="text-sm text-muted-foreground">
								Send it privately to a GitHub organization owner. It expires in ten minutes. They
								sign in as themselves and do not need workspace membership. Do not put this link in
								tickets or logs.
							</p>
							<Field>
								<FieldLabel htmlFor={`${id}-handoff`}>Approval link</FieldLabel>
								<Input
									id={`${id}-handoff`}
									readOnly
									value={props.handoffUrl}
									onFocus={(event) => event.target.select()}
								/>
							</Field>
						</section>
					)}
					{editing && isOwner && (
						<GithubTargetForm
							key={editing === "new" ? "new" : editing.id}
							target={editing === "new" ? undefined : editing}
							groups={state.approvedGroups}
							busy={props.savingPolicy}
							onCancel={() => setEditing(null)}
							onSave={async (input) => {
								await props.onConfigure(editing === "new" ? undefined : editing.id, input);
								setEditing(null);
							}}
						/>
					)}
					{state.data.targets.length === 0 ? (
						<div className="rounded-lg border border-dashed p-8 text-center">
							<h2 className="font-semibold">No GitHub targets yet</h2>
							<p className="mt-2 text-sm text-muted-foreground">
								Each organization or team has its own approval, inventory and reconciliation status.
							</p>
						</div>
					) : (
						state.data.targets.map((target) => (
							<section
								key={target.id}
								className="space-y-4 rounded-lg border p-4 sm:p-6"
								aria-label={`${target.organization} ${target.team ?? "organization"} access`}
							>
								<div className="flex flex-wrap items-start justify-between gap-3">
									<div>
										<h2 className="break-all text-lg font-semibold">
											{target.organization}
											{target.team ? ` / ${target.team}` : " / Organization"}
										</h2>
										<p className="text-sm text-muted-foreground">
											{target.source === "REQUEST"
												? "Approved access requests"
												: "Approved directory groups"}{" "}
											· {statusLabels[target.status]} ·{" "}
											{target.paused
												? "Writes paused — access remains"
												: "Writes may proceed after current checks"}
										</p>
									</div>
									<p className="text-sm">
										{target.authorityHeld ? "Scope reserved" : "No held authority"}
									</p>
								</div>
								<p className="text-sm text-muted-foreground">
									Approved groups: {target.approvedGroupIds.join(", ") || "None"}.{" "}
									{target.lastConfirmedAt && (
										<>
											Last checked <RelativeTime value={target.lastConfirmedAt} />.
										</>
									)}
								</p>
								<ActiveJobProgress job={props.jobs[target.id]?.activeJob} />
								{props.jobs[target.id]?.error !== undefined && (
									<QueryErrorAlert
										title="Couldn't read target job status"
										error={props.jobs[target.id]?.error}
									/>
								)}
								{props.jobs[target.id]?.activeJob && (
									<Button
										variant="outline"
										disabled={busy}
										onClick={() => {
											const job = props.jobs[target.id]?.activeJob;
											if (job) props.onCancelJob(target, job);
										}}
									>
										Cancel current pass
									</Button>
								)}
								{target.failureReason && (
									<p className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm">
										{target.failureReason}
										{target.retryAt && (
											<>
												{" "}
												Retry after <RelativeTime value={target.retryAt} />.
											</>
										)}
									</p>
								)}
								<div className="flex flex-wrap gap-2">
									{target.status !== "ENDED" && (
										<>
											<Button
												variant="outline"
												disabled={busy}
												onClick={() => props.onReconcile(target.id)}
											>
												Reconcile / retry
											</Button>
											<Button
												variant="outline"
												disabled={busy}
												onClick={() => props.onPause(target.id, !target.paused)}
											>
												{target.paused ? "Resume writes" : "Pause writes"}
											</Button>
											{isOwner && (
												<>
													<Button
														variant="outline"
														disabled={busy || !target.authorized}
														onClick={() => props.onPreview(target.id)}
													>
														Preview target
													</Button>
													{target.status !== "ENDING" && (
														<Button
															variant="outline"
															disabled={busy}
															onClick={() => setEditing(target)}
														>
															Edit policy
														</Button>
													)}
													<Button
														variant="outline"
														disabled={busy}
														onClick={() => props.onRenew(target.id, target.installationId)}
													>
														New approval link
													</Button>
													<Button
														variant="outline"
														disabled={busy}
														onClick={() => setEnding(target)}
													>
														End management
													</Button>
												</>
											)}
										</>
									)}
									{showBusy && props.changingTargetId === target.id && (
										<p className="text-sm text-muted-foreground">Updating GitHub access…</p>
									)}
								</div>
								{isOwner && target.status === "ENDING" && (
									<form
										className="flex flex-wrap items-end gap-2"
										onSubmit={(event) => {
											event.preventDefault();
											const value = new FormData(event.currentTarget).get("installation");
											if (typeof value === "string") props.onRenew(target.id, Number(value));
										}}
									>
										<Field className="w-full sm:w-64">
											<FieldLabel htmlFor={`${id}-installation-${target.id}`}>
												Reinstalled App installation ID
											</FieldLabel>
											<Input
												id={`${id}-installation-${target.id}`}
												name="installation"
												type="number"
												min="1"
												required
												defaultValue={target.installationId}
											/>
										</Field>
										<Button variant="outline" disabled={busy}>
											Restore authorization
										</Button>
									</form>
								)}
								{target.preview && (
									<details className="rounded-md border p-3" open>
										<summary className="cursor-pointer font-medium">
											Latest preview: {target.preview.eligiblePeople} eligible people,{" "}
											{target.preview.awaitingIdentity} awaiting GitHub links
										</summary>
										<p className="my-2 text-sm">
											{target.preview.unlinkedInvitations} invitations could not be matched to a
											native identity and remain unmanaged. Existing access below is not adopted
											automatically.
										</p>
										<ul className="list-disc space-y-1 pl-5 text-sm">
											{target.preview.inventory.map((member) => (
												<li key={member.githubUserId}>
													{member.login} — {externalLabels[member.state]}
													{member.explanation && `: ${member.explanation}`}
												</li>
											))}
										</ul>
										{isOwner && target.status !== "ENDING" && target.status !== "ENDED" && (
											<Button
												className="mt-3"
												disabled={busy || !target.authorized}
												onClick={() => props.onApprove(target)}
											>
												Approve this exact preview
											</Button>
										)}
									</details>
								)}
								{target.members.length === 0 ? (
									<p className="text-sm text-muted-foreground">
										No linked memberships have been reconciled. After preview approval, reconcile to
										prepare eligible invitations and identify existing access that needs adoption.
									</p>
								) : (
									<Table>
										<TableHeader>
											<TableRow>
												<TableHead>Developer</TableHead>
												<TableHead>Confirmed GitHub state</TableHead>
												<TableHead>Management</TableHead>
												<TableHead>Next step</TableHead>
												{isOwner && <TableHead>Owner decision</TableHead>}
											</TableRow>
										</TableHeader>
										<TableBody>
											{target.members.map((member) => (
												<TableRow key={member.githubUserId}>
													<TableCell>
														<p>{member.displayName}</p>
														<p className="text-xs text-muted-foreground">
															{member.githubLogin ?? `GitHub ID ${member.githubUserId}`}
														</p>
													</TableCell>
													<TableCell>
														{member.externalState
															? externalLabels[member.externalState]
															: "Not confirmed"}
													</TableCell>
													<TableCell>
														{member.revocationRequested
															? "Removal pending"
															: member.manualException
																? "Manual exception"
																: member.managed
																	? "Managed"
																	: "Unmanaged"}
													</TableCell>
													<TableCell className="max-w-sm whitespace-normal">
														{member.blocker ?? "No action needed"}
														{member.exceptionReason && <p>{member.exceptionReason}</p>}
													</TableCell>
													{isOwner && (
														<TableCell>
															<div className="flex flex-wrap gap-2">
																{!member.managed &&
																	!member.manualException &&
																	(member.externalState === "ACTIVE" ||
																		member.externalState === "PENDING") && (
																		<Button
																			size="sm"
																			variant="outline"
																			disabled={busy}
																			onClick={() =>
																				props.onDecide(target.id, member.githubUserId, {
																					decision: "ADOPT",
																				})
																			}
																		>
																			Adopt access
																		</Button>
																	)}
																{member.manualException ? (
																	<Button
																		size="sm"
																		variant="outline"
																		disabled={busy}
																		onClick={() =>
																			props.onDecide(target.id, member.githubUserId, {
																				decision: "RESET_EXCEPTION",
																			})
																		}
																	>
																		Reset exception
																	</Button>
																) : (
																	<Button
																		size="sm"
																		variant="outline"
																		disabled={busy}
																		onClick={() => {
																			setReason("");
																			setDecision({
																				targetId: target.id,
																				userId: member.githubUserId,
																				name: member.displayName,
																			});
																		}}
																	>
																		Keep unmanaged
																	</Button>
																)}
															</div>
														</TableCell>
													)}
												</TableRow>
											))}
										</TableBody>
									</Table>
								)}
								{target.actions.length > 0 && (
									<details>
										<summary className="cursor-pointer text-sm font-medium">
											Recent action history
										</summary>
										<ul className="mt-2 space-y-2 text-sm">
											{target.actions.map((action) => (
												<li key={action.id}>
													{action.type === "GRANT" ? "Grant" : "Remove"} GitHub ID{" "}
													{action.githubUserId}: {action.status.toLowerCase().replaceAll("_", " ")},
													policy {action.configurationVersion}
													{action.failureReason && ` — ${action.failureReason}`}
												</li>
											))}
										</ul>
									</details>
								)}
							</section>
						))
					)}
				</div>
			)}
			<AlertDialog
				open={ending !== null}
				onOpenChange={(open) => {
					if (!open) setEnding(null);
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>End GitHub management?</AlertDialogTitle>
						<AlertDialogDescription>
							Managed access will be queued for removal. Authority and credentials remain until
							GitHub confirms removal. Paused writes must resume to complete teardown; unrelated
							unmanaged access is not removed.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								if (ending) props.onEnd(ending.id);
								setEnding(null);
							}}
						>
							End management
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
			<AlertDialog
				open={decision !== null}
				onOpenChange={(open) => {
					if (!open) setDecision(null);
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Keep {decision?.name}'s access unmanaged?</AlertDialogTitle>
						<AlertDialogDescription>
							Hephaestus will stop managing this access, not remove it. You accept responsibility
							for any remaining GitHub membership, including an unconfirmed prior grant.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<Field>
						<FieldLabel htmlFor={`${id}-exception`}>Reason for the exception</FieldLabel>
						<Textarea
							id={`${id}-exception`}
							value={reason}
							onChange={(event) => setReason(event.target.value)}
							maxLength={512}
						/>
					</Field>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							disabled={!reason.trim() || busy}
							onClick={() => {
								if (decision)
									props.onDecide(decision.targetId, decision.userId, {
										decision: "MANUAL_EXCEPTION",
										reason,
									});
								setDecision(null);
							}}
						>
							Keep unmanaged
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</PageLayout>
	);
}

function GithubTargetForm({
	target,
	groups,
	busy,
	onSave,
	onCancel,
}: {
	target?: GitHubAccessTarget;
	groups: string[];
	busy: boolean;
	onSave: (input: GitHubAccessConfiguration) => Promise<void>;
	onCancel: () => void;
}) {
	const id = useId();
	const [organization, setOrganization] = useState(target?.organization ?? "");
	const [team, setTeam] = useState(target?.team ?? "");
	const [installation, setInstallation] = useState(String(target?.installationId ?? ""));
	const [selected, setSelected] = useState(target?.draftGroupIds ?? []);
	const [source, setSource] = useState<GitHubAccessConfiguration["source"]>(
		target?.source ?? "REQUEST",
	);
	const [error, setError] = useState<unknown>();
	const showBusy = useSpinDelay(busy, { delay: 1000, minDuration: 500 });
	return (
		<form
			className="space-y-4 rounded-lg border p-4"
			onSubmit={(event) => {
				event.preventDefault();
				setError(undefined);
				void onSave({
					organization: organization.trim(),
					team: team.trim() || undefined,
					installationId: Number(installation),
					source,
					groupIds: source === "DIRECTORY" ? selected : [],
				}).catch(setError);
			}}
		>
			<h2 className="font-semibold">{target ? "Revise GitHub target" : "Add GitHub target"}</h2>
			<FieldGroup>
				<Field orientation="horizontal">
					<Checkbox
						id={`${id}-directory`}
						checked={source === "DIRECTORY"}
						disabled={target?.authorityHeld === true || groups.length === 0}
						onCheckedChange={(checked) => setSource(checked ? "DIRECTORY" : "REQUEST")}
					/>
					<FieldContent>
						<FieldLabel htmlFor={`${id}-directory`}>
							Use directory eligibility instead of access requests
						</FieldLabel>
						<FieldDescription>
							By default, only approved, unexpired requests are delivered to this workspace's GitHub
							organization. A team target includes only requests approved for that synchronized
							team.
						</FieldDescription>
					</FieldContent>
				</Field>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel htmlFor={`${id}-org`}>Organization login</FieldLabel>
						<FieldDescription>
							Use the login, not a GitHub URL. A reserved scope cannot be changed; end it before
							configuring a different target.
						</FieldDescription>
					</FieldContent>
					<Input
						id={`${id}-org`}
						className="w-full @md/field-group:w-56"
						required
						maxLength={100}
						value={organization}
						readOnly={target?.authorityHeld}
						onChange={(event) => setOrganization(event.target.value)}
					/>
				</Field>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel htmlFor={`${id}-team`}>Team slug (optional)</FieldLabel>
						<FieldDescription>Empty means organization-wide membership.</FieldDescription>
					</FieldContent>
					<Input
						id={`${id}-team`}
						className="w-full @md/field-group:w-56"
						maxLength={100}
						value={team}
						readOnly={target?.authorityHeld}
						onChange={(event) => setTeam(event.target.value)}
					/>
				</Field>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel htmlFor={`${id}-installation`}>Access App installation ID</FieldLabel>
						<FieldDescription>
							The organization owner supplies this ID after installing the separate App.
						</FieldDescription>
					</FieldContent>
					<Input
						id={`${id}-installation`}
						className="w-full @md/field-group:w-56"
						type="number"
						min="1"
						required
						value={installation}
						onChange={(event) => setInstallation(event.target.value)}
					/>
				</Field>
			</FieldGroup>
			{source === "DIRECTORY" && (
				<fieldset className="space-y-2">
					<legend className="mb-2 text-sm font-medium">Approved directory groups</legend>
					{groups.map((group) => (
						<Field key={group} orientation="horizontal">
							<Checkbox
								id={`${id}-${group}`}
								checked={selected.includes(group)}
								onCheckedChange={(checked) =>
									setSelected(
										checked ? [...selected, group] : selected.filter((value) => value !== group),
									)
								}
							/>
							<FieldLabel htmlFor={`${id}-${group}`} className="break-all">
								{group}
							</FieldLabel>
						</Field>
					))}
				</fieldset>
			)}
			<p className="text-sm text-muted-foreground">
				Saving requires fresh organization-owner consent and a new preview approval. Previously
				managed access and pending removals remain recorded. Native scopes cannot be changed after
				authority is acquired.
			</p>
			{error !== undefined && <QueryErrorAlert title="Couldn't save this target" error={error} />}
			<div className="flex flex-wrap gap-2">
				<Button type="submit" disabled={busy || (source === "DIRECTORY" && selected.length === 0)}>
					{showBusy && <Spinner />}
					{busy ? "Saving…" : "Save and create approval link"}
				</Button>
				<Button type="button" variant="outline" onClick={onCancel}>
					Cancel
				</Button>
			</div>
		</form>
	);
}
