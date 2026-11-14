import { ArchiveIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import type { GitLabStoppedProject } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import { Item, ItemActions, ItemContent, ItemGroup, ItemTitle } from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { problemDetailOf } from "@/lib/problem-detail";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import { WorkspaceIntegrationCardHeading } from "./WorkspaceIntegrationCardHeading";

export interface WorkspaceStoppedGitLabProjectsProps {
	/** The stopped projects whose synced work this workspace keeps, as the current list reads them. */
	projects: GitLabStoppedProject[];
	isLoading: boolean;
	error: unknown;
	/** Reading the list again failed; the projects last read stay, with the failure above them. */
	refreshFailure?: RefreshFailure;
	onRetry?: () => void;
	/** Removes the synced work of exactly this listed Stop. Resolves once it is removed; rejects to keep the confirmation open. */
	onRemove: (project: GitLabStoppedProject) => Promise<void>;
}

/**
 * One stopped project and its Remove confirmation. The list keys the row on the project and its Stop,
 * so a later Stop of the same project mounts a new row. A confirmation opened for the earlier Stop closes
 * with its row instead of answering for the newer one.
 */
function StoppedProjectRow({
	project,
	onRemove,
}: {
	project: GitLabStoppedProject;
	onRemove: WorkspaceStoppedGitLabProjectsProps["onRemove"];
}) {
	const [open, setOpen] = useState(false);
	const [submitting, setSubmitting] = useState(false);
	const [failure, setFailure] = useState<unknown>(null);

	const handleOpenChange = (next: boolean) => {
		if (!next) {
			setFailure(null);
		}
		setOpen(next);
	};

	const confirm = async () => {
		setSubmitting(true);
		setFailure(null);
		try {
			await onRemove(project);
			setOpen(false);
		} catch (error) {
			setFailure(error);
		}
		setSubmitting(false);
	};

	return (
		<Item variant="outline" size="sm" role="listitem">
			<ItemContent className="min-w-0 basis-48">
				<ItemTitle className="line-clamp-none font-mono wrap-anywhere">
					{project.nameWithOwner}
				</ItemTitle>
			</ItemContent>
			<ItemActions>
				<AlertDialog
					open={open}
					onOpenChange={(next, details) => {
						// A pending removal owns the dialog until its answer, so dismissing cannot hide it.
						if (!next && submitting) {
							details.cancel();
							return;
						}
						handleOpenChange(next);
					}}
				>
					<AlertDialogTrigger
						render={
							<Button
								variant="outline"
								size="sm"
								aria-label={`Remove synced work from ${project.nameWithOwner}`}
							>
								Remove synced work
							</Button>
						}
					/>
					<AlertDialogContent>
						<AlertDialogHeader>
							<AlertDialogTitle className="wrap-anywhere">
								Remove the synced work of {project.nameWithOwner}?
							</AlertDialogTitle>
							<AlertDialogDescription>
								Hephaestus removes the work it synced from this project from this workspace. The
								project on GitLab is not affected. Practice reviews, feedback and conversations
								about that work keep their usual retention. The project stays stopped. To sync it
								again, add it with its current path.
							</AlertDialogDescription>
						</AlertDialogHeader>
						{failure != null && (
							<Alert variant="destructive">
								<AlertTitle>The synced work was not removed</AlertTitle>
								<AlertDescription>{problemDetailOf(failure)}</AlertDescription>
							</Alert>
						)}
						<AlertDialogFooter>
							<AlertDialogCancel disabled={submitting}>Cancel</AlertDialogCancel>
							<AlertDialogAction
								variant="destructive"
								disabled={submitting}
								onClick={() => {
									void confirm();
								}}
							>
								{submitting ? (
									<>
										<Spinner className="size-4" aria-hidden />
										Removing…
									</>
								) : (
									"Remove synced work"
								)}
							</AlertDialogAction>
						</AlertDialogFooter>
					</AlertDialogContent>
				</AlertDialog>
			</ItemActions>
		</Item>
	);
}

/**
 * The GitLab projects this workspace stopped syncing but whose synced work it still keeps, with the
 * explicit removal of that work. Adding a project again happens in the repository list, by its current
 * path, so GitLab confirms which project the path names today.
 */
export function WorkspaceStoppedGitLabProjects({
	projects,
	isLoading,
	error,
	refreshFailure,
	onRetry,
	onRemove,
}: WorkspaceStoppedGitLabProjectsProps) {
	let list: ReactNode;
	if (isLoading) {
		list = (
			<ItemGroup aria-busy="true">
				{Array.from({ length: 2 }, (_, index) => (
					<Item key={index} variant="outline" size="sm" role="listitem">
						<ItemContent>
							<Skeleton className="h-5 w-48" />
						</ItemContent>
						<ItemActions>
							<Skeleton className="h-8 w-36 rounded-lg" />
						</ItemActions>
					</Item>
				))}
			</ItemGroup>
		);
	} else if (error != null) {
		list = (
			<QueryErrorAlert
				error={error}
				title="We could not load the stopped projects"
				onRetry={onRetry}
			/>
		);
	} else if (projects.length > 0) {
		list = (
			<ItemGroup role="list" aria-label="Stopped projects">
				{projects.map((project) => (
					<StoppedProjectRow
						key={`${project.repositoryItemId}:${project.stopReceipt}`}
						project={project}
						onRemove={onRemove}
					/>
				))}
			</ItemGroup>
		);
	} else {
		list = (
			<Empty>
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<ArchiveIcon />
					</EmptyMedia>
					<EmptyTitle>No stopped projects</EmptyTitle>
					<EmptyDescription>
						When you stop syncing a project, the work Hephaestus synced from it stays here until you
						remove it.
					</EmptyDescription>
				</EmptyHeader>
			</Empty>
		);
	}

	return (
		<Card>
			<CardHeader>
				<WorkspaceIntegrationCardHeading>Stopped projects</WorkspaceIntegrationCardHeading>
				<CardDescription>
					Hephaestus no longer syncs these projects, but this workspace keeps the work it already
					synced from them. To sync a project again, add it under Manage repositories with its
					current path.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-4">
				{refreshFailure !== undefined && (
					<QueryErrorAlert title="The stopped projects may be out of date" {...refreshFailure} />
				)}
				{list}
			</CardContent>
		</Card>
	);
}
