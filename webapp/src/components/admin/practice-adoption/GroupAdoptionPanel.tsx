import { ChevronRight, CircleAlert } from "lucide-react";

import type { CatalogGroupAdoptionPreview } from "@/api/types.gen";
import { GroupPill } from "@/components/admin/practice-catalog/GroupPill";
import { PracticeListSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailDrawerHeader } from "@/components/core/detail-drawer/DetailDrawerHeader";
import {
	CATALOG_GROUP_ACTION_DEFS,
	CATALOG_GROUP_CHANGE_ACTIONS,
} from "@/components/practice-vocabulary/catalog-group-action-defs";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { DrawerBody, DrawerDescription, DrawerFooter, DrawerTitle } from "@/components/ui/drawer";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { hasText } from "@/lib/text";

export type GroupAdoptionState = PanelState<{
	preview: CatalogGroupAdoptionPreview;
	action: "idle" | "adding" | "stale";
}>;

export interface GroupAdoptionPanelProps {
	state: GroupAdoptionState;
	onConfirm: () => void;
	onOpenPractice: (catalogSlug: string) => void;
	nested?: boolean;
}

export function GroupAdoptionPanel({
	state,
	onConfirm,
	onOpenPractice,
	nested,
}: GroupAdoptionPanelProps) {
	const preview = state.status === "ready" ? state.preview : undefined;
	const changes =
		preview?.actions.filter(({ action }) => CATALOG_GROUP_CHANGE_ACTIONS.includes(action)) ?? [];
	const restoring = changes.length > 0 && changes.every(({ action }) => action === "MOVE_TO_GROUP");

	return (
		<>
			<DetailDrawerHeader nested={nested}>
				{preview && (
					<GroupPill
						size="lg"
						slug={preview.slug}
						name={preview.definition.name}
						icon={preview.definition.icon}
						color={preview.definition.color}
					/>
				)}
				<div className="min-w-0 flex-1 space-y-0.5">
					<DrawerTitle className="break-words">
						{preview?.definition.name ?? "Practice group"}
					</DrawerTitle>
					{preview && (
						<DrawerDescription>
							{preview.disposition === "CREATE_CATALOG_GROUP"
								? "Creates this group in the workspace."
								: "Uses the existing workspace group without changing it."}
						</DrawerDescription>
					)}
				</div>
			</DetailDrawerHeader>

			<DrawerBody className="space-y-6">
				{state.status === "ready" && state.action === "stale" && (
					<Alert variant="warning">
						<CircleAlert />
						<AlertTitle>The catalog changed while you were reading</AlertTitle>
						<AlertDescription>
							This is the current plan for the group. Nothing was added.
						</AlertDescription>
					</Alert>
				)}
				{state.status === "loading" && <PracticeListSkeleton rows={4} />}
				{state.status === "error" && (
					<QueryErrorAlert
						error={state.error}
						title="Couldn't load the current group definition"
						onRetry={state.onRetry}
					/>
				)}
				{state.status === "ready" && (
					<>
						{hasText(state.preview.definition.description) && (
							<p className="text-sm text-muted-foreground">
								{state.preview.definition.description}
							</p>
						)}
						<ItemGroup>
							{state.preview.practices.map((practice) => {
								const action =
									state.preview.actions.find(({ slug }) => slug === practice.slug)?.action ??
									"KEEP";
								const def = CATALOG_GROUP_ACTION_DEFS[action];
								return (
									<div key={practice.slug} role="listitem">
										<Item
											variant="outline"
											render={<button type="button" />}
											onClick={() => onOpenPractice(practice.slug)}
										>
											<ItemMedia variant="icon">
												<def.icon />
											</ItemMedia>
											<ItemContent className="min-w-0 text-left">
												<ItemTitle className="line-clamp-none break-words">
													{practice.definition.name}
												</ItemTitle>
												<ItemDescription className="line-clamp-none">
													{def.description}
												</ItemDescription>
											</ItemContent>
											<ItemActions>
												<StatusBadge def={def} />
												<ChevronRight className="size-4 text-muted-foreground" aria-hidden="true" />
											</ItemActions>
										</Item>
									</div>
								);
							})}
						</ItemGroup>
					</>
				)}
			</DrawerBody>

			{state.status === "ready" && (
				<DrawerFooter>
					<Button onClick={onConfirm} disabled={changes.length === 0 || state.action === "adding"}>
						{state.action === "adding"
							? "Adding…"
							: restoring
								? "Restore group"
								: `Add ${changes.length} ${changes.length === 1 ? "practice" : "practices"}`}
					</Button>
				</DrawerFooter>
			)}
		</>
	);
}
