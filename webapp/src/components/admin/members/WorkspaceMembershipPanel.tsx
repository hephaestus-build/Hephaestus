import { useState } from "react";

import type { AssignRoleRequest, WorkspaceAccountMembership } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
	AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldLabel } from "@/components/ui/field";
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

export interface WorkspaceMembershipPanelProps {
	members: WorkspaceAccountMembership[];
	isOwner: boolean;
	isLoading: boolean;
	error?: unknown;
	isSaving: boolean;
	onRetry: () => void;
	onAssign: (request: AssignRoleRequest) => Promise<void>;
	onSuspend: (accountId: number) => void;
}

const roleItems = [
	{ value: "MEMBER", label: "Member" },
	{ value: "ADMIN", label: "Administrator" },
	{ value: "OWNER", label: "Owner" },
] satisfies { value: AssignRoleRequest["role"]; label: string }[];

const sourceLabels = {
	MANUAL: "Granted manually",
	MIGRATED: "Retained during upgrade",
	SCM: "Synced from source control",
	DIRECTORY: "Directory managed",
};

export function WorkspaceMembershipPanel({
	members,
	isOwner,
	isLoading,
	error,
	isSaving,
	onRetry,
	onAssign,
	onSuspend,
}: WorkspaceMembershipPanelProps) {
	const [editing, setEditing] = useState<WorkspaceAccountMembership | null>(null);
	const [open, setOpen] = useState(false);
	return (
		<section className="space-y-4" aria-labelledby="workspace-access-heading">
			<div className="flex flex-wrap items-center justify-between gap-3">
				<div className="space-y-1">
					<h2 id="workspace-access-heading" className="text-xl font-semibold">
						Workspace access
					</h2>
					<p className="text-sm text-muted-foreground">
						One membership per account, independent of connected source-control profiles.
					</p>
				</div>
				<Button
					disabled={isLoading || Boolean(error) || isSaving}
					onClick={() => {
						setEditing(null);
						setOpen(true);
					}}
				>
					Add member
				</Button>
			</div>
			{error ? (
				<QueryErrorAlert error={error} title="Couldn't load workspace access" onRetry={onRetry} />
			) : isLoading ? (
				<div className="space-y-3" aria-busy="true">
					<Skeleton className="h-20 w-full" />
					<Skeleton className="h-20 w-full" />
				</div>
			) : members.length === 0 ? (
				<p className="rounded-lg border p-6 text-sm text-muted-foreground">
					No account memberships yet. Source-control contributors do not automatically hold account
					access.
				</p>
			) : (
				<ul className="divide-y rounded-lg border">
					{members.map((member) => {
						const canManage = isOwner || (member.role !== "OWNER" && member.source !== "DIRECTORY");
						return (
							<li
								key={member.accountId}
								className="flex flex-wrap items-center justify-between gap-3 p-4"
							>
								<div className="min-w-0 space-y-1">
									<p className="break-words font-medium">{member.displayName}</p>
									<p className="text-xs text-muted-foreground">
										Account {member.accountId} ·{" "}
										{member.source ? sourceLabels[member.source] : "Instance administrator"}
									</p>
								</div>
								<div className="flex flex-wrap items-center gap-2">
									<Badge variant={member.suspended ? "outline" : "secondary"}>
										{member.suspended
											? "Suspended"
											: roleItems.find((item) => item.value === member.role)?.label}
									</Badge>
									{canManage && (
										<Button
											size="sm"
											variant="outline"
											disabled={isSaving}
											aria-label={`Edit access for ${member.displayName}`}
											onClick={() => {
												setEditing(member);
												setOpen(true);
											}}
										>
											{member.suspended ? "Restore access" : "Edit role"}
										</Button>
									)}
									{canManage && !member.suspended && (
										<AlertDialog>
											<AlertDialogTrigger
												render={
													<Button
														size="sm"
														variant="outline"
														disabled={isSaving}
														aria-label={`Suspend access for ${member.displayName}`}
													/>
												}
											>
												Suspend
											</AlertDialogTrigger>
											<AlertDialogContent>
												<AlertDialogHeader>
													<AlertDialogTitle>
														Suspend {member.displayName}'s workspace access?
													</AlertDialogTitle>
													<AlertDialogDescription>
														This removes member access and prevents synchronization from restoring
														it. Public pages and instance-administrator access are unaffected.
														Provider-side permissions are managed separately. An explicit role
														assignment restores member access.
													</AlertDialogDescription>
												</AlertDialogHeader>
												<AlertDialogFooter>
													<AlertDialogCancel>Cancel</AlertDialogCancel>
													<AlertDialogAction
														render={<Button variant="destructive" />}
														onClick={() => onSuspend(member.accountId)}
													>
														Suspend access
													</AlertDialogAction>
												</AlertDialogFooter>
											</AlertDialogContent>
										</AlertDialog>
									)}
								</div>
							</li>
						);
					})}
				</ul>
			)}
			<Dialog open={open} onOpenChange={setOpen}>
				<DialogContent>
					<MembershipForm
						key={editing?.accountId ?? "new"}
						member={editing}
						isOwner={isOwner}
						isSaving={isSaving}
						onCancel={() => setOpen(false)}
						onAssign={async (request) => {
							await onAssign(request);
							setOpen(false);
						}}
					/>
				</DialogContent>
			</Dialog>
		</section>
	);
}

function MembershipForm({
	member,
	isOwner,
	isSaving,
	onCancel,
	onAssign,
}: {
	member: WorkspaceAccountMembership | null;
	isOwner: boolean;
	isSaving: boolean;
	onCancel: () => void;
	onAssign: (request: AssignRoleRequest) => Promise<void>;
}) {
	const [accountId, setAccountId] = useState(member?.accountId.toString() ?? "");
	const [role, setRole] = useState<AssignRoleRequest["role"]>(member?.role ?? "MEMBER");
	const [saveError, setSaveError] = useState(false);
	const items = isOwner ? roleItems : roleItems.filter((item) => item.value !== "OWNER");
	return (
		<form
			className="space-y-4"
			onSubmit={(event) => {
				event.preventDefault();
				const id = Number(accountId);
				if (isSaving || !Number.isSafeInteger(id) || id <= 0) return;
				setSaveError(false);
				void onAssign({ accountId: id, role }).catch(() => setSaveError(true));
			}}
		>
			<DialogHeader>
				<DialogTitle>
					{member ? `Access for ${member.displayName}` : "Add a workspace member"}
				</DialogTitle>
				<DialogDescription>
					Ask your teammate for the account ID in their User settings. Email addresses and usernames
					are not proof of account ownership.
				</DialogDescription>
			</DialogHeader>
			<Field>
				<FieldLabel htmlFor="membership-account-id">Account ID</FieldLabel>
				<Input
					id="membership-account-id"
					type="number"
					min="1"
					step="1"
					required
					readOnly={Boolean(member)}
					value={accountId}
					onChange={(event) => setAccountId(event.target.value)}
				/>
			</Field>
			<Field>
				<FieldLabel htmlFor="membership-role">Workspace role</FieldLabel>
				<Select
					value={role}
					items={items}
					onValueChange={(value) => {
						if (value) setRole(value);
					}}
				>
					<SelectTrigger id="membership-role">
						<SelectValue />
					</SelectTrigger>
					<SelectContent aria-label="Workspace role">
						{items.map((item) => (
							<SelectItem key={item.value} value={item.value}>
								{item.label}
							</SelectItem>
						))}
					</SelectContent>
				</Select>
			</Field>
			{role === "OWNER" && (
				<p className="text-sm text-muted-foreground">
					Owners can grant ownership, change access policies, and delete the workspace. Promote a
					successor before removing the final owner.
				</p>
			)}
			{member?.source === "DIRECTORY" && (
				<p className="text-sm text-muted-foreground">
					Saving makes this a manual exception. Directory synchronization will no longer remove this
					membership.
				</p>
			)}
			{saveError && (
				<p role="alert" className="text-sm text-destructive">
					Access was not updated. Check the error notification and try again.
				</p>
			)}
			<DialogFooter>
				<Button type="button" variant="outline" onClick={onCancel} disabled={isSaving}>
					Cancel
				</Button>
				<Button type="submit" disabled={isSaving || !accountId}>
					{isSaving && <Spinner />}
					{isSaving ? "Saving…" : member?.suspended ? "Restore access" : "Save access"}
				</Button>
			</DialogFooter>
		</form>
	);
}
