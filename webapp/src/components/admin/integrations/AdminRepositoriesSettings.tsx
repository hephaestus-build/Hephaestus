import { FolderGitIcon, Trash2Icon } from "lucide-react";
import { useState } from "react";

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

import { IntegrationCardHeading } from "./IntegrationCardHeading";

interface RepositoryItem {
	nameWithOwner: string;
}

function RepositoryRow({
	repo,
	providerLabel,
	isRemoving,
	onRemove,
}: {
	repo: RepositoryItem;
	providerLabel: string;
	isRemoving: boolean;
	onRemove: (nameWithOwner: string) => void;
}) {
	const [open, setOpen] = useState(false);

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
							<Button variant="outline" size="icon" aria-label={`Remove ${repo.nameWithOwner}`}>
								<Trash2Icon className="size-4" />
							</Button>
						}
					/>
					<AlertDialogContent>
						<AlertDialogHeader>
							<AlertDialogTitle>Stop monitoring {repo.nameWithOwner}?</AlertDialogTitle>
							<AlertDialogDescription>
								This stops syncing the repository and{" "}
								<strong>
									permanently erases everything Hephaestus has mirrored from it — its issues, pull
									requests, reviews, and the practice reviews built from them
								</strong>
								. The repository on {providerLabel} itself is not affected, and you can start
								monitoring it again later.
							</AlertDialogDescription>
						</AlertDialogHeader>
						<AlertDialogFooter>
							<AlertDialogCancel disabled={isRemoving}>Cancel</AlertDialogCancel>
							<AlertDialogAction variant="destructive" disabled={isRemoving} onClick={handleRemove}>
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

interface AdminRepositoriesSettingsProps {
	repositories: RepositoryItem[];
	providerLabel?: string;
	isLoading: boolean;
	error: unknown;
	addRepositoryError: unknown;
	isAddingRepository: boolean;
	isRemovingRepository: boolean;
	onAddRepository: (nameWithOwner: string) => void;
	onRemoveRepository: (nameWithOwner: string) => void;
	onRetry?: () => void;
}

export function AdminRepositoriesSettings({
	repositories,
	providerLabel = "GitHub or GitLab",
	isLoading,
	error,
	addRepositoryError,
	isAddingRepository,
	isRemovingRepository,
	onAddRepository,
	onRemoveRepository,
	onRetry,
}: AdminRepositoriesSettingsProps) {
	const [repositoryInput, setRepositoryInput] = useState("");
	const isValidInput = repositoryInput.includes("/");

	const handleAddRepository = () => {
		if (isValidInput) {
			onAddRepository(repositoryInput);
			setRepositoryInput("");
		}
	};

	const hasRepositories = repositories.length > 0;
	const failed = error != null;

	return (
		<div className="space-y-6">
			<Card>
				<CardHeader>
					<IntegrationCardHeading>Manage repositories</IntegrationCardHeading>
					<CardDescription>
						Add or remove the repositories Hephaestus watches for practice reviews and mentoring.
						Their per-class sync freshness is shown in the sync-state table above.
					</CardDescription>
				</CardHeader>

				<CardContent className="space-y-4">
					{isLoading ? (
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
					) : failed ? (
						<QueryErrorAlert
							error={error}
							title="We couldn't load the monitored repositories"
							onRetry={onRetry}
						/>
					) : hasRepositories ? (
						<ScrollArea viewportClassName="max-h-80">
							<ItemGroup className="pr-3">
								{repositories.map((repo) => (
									<RepositoryRow
										key={repo.nameWithOwner}
										repo={repo}
										providerLabel={providerLabel}
										isRemoving={isRemovingRepository}
										onRemove={onRemoveRepository}
									/>
								))}
							</ItemGroup>
						</ScrollArea>
					) : (
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
					)}

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
									onClick={handleAddRepository}
									disabled={!isValidInput || isAddingRepository}
								>
									Add
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
				</CardContent>
			</Card>
		</div>
	);
}
