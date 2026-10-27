import { CircleStopIcon, FolderGitIcon, Trash2Icon } from "lucide-react";
import { type ReactNode, useState } from "react";

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
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import {
	InputGroup,
	InputGroupAddon,
	InputGroupButton,
	InputGroupInput,
} from "@/components/ui/input-group";
import { Item, ItemActions, ItemContent, ItemGroup, ItemTitle } from "@/components/ui/item";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { problemDetailOf } from "@/lib/problem-detail";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import { IntegrationCardHeading } from "./IntegrationCardHeading";

interface RepositoryItem {
	nameWithOwner: string;
}

type ScmProvider = "GITHUB" | "GITLAB";

function StopDescription({
	provider,
	providerLabel,
}: {
	provider: ScmProvider | undefined;
	providerLabel: string;
}) {
	if (provider === "GITLAB") {
		return (
			<>
				Hephaestus stops syncing this project and leaves it out when it finds new projects in the
				group. The project on GitLab is not affected. This workspace keeps the work Hephaestus
				already synced from it, under Stopped projects, until you remove it there. To sync it again,
				add it below with its current path.
			</>
		);
	}
	return (
		<>
			Hephaestus stops syncing this repository. The repository on {providerLabel} is not affected.
			If no other workspace on this instance still monitors it, Hephaestus also erases the work it
			mirrored from it. Practice reviews and feedback can remain. To sync it again, add it below.
		</>
	);
}

function RepositoryRow({
	repo,
	provider,
	providerLabel,
	isRemoving,
	onRemove,
}: {
	repo: RepositoryItem;
	provider: ScmProvider | undefined;
	providerLabel: string;
	isRemoving: boolean;
	onRemove: (nameWithOwner: string) => void;
}) {
	const [open, setOpen] = useState(false);
	// A GitLab Stop keeps the synced work, so it is not a destructive action there.
	const keepsWork = provider === "GITLAB";
	const StopIcon = keepsWork ? CircleStopIcon : Trash2Icon;

	const handleRemove = () => {
		onRemove(repo.nameWithOwner);
	};

	return (
		<Item variant="outline" size="sm" role="listitem">
			<ItemContent>
				<ItemTitle className="truncate font-mono" title={repo.nameWithOwner}>
					{repo.nameWithOwner}
				</ItemTitle>
			</ItemContent>
			<ItemActions>
				<AlertDialog open={open} onOpenChange={setOpen}>
					<AlertDialogTrigger
						render={
							<Button
								variant="outline"
								size="icon"
								aria-label={`Stop monitoring ${repo.nameWithOwner}`}
							>
								<StopIcon className="size-4" />
							</Button>
						}
					/>
					<AlertDialogContent>
						<AlertDialogHeader>
							<AlertDialogTitle>Stop monitoring {repo.nameWithOwner}?</AlertDialogTitle>
							<AlertDialogDescription>
								<StopDescription provider={provider} providerLabel={providerLabel} />
							</AlertDialogDescription>
						</AlertDialogHeader>
						<AlertDialogFooter>
							<AlertDialogCancel disabled={isRemoving}>Cancel</AlertDialogCancel>
							<AlertDialogAction
								variant={keepsWork ? "default" : "destructive"}
								disabled={isRemoving}
								onClick={handleRemove}
							>
								{isRemoving ? (
									<>
										<Spinner className="size-4" aria-hidden />
										Stopping…
									</>
								) : (
									"Stop monitoring"
								)}
							</AlertDialogAction>
						</AlertDialogFooter>
					</AlertDialogContent>
				</AlertDialog>
			</ItemActions>
		</Item>
	);
}

interface WorkspaceRepositoriesSettingsProps {
	repositories: RepositoryItem[];
	provider?: ScmProvider;
	providerLabel?: string;
	isLoading: boolean;
	error: unknown;
	/** Reading the repositories again failed; the repositories last read stay, with the failure above them. */
	refreshFailure?: RefreshFailure;
	addRepositoryError: unknown;
	isAddingRepository: boolean;
	isRemovingRepository: boolean;
	/** Resolves to whether the repository was added; the entered path stays for a retry until it was. */
	onAddRepository: (nameWithOwner: string) => Promise<boolean>;
	onRemoveRepository: (nameWithOwner: string) => void;
	onRetry?: () => void;
}

export function WorkspaceRepositoriesSettings({
	repositories,
	provider,
	providerLabel = "GitHub or GitLab",
	isLoading,
	error,
	refreshFailure,
	addRepositoryError,
	isAddingRepository,
	isRemovingRepository,
	onAddRepository,
	onRemoveRepository,
	onRetry,
}: WorkspaceRepositoriesSettingsProps) {
	const [repositoryInput, setRepositoryInput] = useState("");
	const isValidInput = repositoryInput.includes("/");

	const handleAddRepository = async () => {
		if (!isValidInput) {
			return;
		}
		const submitted = repositoryInput;
		if (await onAddRepository(submitted)) {
			// A path entered after this one was submitted is a new draft, not the added repository.
			setRepositoryInput((current) => (current === submitted ? "" : current));
		}
	};

	const hasRepositories = repositories.length > 0;
	const failed = error != null;
	// Add is offered beside a list that reads, even an empty one. The path typed into it stays while
	// the list cannot be read, for when it reads again.
	const canAdd = !isLoading && !failed;

	let list: ReactNode;
	if (isLoading) {
		list = (
			<ItemGroup>
				{Array.from({ length: 3 }, (_, index) => (
					<Item key={index} variant="outline" size="sm" role="listitem">
						<ItemContent>
							<Skeleton className="h-5 w-48" />
						</ItemContent>
						<ItemActions>
							<Skeleton className="size-8 rounded-lg" />
						</ItemActions>
					</Item>
				))}
			</ItemGroup>
		);
	} else if (failed) {
		list = (
			<QueryErrorAlert
				error={error}
				title="We could not load the monitored repositories"
				onRetry={onRetry}
			/>
		);
	} else if (hasRepositories) {
		list = (
			<ScrollArea viewportClassName="max-h-80">
				<ItemGroup className="pr-3">
					{repositories.map((repo) => (
						<RepositoryRow
							key={repo.nameWithOwner}
							repo={repo}
							provider={provider}
							providerLabel={providerLabel}
							isRemoving={isRemovingRepository}
							onRemove={onRemoveRepository}
						/>
					))}
				</ItemGroup>
			</ScrollArea>
		);
	} else {
		list = (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<FolderGitIcon />
					</EmptyMedia>
					<EmptyTitle>No repositories monitored yet</EmptyTitle>
					<EmptyDescription>
						Add a repository below to start monitoring it for practice reviews.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}

	return (
		<div className="space-y-6">
			<Card>
				<CardHeader>
					<IntegrationCardHeading>Manage repositories</IntegrationCardHeading>
					<CardDescription>
						Add or remove the repositories that Hephaestus watches for practice reviews and Heph
						conversations. The sync table above shows how recently each one synced.
					</CardDescription>
				</CardHeader>

				<CardContent className="space-y-4">
					{refreshFailure !== undefined && (
						<QueryErrorAlert
							title="The monitored repositories may be out of date"
							{...refreshFailure}
						/>
					)}
					{list}

					{canAdd && (
						<Field data-invalid={Boolean(addRepositoryError)}>
							<FieldLabel htmlFor="add-repository">Add a repository</FieldLabel>
							<InputGroup>
								<InputGroupInput
									id="add-repository"
									value={repositoryInput}
									onChange={(e) => setRepositoryInput(e.target.value)}
									disabled={isAddingRepository}
									autoComplete="off"
									aria-invalid={Boolean(addRepositoryError)}
								/>
								<InputGroupAddon align="inline-end">
									<InputGroupButton
										variant="default"
										onClick={() => {
											void handleAddRepository();
										}}
										disabled={!isValidInput || isAddingRepository}
									>
										Add repository
									</InputGroupButton>
								</InputGroupAddon>
							</InputGroup>
							<FieldDescription>
								Enter the repository as <code>owner/name</code>.
							</FieldDescription>
							{addRepositoryError != null && (
								<FieldError>{problemDetailOf(addRepositoryError)}</FieldError>
							)}
						</Field>
					)}
				</CardContent>
			</Card>
		</div>
	);
}
