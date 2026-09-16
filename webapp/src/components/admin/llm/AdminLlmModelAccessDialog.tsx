import { AlertTriangle } from "lucide-react";
import { useState } from "react";

import type { LlmModel, UpdateLlmModelSharingRequest } from "@/api/types.gen";
import { FacetMultiSelect } from "@/components/common/FacetMultiSelect";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";

import { type ModelAccessScope, ModelAccessScopeChoice } from "./ModelAccessScopeChoice";
import { type WorkspaceOption, workspaceFacetOptions } from "./workspace-options";

export interface AdminLlmModelAccessDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	model: LlmModel | null;
	workspaceOptions: WorkspaceOption[];
	isLoadingWorkspaces?: boolean;
	workspacesError?: unknown;
	onRetryWorkspaces?: () => void;
	isSubmitting: boolean;
	onSave: (body: UpdateLlmModelSharingRequest) => void;
}

/**
 * Access editor for an instance model; changes take effect at request time. Keyed by the model and
 * mounted only while open, so the selection seeds once — an effect would discard an unsaved edit on
 * a background refetch.
 */
export function AdminLlmModelAccessDialog({
	open,
	onOpenChange,
	model,
	...contentProps
}: AdminLlmModelAccessDialogProps) {
	if (!model) return null;

	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			{open && (
				<AdminLlmModelAccessDialogContent
					key={model.id}
					model={model}
					onCancel={() => onOpenChange(false)}
					{...contentProps}
				/>
			)}
		</Dialog>
	);
}

type AdminLlmModelAccessDialogContentProps = Omit<
	AdminLlmModelAccessDialogProps,
	"open" | "onOpenChange" | "model"
> & {
	model: LlmModel;
	onCancel: () => void;
};

function AdminLlmModelAccessDialogContent({
	model,
	workspaceOptions,
	isLoadingWorkspaces = false,
	workspacesError,
	onRetryWorkspaces,
	isSubmitting,
	onSave,
	onCancel,
}: AdminLlmModelAccessDialogContentProps) {
	const [scope, setScope] = useState<ModelAccessScope>(
		model.visibility === "PUBLIC" ? "ALL" : "SELECTED",
	);
	const [workspaceIds, setWorkspaceIds] = useState<number[]>(model.grantedWorkspaceIds);
	const isWorkspaceError = workspacesError != null;

	const removesCurrentWorkspace =
		scope === "SELECTED" &&
		(model.visibility === "PUBLIC"
			? workspaceOptions.some((workspace) => !workspaceIds.includes(workspace.id))
			: model.grantedWorkspaceIds.some((id) => !workspaceIds.includes(id)));
	const restrictsFutureWorkspaces = model.visibility === "PUBLIC" && scope === "SELECTED";
	const noWorkspaceHasAccess = scope === "SELECTED" && workspaceIds.length === 0;

	return (
		<DialogContent className="sm:max-w-lg">
			<DialogHeader>
				<DialogTitle>Manage access to {model.displayName}</DialogTitle>
				<DialogDescription>
					Choose which workspaces can discover and use this model. Provider credentials remain
					hidden from workspace admins.
				</DialogDescription>
			</DialogHeader>

			{/* The workspace list outgrows a short viewport, so only the body scrolls. */}
			<DialogBody className="space-y-4 py-1">
				<ModelAccessScopeChoice
					idPrefix="llm-model-access"
					label="Who can use this model"
					value={scope}
					onChange={setScope}
				/>

				{scope === "SELECTED" && (
					<div className="space-y-2">
						<p className="text-sm font-medium">Workspaces</p>
						{isWorkspaceError ? (
							<>
								<QueryErrorAlert
									error={workspacesError}
									title="Could not load workspaces"
									onRetry={onRetryWorkspaces}
								/>
								<p className="text-xs text-muted-foreground">
									Saving a workspace list stays disabled until the directory loads.
								</p>
							</>
						) : (
							<FacetMultiSelect
								variant="field"
								id="llm-model-access-workspaces"
								title="Workspaces"
								options={workspaceFacetOptions(workspaceOptions)}
								selected={workspaceIds}
								onChange={setWorkspaceIds}
								disabled={isLoadingWorkspaces || isSubmitting}
								emptyLabel="No workspaces yet"
							/>
						)}
						{isLoadingWorkspaces && (
							<p className="text-xs text-muted-foreground">Loading workspaces…</p>
						)}
					</div>
				)}

				{/* Re-derived on every press: an assertive `role="alert"` would interrupt the combobox's
				    own announcement each time (SC 4.1.3). */}
				{noWorkspaceHasAccess && !isWorkspaceError && (
					<Alert role="status">
						<AlertTitle>No workspace will be able to use this model</AlertTitle>
						<AlertDescription>
							This is useful while staging a model. Grant access when it is ready.
						</AlertDescription>
					</Alert>
				)}

				{removesCurrentWorkspace && (
					<Alert variant="warning" role="status">
						<AlertTriangle aria-hidden />
						<AlertTitle>Access is reduced immediately</AlertTitle>
						<AlertDescription>
							Practice reviews and Mentor stop in the workspaces you removed, until each of them
							picks another model.
						</AlertDescription>
					</Alert>
				)}
				{restrictsFutureWorkspaces && !removesCurrentWorkspace && (
					<Alert role="status">
						<AlertTitle>Future workspaces will need an explicit grant</AlertTitle>
						<AlertDescription>
							Every workspace using it today keeps it, so nothing stops running.
						</AlertDescription>
					</Alert>
				)}
			</DialogBody>

			<DialogFooter>
				<Button type="button" variant="outline" onClick={onCancel}>
					Cancel
				</Button>
				<Button
					type="button"
					disabled={
						isSubmitting || (scope === "SELECTED" && (isLoadingWorkspaces || isWorkspaceError))
					}
					onClick={() =>
						onSave(
							scope === "ALL" ? { visibility: "PUBLIC" } : { visibility: "GRANTED", workspaceIds },
						)
					}
				>
					{isSubmitting ? "Saving…" : "Save access"}
				</Button>
			</DialogFooter>
		</DialogContent>
	);
}
