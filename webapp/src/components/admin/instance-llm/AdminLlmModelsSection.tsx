import { Pencil, Plus, ShieldCheck, Trash2 } from "lucide-react";
import { useState } from "react";

import type { LlmConnection, LlmModel } from "@/api/types.gen";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { StatusBadge } from "@/components/common/StatusBadge";
import { AiMark } from "@/components/icons/AiMark";
import { DataHandlingBadge } from "@/components/practice-vocabulary/DataHandlingBadge";
import {
	MODEL_READINESS_DEFS,
	type ModelReadiness,
} from "@/components/practice-vocabulary/model-readiness-defs";
import { Button } from "@/components/ui/button";
import {
	Table,
	TableBody,
	TableCaption,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { priceFieldsOf, priceLabel } from "@/lib/llm-pricing";
import { hasText } from "@/lib/text";

import { LlmConnectionApi } from "./LlmConnectionApi";
import type { WorkspaceOption } from "./workspace-options";

export interface AdminLlmModelsSectionProps {
	/** Its API is named under the heading, because it decides which purposes the models can serve. */
	connection: Pick<LlmConnection, "displayName" | "apiProtocol" | "purposes" | "enabled">;
	workspaceOptions: WorkspaceOption[];
	models: LlmModel[];
	mutatingIds: ReadonlySet<number>;
	onAdd: () => void;
	onEdit: (model: LlmModel) => void;
	onManageAccess: (model: LlmModel) => void;
	onDelete: (model: LlmModel) => void;
}

/**
 * What keeps a model from workspaces, worst first; null for a model they can use. An undeclared data
 * handling is the registry badge's warning, not a block.
 */
function readiness(model: LlmModel, connectionEnabled: boolean): ModelReadiness | null {
	if (!model.currentPrice || model.currentPrice.pricingMode === "UNPRICED") {
		return "PRICE_MISSING";
	}
	if (!connectionEnabled) {
		return "CONNECTION_OFF";
	}
	if (!model.enabled) {
		return "OFF";
	}
	if (model.visibility === "GRANTED" && model.grantedWorkspaceIds.length === 0) {
		return "NO_WORKSPACE_ACCESS";
	}
	return null;
}

const COLUMNS = 6;

function shareLabel(model: LlmModel, workspaces: WorkspaceOption[]): string {
	if (model.visibility === "PUBLIC") {
		return "All workspaces";
	}
	if (model.grantedWorkspaceIds.length === 0) {
		return "No workspaces";
	}
	const firstName = workspaces.find(
		(workspace) => workspace.id === model.grantedWorkspaceIds[0],
	)?.displayName;
	if (!hasText(firstName)) {
		return model.grantedWorkspaceIds.length === 1
			? "1 workspace"
			: `${model.grantedWorkspaceIds.length} workspaces`;
	}
	return model.grantedWorkspaceIds.length === 1
		? firstName
		: `${firstName} + ${model.grantedWorkspaceIds.length - 1} more`;
}

export function AdminLlmModelsSection({
	connection,
	workspaceOptions,
	models,
	mutatingIds,
	onAdd,
	onEdit,
	onManageAccess,
	onDelete,
}: AdminLlmModelsSectionProps) {
	const [deleting, setDeleting] = useState<LlmModel | null>(null);
	const heading = `Models on ${connection.displayName}`;

	return (
		<div className="space-y-3">
			<div className="flex items-start justify-between gap-3">
				<div className="min-w-0 space-y-0.5">
					{/* `CardTitle` is a `<div>` and the caption a `<caption>`, so neither contributes to the
					    outline and an `h3` here would skip a level (WCAG SC 1.3.1) — despite the `text-sm`. */}
					<h2 className="text-sm font-medium">{heading}</h2>
					{/* The API decides which purposes these models can serve. */}
					<p className="text-sm text-muted-foreground">
						<LlmConnectionApi connection={connection} />
					</p>
				</div>
				<Button size="sm" variant="outline" onClick={onAdd}>
					<Plus className="size-4" aria-hidden />
					Add model
				</Button>
			</div>

			<Table bordered>
				<TableCaption className="sr-only">{heading}</TableCaption>
				<TableHeader>
					<TableRow>
						<TableHead scope="col">Model</TableHead>
						<TableHead scope="col">Data handling</TableHead>
						<TableHead scope="col">Price</TableHead>
						<TableHead scope="col">Workspace access</TableHead>
						<TableHead scope="col">Status</TableHead>
						<TableHead scope="col" className="text-right">
							Actions
						</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					{models.length === 0 && (
						<TableRow variant="static">
							<TableCell colSpan={COLUMNS} className="h-16 text-muted-foreground">
								No models yet. Add a model so workspaces can pick it.
							</TableCell>
						</TableRow>
					)}
					{models.map((model) => {
						const busy = mutatingIds.has(model.id);
						const status = readiness(model, connection.enabled);
						return (
							<TableRow key={model.id}>
								<TableCell>
									<span className="flex min-w-0 items-center gap-2 font-medium">
										<AiMark brand={model.brand} size="sm" />
										<span className="min-w-0 truncate" title={model.displayName}>
											{model.displayName}
										</span>
									</span>
								</TableCell>
								<TableCell>
									<DataHandlingBadge tier={model.dataHandlingTier} />
								</TableCell>
								{/* Left-aligned: `priceLabel` is a sentence, not a figure; `tabular-nums` only
								    aligns the digits inside it. */}
								<TableCell numeric>{priceLabel(priceFieldsOf(model), "instance")}</TableCell>
								<TableCell>{shareLabel(model, workspaceOptions)}</TableCell>
								<TableCell>
									{status === null ? (
										<span className="text-muted-foreground">Ready</span>
									) : (
										<StatusBadge def={MODEL_READINESS_DEFS[status]} />
									)}
								</TableCell>
								<TableCell className="text-right">
									<div className="flex justify-end gap-1">
										<Button
											type="button"
											variant="outline"
											size="sm"
											aria-label={`Manage access for ${model.displayName}`}
											disabled={busy}
											onClick={() => onManageAccess(model)}
										>
											<ShieldCheck className="size-4" aria-hidden />
											Access
										</Button>
										<Button
											type="button"
											variant="ghost"
											size="icon"
											aria-label={`Edit ${model.displayName}`}
											disabled={busy}
											onClick={() => onEdit(model)}
										>
											<Pencil className="size-4" aria-hidden />
										</Button>
										<Button
											type="button"
											variant="ghost"
											size="icon"
											aria-label={`Delete ${model.displayName}`}
											disabled={busy}
											onClick={() => setDeleting(model)}
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

			<ConfirmDialog
				subject={deleting}
				onClose={() => setDeleting(null)}
				title={(model) => `Delete “${model.displayName}”?`}
				description="This deletes the model. If a workspace still uses it, the delete fails. You cannot undo this."
				confirmLabel="Delete model"
				onConfirm={onDelete}
			/>
		</div>
	);
}
