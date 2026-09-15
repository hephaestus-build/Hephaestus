import {
	CheckIcon,
	MoreHorizontalIcon,
	PauseIcon,
	PlayIcon,
	Trash2Icon,
	TriangleAlertIcon,
} from "lucide-react";

import type { OutlineCollection } from "@/api/types.gen";
import { OutlineCollectionIcon } from "@/components/admin/integrations/outline/OutlineCollectionIcon";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuItem,
	DropdownMenuSeparator,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
	Popover,
	PopoverContent,
	PopoverDescription,
	PopoverTitle,
	PopoverTrigger,
} from "@/components/ui/popover";
import { Spinner } from "@/components/ui/spinner";
import { TableCell, TableRow } from "@/components/ui/table";

/** The mirror lifecycle states, sourced from the generated DTO so they never drift. */
export type OutlineMirrorState = OutlineCollection["state"];

export interface OutlineCollectionRowProps {
	collection: OutlineCollection;
	/** Pause an ENABLED collection (reversible, no confirm — documents are kept). */
	onPause: (collection: OutlineCollection) => void;
	/** Resume a PAUSED collection (reversible, no confirm). */
	onResume: (collection: OutlineCollection) => void;
	/** Open the remove & erase confirmation dialog. */
	onRemove: (collection: OutlineCollection) => void;
}

/**
 * One mirrored Outline collection: name with its Outline color/icon, mirror-state badge, the last
 * pass's outcome, and a state-gated row action menu. Pure — every transition is delegated upward.
 *
 * <p>This is the management plane only. Document count and freshness live in the shared
 * `SyncResourcesTable` the Outline page mounts above this card — the same table SCM and Slack mount,
 * so all four integrations report freshness in one language, tinted against the connection's cadence.
 * Printing them here too, from a separately-polled `collections.list`, would let the same fact disagree
 * with itself on screen and could not tint a reading stale against that cadence.
 *
 * <p>The sync error and the budget-skip detail hang off a {@link Popover}, not a tooltip: a tooltip
 * never opens on touch, and the sync error is the one string an admin has to be able to read, select
 * and copy. Both are properties of the last pass rather than of a count, so both sit in the Sync cell.
 */
export function OutlineCollectionRow({
	collection,
	onPause,
	onResume,
	onRemove,
}: OutlineCollectionRowProps) {
	const label = collection.name ?? collection.collectionId;
	const paused = collection.state === "PAUSED";

	return (
		<TableRow>
			<TableCell>
				<div className="flex items-center gap-2">
					<OutlineCollectionIcon icon={collection.icon} color={collection.color} />
					<span className="font-medium">{label}</span>
				</div>
				{/* Only the human-facing Outline urlId is worth a subtitle — the raw UUID is noise. */}
				{collection.urlId && (
					<div className="text-muted-foreground font-mono text-xs">{collection.urlId}</div>
				)}
			</TableCell>

			<TableCell>
				{/* Word + icon for every state (never color-only) so the status survives WCAG 1.4.1. */}
				{paused ? (
					<Badge variant="outline">
						<PauseIcon className="size-3 text-muted-foreground" aria-hidden />
						Paused
					</Badge>
				) : (
					<Badge variant="success">
						<CheckIcon className="size-3" aria-hidden />
						Mirroring
					</Badge>
				)}
			</TableCell>

			<TableCell>
				<div className="flex items-center gap-2">
					{collection.syncStatus === "PENDING" ? (
						<Badge variant="secondary">
							{/* The visible "Syncing…" text carries the meaning; hide the spinner from AT so the
							row doesn't double-announce a generic "Loading" live region per collection. */}
							<Spinner className="size-3" role="presentation" aria-hidden />
							Syncing…
						</Badge>
					) : (
						<Badge variant="outline">
							<CheckIcon className="size-3 text-muted-foreground" aria-hidden />
							Up to date
						</Badge>
					)}
					{collection.lastSyncError && (
						<Popover>
							<PopoverTrigger
								render={
									<Button
										variant="ghost"
										size="icon-xs"
										className="text-destructive"
										aria-label={`Sync error for ${label}`}
									>
										<TriangleAlertIcon aria-hidden />
									</Button>
								}
							/>
							<PopoverContent align="start" className="max-w-sm">
								<PopoverTitle>Last sync failed</PopoverTitle>
								<PopoverDescription className="break-words whitespace-pre-wrap select-text">
									{collection.lastSyncError}
								</PopoverDescription>
							</PopoverContent>
						</Popover>
					)}
					{!!collection.exportsSkippedForBudget && (
						<Popover>
							{/* A property of the last pass, so it sits with the pass's other outcome (the sync
							error) rather than beside a count. Warning, not destructive — this is expected budget
							throttling, and the next reconcile catches these up. */}
							<PopoverTrigger
								render={
									<Button
										variant="ghost"
										size="icon-xs"
										className="text-warning"
										aria-label={`${collection.exportsSkippedForBudget} exports skipped for budget for ${label}`}
									>
										<TriangleAlertIcon aria-hidden />
									</Button>
								}
							/>
							<PopoverContent align="start" className="max-w-sm">
								<PopoverTitle>Exports skipped for budget</PopoverTitle>
								<PopoverDescription className="break-words">
									{collection.exportsSkippedForBudget} export
									{collection.exportsSkippedForBudget === 1 ? "" : "s"} skipped for the shared
									budget in the last pass — they catch up on the next reconcile.
								</PopoverDescription>
							</PopoverContent>
						</Popover>
					)}
				</div>
			</TableCell>

			<TableCell className="text-right">
				<DropdownMenu>
					<DropdownMenuTrigger
						render={
							<Button variant="ghost" size="icon-sm" aria-label={`Actions for ${label}`}>
								<MoreHorizontalIcon className="size-4" />
							</Button>
						}
					/>
					<DropdownMenuContent align="end">
						{paused ? (
							<DropdownMenuItem onClick={() => onResume(collection)}>
								<PlayIcon className="size-4" />
								Resume
							</DropdownMenuItem>
						) : (
							<DropdownMenuItem onClick={() => onPause(collection)}>
								<PauseIcon className="size-4" />
								Pause
							</DropdownMenuItem>
						)}
						<DropdownMenuSeparator />
						<DropdownMenuItem variant="destructive" onClick={() => onRemove(collection)}>
							<Trash2Icon className="size-4" />
							Remove &amp; erase…
						</DropdownMenuItem>
					</DropdownMenuContent>
				</DropdownMenu>
			</TableCell>
		</TableRow>
	);
}
