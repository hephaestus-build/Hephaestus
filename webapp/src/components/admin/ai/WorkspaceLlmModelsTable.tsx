import { Bot, Pencil, Trash2 } from "lucide-react";
import { useState } from "react";

import type { WorkspaceLlmModel } from "@/api/types.gen";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { DataHandlingBadge } from "@/components/practice-vocabulary/DataHandlingBadge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
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
	models: WorkspaceLlmModel[];
	/** Ids of the models with a write in flight. */
	mutatingIds: ReadonlySet<number>;
	onEdit: (model: WorkspaceLlmModel) => void;
	onDelete: (model: WorkspaceLlmModel) => void;
}

export function WorkspaceLlmModelsTable({
	models,
	mutatingIds,
	onEdit,
	onDelete,
}: WorkspaceLlmModelsTableProps) {
	const [deleting, setDeleting] = useState<WorkspaceLlmModel | null>(null);

	if (models.length === 0) {
		return (
			<Empty className="border">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<Bot />
					</EmptyMedia>
					<EmptyTitle>No models yet</EmptyTitle>
				</EmptyHeader>
			</Empty>
		);
	}

	return (
		<>
			<Table containerClassName="rounded-md border">
				<TableCaption className="sr-only">Models on your own connected providers</TableCaption>
				<TableHeader>
					<TableRow>
						<TableHead scope="col">Model</TableHead>
						<TableHead scope="col">Data handling</TableHead>
						<TableHead scope="col">Price</TableHead>
						<TableHead scope="col">Active</TableHead>
						<TableHead scope="col" className="text-right">
							Actions
						</TableHead>
					</TableRow>
				</TableHeader>
				<TableBody>
					{models.map((model) => {
						const busy = mutatingIds.has(model.id);
						return (
							<TableRow key={model.id}>
								<TableCell>
									<div className="font-medium">{model.displayName}</div>
								</TableCell>
								<TableCell>
									<DataHandlingBadge tier={model.dataHandlingTier} />
								</TableCell>
								{/* Left-aligned: `priceLabel` is a sentence, not a figure; `tabular-nums` only
								    aligns the digits inside it. */}
								<TableCell className="tabular-nums">{priceLabel(model, "workspace")}</TableCell>
								<TableCell>
									<Badge variant={model.enabled ? "default" : "secondary"}>
										{model.enabled ? "Active" : "Off"}
									</Badge>
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
				description="Any agent bound to this model will stop working until it's rebound. This cannot be undone."
				confirmLabel="Delete"
				onConfirm={onDelete}
			/>
		</>
	);
}
