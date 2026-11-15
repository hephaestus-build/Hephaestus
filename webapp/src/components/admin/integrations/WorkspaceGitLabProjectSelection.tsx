import { cn } from "cn";
import { type ReactNode, useId, useState } from "react";

import type { GitLabRepositorySelection } from "@/api/types.gen";
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
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import { WorkspaceIntegrationCardHeading } from "./WorkspaceIntegrationCardHeading";

type ProjectSelection = GitLabRepositorySelection["repositorySelection"];

interface WorkspaceGitLabProjectSelectionProps {
	/** The choice as the server stores it; absent until it reads. */
	selection: ProjectSelection | undefined;
	isLoading: boolean;
	/** The choice could not be read, and none is shown. */
	error: unknown;
	/** Reading the choice again failed; the choice last read stays, with the failure above it. */
	refreshFailure?: RefreshFailure;
	onRetry?: () => void;
	isSaving: boolean;
	saveError: unknown;
	/** Resolves once the server answered; the shown choice follows the server, not the request. */
	onSelectionChange: (selection: ProjectSelection) => Promise<boolean>;
}

/**
 * Whether a GitLab workspace picks up new projects from its group. The radio shows the stored
 * choice until the server confirms a change, and widening asks first.
 */
export function WorkspaceGitLabProjectSelection({
	selection,
	isLoading,
	error,
	refreshFailure,
	onRetry,
	isSaving,
	saveError,
	onSelectionChange,
}: WorkspaceGitLabProjectSelectionProps) {
	const id = useId();
	const [confirmingWidening, setConfirmingWidening] = useState(false);
	// The question exists only while the stored choice is SELECTED. A controlled Base UI dialog does not
	// report a close its `open` prop caused, so a change read from the server withdraws the question
	// here, during render. Otherwise it would come back the next time the choice reads SELECTED.
	if (confirmingWidening && selection !== "SELECTED") {
		setConfirmingWidening(false);
	}

	const choose = (next: ProjectSelection) => {
		if (isSaving || next === selection) {
			return;
		}
		if (next === "ALL") {
			setConfirmingWidening(true);
			return;
		}
		void onSelectionChange(next);
	};

	const widen = async () => {
		await onSelectionChange("ALL");
		setConfirmingWidening(false);
	};

	let body: ReactNode;
	if (isLoading) {
		body = (
			<div className="grid gap-3" aria-busy="true">
				<span className="sr-only">Loading the project choice…</span>
				{["w-44", "w-52"].map((width) => (
					<div key={width} className="flex items-start gap-3" aria-hidden>
						<Skeleton className="size-4 shrink-0 rounded-full" />
						<div className="flex min-w-0 flex-col gap-1.5">
							<Skeleton className={cn("h-4", width)} />
							<Skeleton className="h-3 w-full max-w-72" />
						</div>
					</div>
				))}
			</div>
		);
	} else if (selection === undefined) {
		body =
			error == null ? null : (
				<QueryErrorAlert
					error={error}
					title="We could not load the project choice"
					onRetry={onRetry}
				/>
			);
	} else {
		body = (
			<>
				<RadioGroup
					value={selection}
					onValueChange={(value) => choose(value)}
					disabled={isSaving}
					aria-label="Projects to follow"
				>
					<Field orientation="horizontal">
						<RadioGroupItem id={`${id}-all`} value="ALL" />
						<FieldContent className="min-w-0">
							<FieldLabel htmlFor={`${id}-all`}>All projects in the group</FieldLabel>
							<FieldDescription>
								Projects that Hephaestus later finds in the group are added. Projects you stopped
								stay stopped.
							</FieldDescription>
						</FieldContent>
					</Field>
					<Field orientation="horizontal">
						<RadioGroupItem id={`${id}-selected`} value="SELECTED" />
						<FieldContent className="min-w-0">
							<FieldLabel htmlFor={`${id}-selected`}>Only the projects listed below</FieldLabel>
							<FieldDescription>
								New projects in the group are not added. To change the list, use Manage repositories
								below.
							</FieldDescription>
						</FieldContent>
					</Field>
				</RadioGroup>
				{/* Exists before its words change, so a screen reader hears the save start. While idle it
				    is out of the flex flow, so the empty region adds no gap. */}
				<p
					role="status"
					className={cn(
						"flex items-center gap-2 text-sm text-muted-foreground",
						!isSaving && "sr-only",
					)}
				>
					{isSaving && (
						<>
							<Spinner className="size-4" />
							Saving the project choice…
						</>
					)}
				</p>
				{saveError != null && (
					<QueryErrorAlert error={saveError} title="We could not change the project choice" />
				)}
			</>
		);
	}

	return (
		<Card className="max-w-full">
			<CardHeader>
				<WorkspaceIntegrationCardHeading>Projects to follow</WorkspaceIntegrationCardHeading>
				<CardDescription>
					Choose whether new projects in the GitLab group are added to this workspace. With only the
					listed projects, members come from those projects and the subgroups that contain them,
					after the next successful sync. Teams and work that is already synced stay.
				</CardDescription>
			</CardHeader>
			<CardContent className="flex flex-col gap-4">
				{refreshFailure !== undefined && (
					<QueryErrorAlert title="The project choice may be out of date" {...refreshFailure} />
				)}
				{body}
			</CardContent>
			<AlertDialog
				open={confirmingWidening}
				onOpenChange={(open) => {
					if (!open && !isSaving) {
						setConfirmingWidening(false);
					}
				}}
			>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Add new projects from the group?</AlertDialogTitle>
						<AlertDialogDescription>
							From now on, Hephaestus adds the projects it finds in the group, including projects
							created later. Projects you stopped stay stopped until you add them again. Workspace
							members also come from the whole group after the next successful sync.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel disabled={isSaving}>Cancel</AlertDialogCancel>
						<AlertDialogAction
							pending={isSaving}
							onClick={() => {
								void widen();
							}}
						>
							{isSaving ? "Saving…" : "Add new projects"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</Card>
	);
}
