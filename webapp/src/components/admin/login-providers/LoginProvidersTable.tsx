import { Copy, KeyRound, Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import type { LoginProviderView } from "@/api/types.gen";
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
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { InputGroup, InputGroupButton, InputGroupInput } from "@/components/ui/input-group";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { getProviderLabel } from "@/lib/provider";

interface LoginProvidersTableProps {
	providers: LoginProviderView[];
	isLoading: boolean;
	isError: boolean;
	/** The thrown query error, surfaced through the shared error alert. */
	error?: unknown;
	/** Refetch the provider list after a failure. */
	onRetry?: () => void;
	mutatingIds: ReadonlySet<string>;
	onEdit: (provider: LoginProviderView) => void;
	onToggleEnabled: (provider: LoginProviderView, enabled: boolean) => void;
	onDelete: (provider: LoginProviderView) => void;
	/** Opens the create dialog — offered from the empty state so it is not a dead end. */
	onAdd?: () => void;
	onDirectoryGroups: (provider: LoginProviderView) => void;
}

const SKELETON_ROWS = ["a", "b", "c"];

export function LoginProvidersTable({
	providers,
	isLoading,
	isError,
	error,
	onRetry,
	mutatingIds,
	onEdit,
	onToggleEnabled,
	onDelete,
	onAdd,
	onDirectoryGroups,
}: LoginProvidersTableProps) {
	// ONE delete dialog for the whole table, driven by the row it targets — a dialog per row means N
	// portals mounted for a single, rare action.
	const [deleting, setDeleting] = useState<LoginProviderView | null>(null);

	// The delete succeeded exactly when the row leaves the list; close on that, so a *failed* delete
	// (row still present, mutation settled) keeps the dialog open to retry.
	if (
		deleting &&
		!providers.some((provider) => provider.registrationId === deleting.registrationId)
	) {
		setDeleting(null);
	}

	const isDeletePending = deleting != null && mutatingIds.has(deleting.registrationId);

	if (isError) {
		return (
			<QueryErrorAlert error={error} title="Could not load login providers" onRetry={onRetry} />
		);
	}

	if (isLoading) {
		return <LoadingRows />;
	}

	if (providers.length === 0) {
		return (
			<Empty className="border">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<KeyRound aria-hidden />
					</EmptyMedia>
					<EmptyTitle>No login providers yet</EmptyTitle>
					<EmptyDescription>
						Add one so users can sign in, or link a Slack or Outline account from their settings.
					</EmptyDescription>
				</EmptyHeader>
				{onAdd && (
					<EmptyContent>
						<Button onClick={onAdd}>
							<Plus className="size-4" aria-hidden />
							Add provider
						</Button>
					</EmptyContent>
				)}
			</Empty>
		);
	}

	const copyRedirect = async (uri: string) => {
		try {
			await navigator.clipboard.writeText(uri);
			toast.success("Redirect URI copied");
		} catch {
			toast.error("Could not copy to clipboard");
		}
	};

	return (
		<>
			<Table>
				<TableHeader>
					<TableRow>
						<TableHead>Provider</TableHead>
						<TableHead>Type</TableHead>
						<TableHead>Redirect URI</TableHead>
						<TableHead>Enabled</TableHead>
						<TableHead className="text-right">Actions</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					{providers.map((provider) => {
						const busy = mutatingIds.has(provider.registrationId);
						const redirectInputId = `lp-redirect-${provider.registrationId}`;
						return (
							<TableRow key={provider.registrationId}>
								<TableCell>
									<div className="font-medium">{provider.displayName}</div>
									<div className="text-xs text-muted-foreground">
										{provider.registrationId}
										{provider.seededFromEnv && (
											<Badge variant="outline" className="ml-2 align-middle">
												seeded
											</Badge>
										)}
									</div>
									<div className="text-xs text-muted-foreground">{provider.baseUrl}</div>
								</TableCell>
								<TableCell>
									<Badge variant="secondary">{getProviderLabel(provider.type)}</Badge>
								</TableCell>
								<TableCell>
									<InputGroup className="max-w-[24rem]">
										<InputGroupInput
											id={redirectInputId}
											readOnly
											value={provider.redirectUri}
											aria-label={`Redirect URI for ${provider.displayName}`}
											onFocus={(event) => event.currentTarget.select()}
										/>
										<Tooltip>
											<TooltipTrigger
												render={
													<InputGroupButton
														size="icon-xs"
														aria-label={`Copy redirect URI for ${provider.displayName}`}
														onClick={() => void copyRedirect(provider.redirectUri)}
													>
														<Copy aria-hidden />
													</InputGroupButton>
												}
											/>
											<TooltipContent>Copy redirect URI</TooltipContent>
										</Tooltip>
									</InputGroup>
								</TableCell>
								<TableCell>
									<div className="flex items-center gap-2">
										<Switch
											checked={provider.enabled}
											disabled={busy}
											aria-busy={busy}
											aria-label={`${provider.enabled ? "Disable" : "Enable"} ${provider.displayName}`}
											onCheckedChange={(checked) => onToggleEnabled(provider, checked)}
										/>
										{busy && <Spinner className="size-3.5 text-muted-foreground" />}
									</div>
								</TableCell>
								<TableCell className="text-right">
									<div className="flex justify-end gap-1">
										{provider.type === "OIDC" && (
											<Button
												variant="outline"
												size="sm"
												disabled={busy}
												onClick={() => onDirectoryGroups(provider)}
											>
												Directory groups
											</Button>
										)}
										<Button
											type="button"
											variant="ghost"
											size="icon"
											aria-label={`Edit ${provider.displayName}`}
											disabled={busy}
											onClick={() => onEdit(provider)}
										>
											<Pencil className="size-4" aria-hidden />
										</Button>
										<Button
											type="button"
											variant="ghost"
											size="icon"
											aria-label={`Delete ${provider.displayName}`}
											disabled={busy}
											onClick={() => setDeleting(provider)}
										>
											<Trash2 className="size-4 text-destructive" aria-hidden />
										</Button>
									</div>
								</TableCell>
							</TableRow>
						);
					})}
				</TableBody>
			</Table>

			<AlertDialog
				open={deleting != null}
				// Dismissal is allowed even while the DELETE is in flight (ADR 0027).
				onOpenChange={(open) => {
					if (!open) setDeleting(null);
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Delete “{deleting?.displayName}”?</AlertDialogTitle>
						<AlertDialogDescription>
							Users will no longer be able to sign in with this provider. Existing linked accounts
							are unaffected. This cannot be undone.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction
							variant="destructive"
							disabled={isDeletePending}
							onClick={() => deleting && onDelete(deleting)}
						>
							{isDeletePending ? "Deleting…" : "Delete"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</>
	);
}

/** Skeleton rows in the real table shell, so the layout does not jump when the data lands. */
function LoadingRows() {
	return (
		<Table>
			<TableHeader>
				<TableRow>
					<TableHead>Provider</TableHead>
					<TableHead>Type</TableHead>
					<TableHead>Redirect URI</TableHead>
					<TableHead>Enabled</TableHead>
					<TableHead className="text-right">Actions</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{SKELETON_ROWS.map((id) => (
					<TableRow key={`loading-${id}`}>
						<TableCell>
							<Skeleton className="h-4 w-32" />
							<Skeleton className="mt-2 h-3 w-20" />
						</TableCell>
						<TableCell>
							<Skeleton className="h-5 w-16" />
						</TableCell>
						<TableCell>
							<Skeleton className="h-8 w-64" />
						</TableCell>
						<TableCell>
							<Skeleton className="h-5 w-9" />
						</TableCell>
						<TableCell>
							<div className="flex justify-end gap-1">
								<Skeleton className="size-8" />
								<Skeleton className="size-8" />
							</div>
						</TableCell>
					</TableRow>
				))}
			</TableBody>
		</Table>
	);
}
