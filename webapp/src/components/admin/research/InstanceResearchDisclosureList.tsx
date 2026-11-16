import { ArchiveIcon } from "lucide-react";
import { useState } from "react";

import type { CloseResearchDisclosure, ResearchRemovalObligation } from "@/api/types.gen";
import { EmptyState } from "@/components/common/EmptyState";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { formatCalendarDate } from "@/lib/dates";
import { lastPermittedMoment } from "@/lib/research-release";

import { InstanceResearchAttestationDialog } from "./InstanceResearchAttestationDialog";
import { removalReasonLabel } from "./research-labels";

type Closure = CloseResearchDisclosure["closure"];

/** One open dataset as the server's last check returned it, with its workspace named for people. */
export interface ResearchOpenDataset {
	datasetId: string;
	workspaceLabel: string;
	createdAt: Date;
	useUntil: Date;
	useExpired: boolean;
	removals: ResearchRemovalObligation[];
}

export type InstanceResearchDisclosureListState =
	/** Nothing read yet: a check asks the server, which enforces the deployment's assurance policy. */
	| { status: "unchecked" }
	| { status: "loading" }
	/** A failed check shows no earlier answer: the server owns which datasets are open. */
	| { status: "error"; error: unknown }
	| {
			status: "ready";
			/** Identifies this server answer; a new check gives a new revision. */
			revision: number;
			datasets: ResearchOpenDataset[];
	  };

export interface InstanceResearchDisclosureListProps {
	state: InstanceResearchDisclosureListState;
	/**
	 * A receipt or closure without a confirmed answer. A lost response can follow a recorded
	 * attestation, so this never says that nothing was recorded.
	 */
	actionError?: unknown;
	/** The dataset whose receipt or closure is in flight. */
	pendingDatasetId?: string;
	onCheck: () => void;
	onRecordRemoved: (datasetId: string, participantKeys: string[]) => void;
	onClose: (datasetId: string, closure: Closure) => void;
}

/** A dialog belongs to one dataset in one server answer; any later answer closes it. */
interface OpenDialog {
	mode: "removal" | "closure";
	datasetId: string;
	revision: number;
}

function datasetLabel(dataset: ResearchOpenDataset): string {
	return `${dataset.workspaceLabel}, exported ${formatCalendarDate(dataset.createdAt)}`;
}

export function InstanceResearchDisclosureList({
	state,
	actionError,
	pendingDatasetId,
	onCheck,
	onRecordRemoved,
	onClose,
}: InstanceResearchDisclosureListProps) {
	const [dialog, setDialog] = useState<OpenDialog | undefined>();
	const closeDialog = (open: boolean) => {
		if (!open) {
			setDialog(undefined);
		}
	};
	const openDialog = (mode: OpenDialog["mode"], datasetId: string) => {
		if (state.status === "ready") {
			setDialog({ mode, datasetId, revision: state.revision });
		}
	};
	const checking = state.status === "loading";
	// The dialog shows the dataset as the current answer has it. While the list loads or fails, or once
	// a newer answer arrives, there is no dataset to confirm against, so the dialog is not shown.
	const active =
		dialog !== undefined && state.status === "ready" && state.revision === dialog.revision
			? state.datasets.find((dataset) => dataset.datasetId === dialog.datasetId)
			: undefined;
	const activeMode = active === undefined ? undefined : dialog?.mode;

	return (
		<Card>
			<CardHeader>
				<CardTitle>
					<h2>Open datasets</h2>
				</CardTitle>
				<CardDescription>
					A dataset stays open until you close it, also when this instance no longer runs a research
					program. Check before the research team uses a dataset again. A withdrawal, a newer
					answer, a changed question or an erased account makes a removal owed. The removal covers
					the participant’s own rows and every review fact that lists their key.
				</CardDescription>
			</CardHeader>
			<CardContent className="space-y-4">
				<Button pending={checking} variant="outline" className="w-fit" onClick={onCheck}>
					{checking ? "Checking…" : "Check open datasets"}
				</Button>
				{actionError !== undefined && (
					<>
						<QueryErrorAlert
							error={actionError}
							title="We could not confirm whether this was recorded"
						/>
						<p className="text-sm text-muted-foreground">
							The server may have recorded it before its answer was lost, so it was not sent again.
							The list below is checked again. Record it again only if it is still owed there.
						</p>
					</>
				)}
				{state.status === "unchecked" && (
					<p className="text-sm text-muted-foreground">
						Open datasets appear after you check. Each check loads the current records.
					</p>
				)}
				{state.status === "loading" && (
					<div aria-busy="true" className="space-y-3">
						<Skeleton className="h-6 w-1/2" />
						<Skeleton className="h-24 w-full" />
						<span className="sr-only">Loading open datasets</span>
					</div>
				)}
				{state.status === "error" && (
					<QueryErrorAlert
						error={state.error}
						title="We could not check the open datasets"
						onRetry={onCheck}
					/>
				)}
				{state.status === "ready" && state.datasets.length === 0 && (
					<EmptyState
						icon={<ArchiveIcon />}
						title="No open datasets"
						headingLevel={3}
						description="Every exported dataset is closed. Closed datasets keep a record without participant links."
					/>
				)}
				{state.status === "ready" &&
					state.datasets.map((dataset) => {
						const pending = pendingDatasetId === dataset.datasetId;
						return (
							<section
								key={dataset.datasetId}
								aria-labelledby={`dataset-${dataset.datasetId}`}
								className="space-y-3 rounded-lg border p-4"
							>
								<div className="space-y-1">
									<h3 id={`dataset-${dataset.datasetId}`} className="font-medium">
										{datasetLabel(dataset)}
									</h3>
									<p className="text-sm text-muted-foreground">
										Use allowed through {formatCalendarDate(lastPermittedMoment(dataset.useUntil))}{" "}
										(UTC). Dataset <code className="break-all">{dataset.datasetId}</code>
									</p>
								</div>
								{dataset.useExpired && (
									<Alert variant="warning">
										<AlertTitle>The use period ended</AlertTitle>
										<AlertDescription>
											Ask the research team to delete or anonymize every copy, then close the
											dataset.
										</AlertDescription>
									</Alert>
								)}
								{dataset.removals.length === 0 ? (
									<p className="text-sm text-muted-foreground">No removal is owed.</p>
								) : (
									<Table>
										<TableHeader>
											<TableRow>
												<TableHead>Participant key</TableHead>
												<TableHead>Why removal is owed</TableHead>
												<TableHead>Owed since</TableHead>
											</TableRow>
										</TableHeader>
										<TableBody>
											{dataset.removals.map((removal) => (
												<TableRow key={removal.participantKey}>
													<TableCell>
														<code>{removal.participantKey}</code>
													</TableCell>
													<TableCell>{removalReasonLabel(removal.reason)}</TableCell>
													<TableCell>{formatCalendarDate(removal.requiredAt)}</TableCell>
												</TableRow>
											))}
										</TableBody>
									</Table>
								)}
								<div className="flex flex-wrap gap-2">
									{dataset.removals.length > 0 && (
										<Button
											variant="outline"
											disabled={pending || checking}
											onClick={() => openDialog("removal", dataset.datasetId)}
										>
											Record removal…
										</Button>
									)}
									<Button
										pending={pending}
										variant="outline"
										disabled={pending || checking}
										onClick={() => openDialog("closure", dataset.datasetId)}
									>
										{pending ? "Recording…" : "Close dataset…"}
									</Button>
								</div>
							</section>
						);
					})}
				{active !== undefined && activeMode === "removal" && active.removals.length > 0 && (
					<InstanceResearchAttestationDialog
						open
						onOpenChange={closeDialog}
						mode="removal"
						// The dialog closes itself on confirm; the list reloads from the server afterwards.
						datasetId={active.datasetId}
						datasetLabel={datasetLabel(active)}
						participantKeys={active.removals.map((removal) => removal.participantKey)}
						onConfirm={(keys) => onRecordRemoved(active.datasetId, keys)}
					/>
				)}
				{active !== undefined && activeMode === "closure" && (
					<InstanceResearchAttestationDialog
						open
						onOpenChange={closeDialog}
						mode="closure"
						datasetId={active.datasetId}
						datasetLabel={datasetLabel(active)}
						owedCount={active.removals.length}
						onConfirm={(closure) => onClose(active.datasetId, closure)}
					/>
				)}
			</CardContent>
		</Card>
	);
}
