import { Link } from "@tanstack/react-router";
import { Network, ShieldCheck } from "lucide-react";
import { useId, useState } from "react";
import { useSpinDelay } from "spin-delay";

import type {
	DirectoryConfiguration,
	DirectoryEvidence,
	DirectoryMember,
	DirectoryPolicy,
	DirectorySource,
	SyncJob,
} from "@/api/types.gen";
import { ActiveJobProgress } from "@/components/admin/integrations/ActiveJobProgress";
import {
	SyncJobsTable,
	type SyncJobsTableProps,
} from "@/components/admin/integrations/SyncJobsTable";
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
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
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

export type DirectoryPageState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			policy?: DirectoryPolicy;
			sources: DirectorySource[];
			sourceError?: unknown;
			onRetrySources: () => void;
	  };

export interface WorkspaceDirectoryAccessPageProps {
	workspaceSlug: string;
	state: DirectoryPageState;
	isOwner: boolean;
	pendingAction?: "save" | "preview" | "approve" | "reconcile" | "status" | "adopt" | "cancel";
	activeJob?: SyncJob;
	jobs: SyncJobsTableProps;
	onConfigure: (configuration: DirectoryConfiguration) => Promise<void>;
	onPreview: () => void;
	onApprove: (version: number) => void;
	onReconcile: () => void;
	onStatusChange: (status: "ACTIVE" | "PAUSED" | "ENDED") => void;
	onAdopt: (accountId: number) => void;
	onCancel: () => void;
}

const policyLabels = {
	DRAFT: "Awaiting owner approval",
	ACTIVE: "Active",
	PAUSED: "New access paused",
	ENDED: "Management ended",
};
const healthLabels = {
	UNVERIFIED: "Not yet verified",
	HEALTHY: "Last read complete",
	FAILED: "Last read failed",
};

/** Owner policy controls and administrator operations are distinct; all data arrives from the route. */
export function WorkspaceDirectoryAccessPage({
	workspaceSlug,
	state,
	isOwner,
	pendingAction,
	activeJob,
	jobs,
	onConfigure,
	onPreview,
	onApprove,
	onReconcile,
	onStatusChange,
	onAdopt,
	onCancel,
}: WorkspaceDirectoryAccessPageProps) {
	const id = useId();
	const [adopting, setAdopting] = useState<DirectoryMember | null>(null);
	const [ending, setEnding] = useState(false);
	const showBusy = useSpinDelay(pendingAction !== undefined, { delay: 1000, minDuration: 500 });
	const policy = state.status === "ready" ? state.policy : undefined;
	const busy = pendingAction !== undefined;
	const reading = activeJob !== undefined;
	return (
		<PageLayout>
			<PageHeader
				icon={<Network />}
				title="Directory access"
				description="Keep workspace access aligned with explicitly approved organizational groups."
				actions={
					<Button
						variant="outline"
						render={<Link to="/w/$workspaceSlug/admin/members" params={{ workspaceSlug }} />}
					>
						Manage manual access
					</Button>
				}
			/>
			{state.status === "loading" ? (
				<div className="space-y-6" role="group" aria-label="Loading directory access">
					<Skeleton className="h-24 w-full" />
					<Skeleton className="h-64 w-full" />
					<Skeleton className="h-40 w-full" />
				</div>
			) : state.status === "error" ? (
				<QueryErrorAlert
					title="Couldn't load directory access"
					error={state.error}
					onRetry={state.onRetry}
				/>
			) : (
				<div className="space-y-8">
					{policy ? (
						<section className="space-y-3" aria-label="Directory policy status">
							<div className="flex flex-wrap gap-x-8 gap-y-2 text-sm">
								<p>
									<strong>Policy:</strong> {policyLabels[policy.status]}
								</p>
								<p>
									<strong>Source health:</strong> {healthLabels[policy.health]}
								</p>
							</div>
							<p className="break-all text-sm text-muted-foreground">
								Exact issuer: <code>{policy.issuer}</code>
							</p>
							{policy.blockers.length > 0 && (
								<ul className="list-disc space-y-1 pl-5 text-sm">
									{policy.blockers.map((blocker) => (
										<li key={blocker}>{blocker}</li>
									))}
								</ul>
							)}
							{policy.failureReason && (
								<p className="text-sm text-destructive">{policy.failureReason}</p>
							)}
							<ActiveJobProgress job={activeJob} />
							<div className="flex flex-wrap gap-2">
								{policy.status !== "DRAFT" && policy.status !== "ENDED" && (
									<Button variant="outline" disabled={busy || reading} onClick={onReconcile}>
										{showBusy && pendingAction === "reconcile" && <Spinner />}
										{showBusy && pendingAction === "reconcile" ? "Starting read…" : "Reconcile now"}
									</Button>
								)}
								{activeJob && (
									<Button
										variant="outline"
										disabled={busy || activeJob.cancelRequested}
										onClick={onCancel}
									>
										{activeJob.cancelRequested ? "Cancellation requested" : "Cancel read"}
									</Button>
								)}
								{isOwner && policy.status === "ACTIVE" && (
									<Button
										variant="outline"
										disabled={busy}
										onClick={() => onStatusChange("PAUSED")}
									>
										Pause new access
									</Button>
								)}
								{isOwner && policy.status === "PAUSED" && (
									<Button
										variant="outline"
										disabled={busy}
										onClick={() => onStatusChange("ACTIVE")}
									>
										Resume new access
									</Button>
								)}
								{isOwner && policy.status !== "ENDED" && (
									<Button variant="destructive" disabled={busy} onClick={() => setEnding(true)}>
										End management
									</Button>
								)}
							</div>
						</section>
					) : (
						<section className="space-y-2" aria-label="Directory setup">
							<h2 className="text-lg font-semibold">No directory policy</h2>
							<p className="text-sm text-muted-foreground">
								{isOwner
									? "Choose an operator-approved source, preview the changes, then explicitly approve access."
									: "Ask a workspace owner to configure and approve directory access. Administrators can inspect and reconcile an approved policy."}
							</p>
						</section>
					)}
					{isOwner && (
						<section className="space-y-4" aria-labelledby={`${id}-configuration`}>
							<h2 id={`${id}-configuration`} className="text-lg font-semibold">
								{policy?.status === "ENDED" ? "Start a new policy" : "Source and eligibility"}
							</h2>
							{state.sourceError ? (
								<QueryErrorAlert
									title="Couldn't load approved sources"
									error={state.sourceError}
									onRetry={state.onRetrySources}
								/>
							) : state.sources.length === 0 ? (
								<p className="text-sm text-muted-foreground">
									An instance administrator must approve a Keycloak source and its eligible group
									IDs in Login providers. No directory credentials are needed until that approval
									exists.
								</p>
							) : (
								<DirectoryConfigurationForm
									key={`${policy?.connectionId ?? "new"}:${policy?.configurationVersion ?? 0}`}
									policy={policy}
									sources={state.sources}
									isSaving={pendingAction === "save"}
									disabled={busy && pendingAction !== "save"}
									onSave={onConfigure}
								/>
							)}
						</section>
					)}
					{policy && policy.status !== "ENDED" && (
						<section className="space-y-4" aria-labelledby={`${id}-evidence`}>
							<div className="flex flex-wrap items-center justify-between gap-3">
								<h2 id={`${id}-evidence`} className="text-lg font-semibold">
									Evidence and approval
								</h2>
								{isOwner && (
									<Button variant="outline" disabled={busy || reading} onClick={onPreview}>
										{showBusy && pendingAction === "preview" && <Spinner />}Preview saved
										configuration
									</Button>
								)}
							</div>
							<div className="grid gap-4 md:grid-cols-2">
								<EvidenceSummary
									title="Approved policy evidence"
									evidence={policy.approvedEvidence}
								/>
								<EvidenceSummary title="Draft preview" evidence={policy.previewEvidence} />
							</div>
							<p className="text-sm text-muted-foreground">
								Only a complete, fresh read can grant access. Manual access, ownership and
								suspensions are never silently adopted. An outage is not evidence that someone has
								left.
							</p>
							{isOwner && (
								<Button
									disabled={busy || reading || !policy.previewEvidence?.fresh}
									onClick={() => onApprove(policy.configurationVersion)}
								>
									<ShieldCheck aria-hidden />
									Approve this preview
								</Button>
							)}
						</section>
					)}
					{policy && (
						<section className="space-y-4" aria-labelledby={`${id}-members`}>
							<h2 id={`${id}-members`} className="text-lg font-semibold">
								Access inventory
							</h2>
							<p className="text-sm text-muted-foreground">
								Eligibility reflects the displayed evidence, not a claim that a stale read is
								current. Adoption deliberately changes non-owner access to a directory-managed
								Member role; suspension stays in place.
							</p>
							{policy.members.length === 0 ? (
								<p className="text-sm text-muted-foreground">
									No account memberships or linked eligible accounts to display.
								</p>
							) : (
								<Table>
									<TableHeader>
										<TableRow>
											<TableHead>Account</TableHead>
											<TableHead>Current access</TableHead>
											<TableHead>Eligibility</TableHead>
											<TableHead>Proposed change</TableHead>
											{isOwner && <TableHead>Management</TableHead>}
										</TableRow>
									</TableHeader>
									<TableBody>
										{policy.members.map((member) => (
											<TableRow key={member.accountId}>
												<TableCell>
													<p className="font-medium">{member.displayName}</p>
													<p className="text-xs text-muted-foreground">
														Account {member.accountId}
													</p>
												</TableCell>
												<TableCell>
													{member.suspended ? "Suspended" : (member.role ?? "Not a member")}
													<p className="text-xs text-muted-foreground">
														{member.source === "DIRECTORY"
															? "Directory managed"
															: member.source
																? "Managed outside this policy"
																: "Not granted"}
													</p>
												</TableCell>
												<TableCell>
													{member.eligible ? "Eligible in this read" : "Not confirmed eligible"}
												</TableCell>
												<TableCell>
													{
														{
															ADD: "Add member",
															REMOVE: "Remove managed access",
															PROTECTED: "Preserve manual decision",
															UNCHANGED: "No change",
														}[member.change]
													}
												</TableCell>
												{isOwner && (
													<TableCell>
														{member.adoptable && policy.status === "ACTIVE" && (
															<Button
																variant="outline"
																size="sm"
																disabled={busy || !policy.approvedEvidence?.fresh}
																aria-label={`Adopt directory access for ${member.displayName}`}
																onClick={() => setAdopting(member)}
															>
																Adopt
															</Button>
														)}
													</TableCell>
												)}
											</TableRow>
										))}
									</TableBody>
								</Table>
							)}
						</section>
					)}
					{policy && (
						<section className="space-y-4" aria-labelledby={`${id}-jobs`}>
							<h2 id={`${id}-jobs`} className="text-lg font-semibold">
								Directory read history
							</h2>
							<SyncJobsTable {...jobs} />
						</section>
					)}
				</div>
			)}
			<AlertDialog open={ending} onOpenChange={setEnding}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>End directory management?</AlertDialogTitle>
						<AlertDialogDescription>
							Directory-managed access will be removed immediately. Owners, manually granted access
							and suspension records remain. Starting again requires new credentials, a fresh
							preview and owner approval.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Keep management</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							onClick={() => {
								onStatusChange("ENDED");
								setEnding(false);
							}}
						>
							End management
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
			<AlertDialog
				open={adopting !== null}
				onOpenChange={(open) => {
					if (!open) setAdopting(null);
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Adopt {adopting?.displayName}'s access?</AlertDialogTitle>
						<AlertDialogDescription>
							This explicitly replaces their current non-owner role with a directory-managed Member
							role. Confirmed departure will remove it. An existing suspension will not be lifted.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Keep manual access</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								if (adopting) onAdopt(adopting.accountId);
								setAdopting(null);
							}}
						>
							Adopt access
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</PageLayout>
	);
}

function EvidenceSummary({ title, evidence }: { title: string; evidence?: DirectoryEvidence }) {
	return (
		<div className="space-y-2 rounded-lg border p-4">
			<h3 className="font-medium">{title}</h3>
			{evidence ? (
				<>
					<p className="text-sm">
						{evidence.fresh ? "Fresh and usable" : "Not usable for new access"}
					</p>
					<p className="text-sm text-muted-foreground">
						Read started <RelativeTime value={evidence.startedAt} />.
					</p>
					<p className="text-sm">
						{evidence.eligiblePeople} eligible people · {evidence.awaitingIdentity} awaiting a
						verified identity
					</p>
					<p className="text-sm">
						{evidence.additions} additions · {evidence.removals} confirmed removals
					</p>
					<ul className="text-sm text-muted-foreground">
						{Object.entries(evidence.groupNames).map(([id, name]) => (
							<li key={id}>
								{name} <code className="break-all">({id})</code>
							</li>
						))}
					</ul>
				</>
			) : (
				<p className="text-sm text-muted-foreground">No complete read has been recorded.</p>
			)}
		</div>
	);
}

/** Form drafts are local; the parent keys this form on the persisted configuration version. */
function DirectoryConfigurationForm({
	policy,
	sources,
	isSaving,
	disabled,
	onSave,
}: {
	policy?: DirectoryPolicy;
	sources: DirectorySource[];
	isSaving: boolean;
	disabled: boolean;
	onSave: (configuration: DirectoryConfiguration) => Promise<void>;
}) {
	const id = useId();
	const [registrationId, setRegistrationId] = useState(
		policy?.registrationId ?? sources[0]?.registrationId ?? "",
	);
	const [groupIds, setGroupIds] = useState<string[]>(policy?.draftGroupIds ?? []);
	const [clientId, setClientId] = useState("");
	const [clientSecret, setClientSecret] = useState("");
	const source = sources.find((candidate) => candidate.registrationId === registrationId);
	const needsCredentials = !policy || policy.status === "ENDED";
	const showSaving = useSpinDelay(isSaving, { delay: 1000, minDuration: 500 });
	const items = sources.map((item) => ({ value: item.registrationId, label: item.displayName }));
	return (
		<form
			className="space-y-4"
			onSubmit={(event) => {
				event.preventDefault();
				const credentials = clientId || clientSecret ? { clientId, clientSecret } : undefined;
				void onSave({ registrationId, groupIds, ...(credentials ? { credentials } : {}) })
					.then(() => {
						setClientId("");
						setClientSecret("");
					})
					.catch(() => undefined);
			}}
		>
			<FieldGroup>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel htmlFor={`${id}-source`}>Approved source</FieldLabel>
						<FieldDescription>
							End management before changing the issuer. Selecting a source never grants access by
							itself.
						</FieldDescription>
					</FieldContent>
					<Select
						items={items}
						value={registrationId}
						disabled={disabled || isSaving || Boolean(policy && policy.status !== "ENDED")}
						onValueChange={(value) => {
							if (value) {
								setRegistrationId(value);
								setGroupIds([]);
							}
						}}
					>
						<SelectTrigger id={`${id}-source`} className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-label="Approved source">
							{items.map((item) => (
								<SelectItem key={item.value} value={item.value}>
									{item.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>
				{source && (
					<p className="break-all text-sm text-muted-foreground">
						Directory issuer: <code>{source.issuer}</code>
					</p>
				)}
				<fieldset className="space-y-3" disabled={disabled || isSaving}>
					<legend className="mb-3 text-sm font-medium">Eligible groups</legend>
					{source?.groupIds.map((groupId) => (
						<Field key={groupId} orientation="horizontal">
							<Checkbox
								id={`${id}-${groupId}`}
								checked={groupIds.includes(groupId)}
								onCheckedChange={(checked) =>
									setGroupIds((current) =>
										checked ? [...current, groupId] : current.filter((value) => value !== groupId),
									)
								}
							/>
							<FieldLabel htmlFor={`${id}-${groupId}`} className="break-all">
								{policy?.previewEvidence?.groupNames[groupId] ?? groupId}
							</FieldLabel>
						</Field>
					))}
				</fieldset>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel htmlFor={`${id}-client`}>Read-only directory client ID</FieldLabel>
						<FieldDescription>
							Use a dedicated client, not the sign-in client's credentials. Leave both credential
							fields empty to keep the current credentials.
						</FieldDescription>
					</FieldContent>
					<Input
						id={`${id}-client`}
						className="w-full @md/field-group:w-56"
						value={clientId}
						onChange={(event) => setClientId(event.target.value)}
						required={needsCredentials || clientSecret.length > 0}
						autoComplete="off"
						disabled={disabled || isSaving}
					/>
				</Field>
				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel htmlFor={`${id}-secret`}>Directory client secret</FieldLabel>
						<FieldDescription>
							Encrypted at rest and never returned to the browser.
						</FieldDescription>
					</FieldContent>
					<Input
						id={`${id}-secret`}
						type="password"
						className="w-full @md/field-group:w-56"
						value={clientSecret}
						onChange={(event) => setClientSecret(event.target.value)}
						required={needsCredentials || clientId.length > 0}
						autoComplete="new-password"
						disabled={disabled || isSaving}
					/>
				</Field>
			</FieldGroup>
			<Button type="submit" disabled={disabled || isSaving || !source || groupIds.length === 0}>
				{showSaving && <Spinner />}
				{showSaving ? "Saving…" : "Save configuration"}
			</Button>
			<p className="text-sm text-muted-foreground">
				Saving invalidates earlier previews. Approved groups continue to govern reconciliation until
				the owner approves a new preview.
			</p>
		</form>
	);
}
