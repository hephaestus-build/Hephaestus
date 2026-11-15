import { Pencil, Trash2 } from "lucide-react";
import { useState } from "react";

import type { WorkspaceLlmModel } from "@/api/types.gen";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { AiMark } from "@/components/icons/AiMark";
import { DataHandlingBadge } from "@/components/practice-vocabulary/DataHandlingBadge";
import { ModelReadinessStatus } from "@/components/practice-vocabulary/ModelReadinessStatus";
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
import { priceLabel } from "@/lib/llm-pricing";

export interface WorkspaceLlmModelsTableProps {
	providerName: string;
	models: WorkspaceLlmModel[];
	/** Ids of the models with a write in flight. */
	mutatingIds: ReadonlySet<number>;
	onEdit: (model: WorkspaceLlmModel) => void;
	onDelete: (model: WorkspaceLlmModel) => void;
}

const COLUMNS = 5;

export function WorkspaceLlmModelsTable({
	providerName,
	models,
	mutatingIds,
	onEdit,
	onDelete,
}: WorkspaceLlmModelsTableProps) {
	const [deleting, setDeleting] = useState<WorkspaceLlmModel | null>(null);

	return (
		<>
			<Table bordered className="min-w-200 table-fixed">
				<TableCaption className="sr-only">Models on {providerName}</TableCaption>
				<TableHeader>
					<TableRow variant="static">
						<TableHead scope="col">Model</TableHead>
						<TableHead scope="col" className="w-36">
							Data handling
						</TableHead>
						<TableHead scope="col" className="w-72">
							Price
						</TableHead>
						<TableHead scope="col" className="w-36">
							Status
						</TableHead>
						<TableHead scope="col" className="w-24 text-right">
							Actions
						</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					{models.length === 0 && (
						<TableRow variant="static">
							{/* Start-aligned: centred in a table wider than a phone, it would sit partly out of view. */}
							<TableCell colSpan={COLUMNS} className="h-16 text-muted-foreground">
								No models yet. Add a model to use this provider.
							</TableCell>
						</TableRow>
					)}
					{models.map((model) => {
						const busy = mutatingIds.has(model.id);
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
								    aligns the digits inside it. It wraps inside its fixed column. */}
								<TableCell numeric className="whitespace-normal">
									{priceLabel(model, "workspace")}
								</TableCell>
								<TableCell>
									<ModelReadinessStatus readiness={model.readiness} />
								</TableCell>
								<TableCell className="text-right">
									<div className="flex justify-end gap-1">
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
				description="Practice reviews and conversations stop using this model wherever you assigned it, until you assign another. You cannot undo this."
				confirmLabel="Delete model"
				onConfirm={onDelete}
			/>
		</>
	);
}
