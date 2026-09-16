import { useQuery } from "@tanstack/react-query";
import { CheckIcon, LibraryIcon, LockIcon } from "lucide-react";
import { useId, useRef, useState } from "react";

import { listOutlineCollectionCandidatesOptions } from "@/api/@tanstack/react-query.gen";
import type { OutlineCollectionCandidate } from "@/api/types.gen";
import { OutlineCollectionIcon } from "@/components/admin/integrations/outline/OutlineCollectionIcon";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	Combobox,
	ComboboxEmpty,
	ComboboxItem,
	ComboboxList,
	ComboboxSearchInput,
	useComboboxFilter,
} from "@/components/ui/combobox";
import {
	Dialog,
	DialogClose,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { problemDetailOf } from "@/lib/problem-detail";

export interface AddCollectionDialogProps {
	workspaceSlug: string;
	open: boolean;
	onOpenChange: (open: boolean) => void;
	onRegister: (input: { collectionId: string }) => Promise<void> | void;
}

function labelOf(candidate: OutlineCollectionCandidate) {
	return candidate.name ?? candidate.collectionId;
}

function searchTextOf(candidate: OutlineCollectionCandidate) {
	return `${labelOf(candidate)} ${candidate.urlId ?? ""} ${candidate.collectionId}`;
}

export function AddCollectionDialog({
	workspaceSlug,
	open,
	onOpenChange,
	onRegister,
}: AddCollectionDialogProps) {
	const { contains } = useComboboxFilter({ sensitivity: "base" });
	const comboboxRef = useRef<HTMLDivElement>(null);
	const collectionListId = useId();
	const [selectedIds, setSelectedIds] = useState<readonly string[]>([]);
	const [submitting, setSubmitting] = useState(false);
	const [submitError, setSubmitError] = useState<string | null>(null);
	const [registered, setRegistered] = useState(0);

	const {
		data: candidates,
		isLoading,
		error: candidatesError,
		refetch,
	} = useQuery({
		...listOutlineCollectionCandidatesOptions({ path: { workspaceSlug } }),
		enabled: open,
		staleTime: 0,
		retry: false,
	});

	const all = candidates ?? [];
	const selectable = all.filter((candidate) => !candidate.alreadyMirrored);
	const canSubmit = selectedIds.length > 0 && !submitting;
	const selectedCandidates = all.filter((candidate) =>
		selectedIds.includes(candidate.collectionId),
	);

	function handleOpenChange(next: boolean) {
		if (!next) {
			setSelectedIds([]);
			setSubmitting(false);
			setSubmitError(null);
			setRegistered(0);
		}
		onOpenChange(next);
	}

	async function submit() {
		if (!canSubmit) {
			return;
		}
		setSubmitting(true);
		setSubmitError(null);
		setRegistered(0);
		let remaining = [...selectedIds];
		try {
			for (const collectionId of selectedIds) {
				await onRegister({ collectionId });
				remaining = remaining.filter((id) => id !== collectionId);
				setRegistered((done) => done + 1);
			}
			handleOpenChange(false);
		} catch (error) {
			setSelectedIds(remaining);
			setSubmitError(problemDetailOf(error));
		}
		setSubmitting(false);
	}

	const total = selectedIds.length;

	return (
		<Dialog open={open} onOpenChange={handleOpenChange}>
			<DialogContent
				className="sm:max-w-lg"
				initialFocus={() =>
					comboboxRef.current?.querySelector<HTMLElement>('input[role="combobox"]') ?? false
				}
			>
				<DialogHeader>
					<DialogTitle>Add collections to mirror</DialogTitle>
					<DialogDescription>
						Pick the Outline collections whose documents Hephaestus should mirror. Only the
						collections you select here are read.
					</DialogDescription>
				</DialogHeader>

				<form
					onSubmit={(e) => {
						e.preventDefault();
						void submit();
					}}
				>
					{isLoading ? (
						<div className="space-y-2">
							<Skeleton className="h-9 w-full" />
							<Skeleton className="h-9 w-full" />
							<Skeleton className="h-9 w-full" />
						</div>
					) : candidatesError ? (
						<QueryErrorAlert
							error={candidatesError}
							title="Could not reach Outline"
							onRetry={() => {
								void refetch();
							}}
						/>
					) : all.length === 0 ? (
						<Empty variant="outlined">
							<EmptyHeader>
								<EmptyMedia variant="icon">
									<LockIcon />
								</EmptyMedia>
								<EmptyTitle>This token cannot see any collections</EmptyTitle>
								<EmptyDescription>
									Outline only returns the collections its API key's user is a member of. In
									Outline, open the collection, choose <strong>Members</strong>, and add the bot
									user that owns this key — then reopen this dialog.
								</EmptyDescription>
							</EmptyHeader>
						</Empty>
					) : selectable.length === 0 ? (
						<Empty variant="outlined">
							<EmptyHeader>
								<EmptyMedia variant="icon">
									<LibraryIcon />
								</EmptyMedia>
								<EmptyTitle>Every visible collection is already mirrored</EmptyTitle>
								<EmptyDescription>
									Grant the bot user access to another collection in Outline to mirror more.
								</EmptyDescription>
							</EmptyHeader>
						</Empty>
					) : (
						<Combobox
							multiple
							inline
							items={all}
							value={selectedCandidates}
							onValueChange={(next) => setSelectedIds(next.map((c) => c.collectionId))}
							filter={(candidate, query) => contains(candidate, query, searchTextOf)}
							itemToStringLabel={labelOf}
						>
							<div ref={comboboxRef} className="rounded-lg border">
								<ComboboxSearchInput
									// oxlint-disable-next-line jsx-a11y/no-autofocus -- Narrowing the candidate list by typing is the only way through this dialog, so it opens onto the search box.
									autoFocus
									placeholder="Search collections…"
									disabled={submitting}
									aria-label="Search Outline collections"
									aria-expanded="true"
									aria-controls={collectionListId}
								/>
								<ComboboxEmpty>No collections match your search.</ComboboxEmpty>
								<ComboboxList id={collectionListId} aria-label="Outline collections">
									{(candidate: OutlineCollectionCandidate) => {
										const label = labelOf(candidate);
										const checked =
											candidate.alreadyMirrored || selectedIds.includes(candidate.collectionId);
										return (
											<ComboboxItem
												key={candidate.collectionId}
												value={candidate}
												disabled={candidate.alreadyMirrored || submitting}
												className="pr-2"
											>
												<span
													aria-hidden="true"
													className="flex size-4 shrink-0 items-center justify-center rounded-xs border border-input"
												>
													{checked && <CheckIcon className="size-3.5" />}
												</span>
												<OutlineCollectionIcon icon={candidate.icon} color={candidate.color} />
												<span className="min-w-0 flex-1">
													<span className="block truncate text-sm font-medium">{label}</span>
													{candidate.urlId && (
														<span className="block truncate font-mono text-xs text-muted-foreground">
															{candidate.urlId}
														</span>
													)}
												</span>
												{candidate.alreadyMirrored && (
													<Badge variant="outline">Already mirrored</Badge>
												)}
											</ComboboxItem>
										);
									}}
								</ComboboxList>
							</div>
						</Combobox>
					)}

					<div aria-live="polite" className="mt-3 min-h-5 text-sm">
						{submitting && total > 0 && (
							<p className="text-muted-foreground">
								Adding {Math.min(registered + 1, total)} of {total}…
							</p>
						)}
						{!submitting && submitError && <p className="text-destructive">{submitError}</p>}
					</div>

					<DialogFooter className="mt-3">
						<DialogClose render={<Button type="button" variant="outline" disabled={submitting} />}>
							Cancel
						</DialogClose>
						<Button type="submit" disabled={!canSubmit}>
							{submitting && <Spinner />}
							{submitting
								? "Adding…"
								: `Add ${total > 0 ? `${total} ` : ""}collection${total === 1 ? "" : "s"}`}
						</Button>
					</DialogFooter>
				</form>
			</DialogContent>
		</Dialog>
	);
}
