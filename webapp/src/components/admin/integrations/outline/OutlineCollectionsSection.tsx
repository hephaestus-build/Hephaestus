import { LibraryIcon, PlusIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import type { OutlineCollection } from "@/api/types.gen";
import { IntegrationCardHeading } from "@/components/admin/integrations/IntegrationCardHeading";
import { swallow } from "@/components/admin/integrations/swallow";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TableRowsSkeleton } from "@/components/common/TableRowsSkeleton";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Table, TableBody, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { RefreshFailure } from "@/runtime/tanstack-query/infinite-list";

import { AddCollectionDialog, type CollectionCandidates } from "./AddCollectionDialog";
import { OutlineCollectionRow, type OutlineMirrorState } from "./OutlineCollectionRow";
import { RemoveCollectionAlertDialog } from "./RemoveCollectionAlertDialog";

export type { OutlineMirrorState } from "./OutlineCollectionRow";

/** Shared by the loading and loaded states so the header doesn't materialise on resolve. */
function CollectionsTableHeader() {
	return (
		<TableHeader>
			<TableRow>
				<TableHead>Collection</TableHead>
				<TableHead>State</TableHead>
				<TableHead>Sync</TableHead>
				<TableHead className="w-0 text-right">
					<span className="sr-only">Actions</span>
				</TableHead>
			</TableRow>
		</TableHeader>
	);
}

export interface OutlineCollectionsSectionProps {
	collections: OutlineCollection[];
	isLoading: boolean;
	/** The collection-list query's error, if it failed — surfaced with a retry instead of the empty state. */
	error?: unknown;
	onRetry?: () => void;
	/** Reading the collections again failed; the collections last read stay, with the failure above them. */
	refreshFailure?: RefreshFailure;
	/** Register a collection for mirroring (lands ENABLED + PENDING). Resolves on success,
	 * rejects to keep the add dialog open. */
	onRegisterCollection: (input: { collectionId: string }) => Promise<void> | void;
	/** Drive pause / resume via the target mirror state. */
	onUpdateCollectionState: (input: {
		collectionId: string;
		state: OutlineMirrorState;
	}) => Promise<void> | void;
	/** Remove the collection and erase its mirrored documents. Resolves on success, rejects to
	 * keep the confirm dialog open. */
	onRemoveCollection: (input: { collectionId: string }) => Promise<void> | void;
	/** The Add collections dialog is open. The route reads the candidates only while it is. */
	addOpen: boolean;
	onAddOpenChange: (open: boolean) => void;
	collectionCandidates: CollectionCandidates;
}

/**
 * A listed collection with its Remove confirmation. The confirmation lives as long as the row, so it
 * names the collection as the current list reads it. When the row leaves the list, as a refused read
 * clears it, the confirmation and its pending answer go with it, and a later read does not reopen it.
 */
function MirroredCollection({
	collection,
	onUpdateCollectionState,
	onRemoveCollection,
}: Pick<OutlineCollectionsSectionProps, "onUpdateCollectionState" | "onRemoveCollection"> & {
	collection: OutlineCollection;
}) {
	const [removing, setRemoving] = useState(false);
	return (
		<>
			<OutlineCollectionRow
				collection={collection}
				onPause={(c) =>
					swallow(
						onUpdateCollectionState({
							collectionId: c.collectionId,
							state: "PAUSED",
						}),
					)
				}
				onResume={(c) =>
					swallow(
						onUpdateCollectionState({
							collectionId: c.collectionId,
							state: "ENABLED",
						}),
					)
				}
				onRemove={() => setRemoving(true)}
			/>
			<RemoveCollectionAlertDialog
				collection={collection}
				open={removing}
				onOpenChange={setRemoving}
				onConfirm={onRemoveCollection}
			/>
		</>
	);
}

/**
 * Admin surface for the mirrored-collections plane: which Outline collections are mirrored, the
 * outcome of their last pass, and their lifecycle (add → pause ⇄ resume → remove + erase). Rendered
 * only while an Outline connection is active. Pure: all data + mutations live in the container.
 *
 * <p>Management only. Per-collection document counts and freshness belong to the shared
 * `SyncResourcesTable` the Outline page mounts above this card — see {@link OutlineCollectionRow}
 * for why they are not duplicated here.
 */
export function OutlineCollectionsSection({
	collections,
	isLoading,
	error,
	onRetry,
	refreshFailure,
	onRegisterCollection,
	onUpdateCollectionState,
	onRemoveCollection,
	addOpen,
	onAddOpenChange,
	collectionCandidates,
}: OutlineCollectionsSectionProps) {
	const hasCollections = collections.length > 0;
	const failed = error != null;
	const refreshAlert = refreshFailure !== undefined && (
		<QueryErrorAlert title="The mirrored collections may be out of date" {...refreshFailure} />
	);

	let content: ReactNode;
	if (isLoading) {
		content = (
			<Table>
				<CollectionsTableHeader />
				<TableRowsSkeleton columns={["w-36", "w-16", "w-20", null]} rows={3} />
			</Table>
		);
	} else if (failed) {
		content = (
			<QueryErrorAlert
				error={error}
				title="We could not load the mirrored collections"
				onRetry={onRetry}
			/>
		);
	} else if (hasCollections) {
		content = (
			<>
				{refreshAlert}
				<Table>
					<CollectionsTableHeader />
					<TableBody>
						{collections.map((collection) => (
							<MirroredCollection
								key={collection.collectionId}
								collection={collection}
								onUpdateCollectionState={onUpdateCollectionState}
								onRemoveCollection={onRemoveCollection}
							/>
						))}
					</TableBody>
				</Table>
			</>
		);
	} else {
		content = (
			<>
				{refreshAlert}
				<Empty>
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<LibraryIcon />
						</EmptyMedia>
						<EmptyTitle>No collections mirrored yet</EmptyTitle>
						<EmptyDescription>
							Add the Outline collections whose documents should reach practice reviews. Hephaestus
							reads only the collections you add.
						</EmptyDescription>
					</EmptyHeader>
					<EmptyContent>
						<Button size="sm" onClick={() => onAddOpenChange(true)}>
							<PlusIcon className="size-4" />
							Add collection
						</Button>
					</EmptyContent>
				</Empty>
			</>
		);
	}

	return (
		<div className="space-y-6">
			<Card>
				<CardHeader>
					<IntegrationCardHeading>Mirrored collections</IntegrationCardHeading>
					<CardDescription>
						Documents in mirrored collections are kept in sync and reach practice reviews as
						context. Hephaestus posts no feedback in Outline: feedback about a document goes to its
						author’s Practice profile and conversation with Hephaestus. Pausing a collection{" "}
						<strong>freezes syncing but keeps its documents</strong>
						{". "}Removing it <strong>erases every mirrored document</strong> from Hephaestus.
					</CardDescription>
					<CardAction>
						<Button size="sm" onClick={() => onAddOpenChange(true)}>
							<PlusIcon className="size-4" />
							Add collection
						</Button>
					</CardAction>
				</CardHeader>

				<CardContent className="space-y-4">{content}</CardContent>
			</Card>

			<AddCollectionDialog
				candidates={collectionCandidates}
				open={addOpen}
				onOpenChange={onAddOpenChange}
				onRegister={onRegisterCollection}
			/>
		</div>
	);
}
