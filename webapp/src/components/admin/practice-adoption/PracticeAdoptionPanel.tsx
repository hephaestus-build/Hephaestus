import { CircleAlert, Copy, ShieldCheck } from "lucide-react";

import { cn } from "cn";
import type { CatalogPracticePreview, PracticeDefinitionOptions } from "@/api/types.gen";
import { PracticeDefinitionPreview } from "@/components/admin/practice-adoption/PracticeDefinitionPreview";
import { GroupPill } from "@/components/admin/practice-catalog/GroupPill";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { DetailRow } from "@/components/common/DetailRow";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailDrawerHeader } from "@/components/core/detail-drawer/DetailDrawerHeader";
import { DetailStackLink } from "@/components/core/detail-drawer/DetailStackLink";
import { Section } from "@/components/core/Section";
import { AUTONOMY_DEFS } from "@/components/practice-vocabulary/autonomy-defs";
import { CATALOG_AVAILABILITY_DEFS } from "@/components/practice-vocabulary/catalog-availability-defs";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerDescription, DrawerFooter, DrawerTitle } from "@/components/ui/drawer";
import {
	Item,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { Separator } from "@/components/ui/separator";

export type PracticeAdoptionState = PanelState<{
	preview: CatalogPracticePreview;
	definitionOptions: PracticeDefinitionOptions;
	action: "idle" | "adding" | "stale";
}>;

export interface PracticeAdoptionPanelProps {
	state: PracticeAdoptionState;
	onAdopt: () => void;
	nested?: boolean;
}

export function PracticeAdoptionPanel({ state, onAdopt, nested }: PracticeAdoptionPanelProps) {
	if (state.status !== "ready") {
		return <PracticeAdoptionPlaceholder state={state} nested={nested} />;
	}
	const { preview, definitionOptions, action } = state;
	const availability = CATALOG_AVAILABILITY_DEFS[preview.availability];
	const unavailable = preview.availability !== "AVAILABLE";

	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<GroupPill
					size="lg"
					slug={preview.group.slug}
					name={preview.group.definition?.name}
					icon={preview.group.definition?.icon}
					color={preview.group.definition?.color}
				/>
				<div className="min-w-0 flex-1 space-y-1">
					<DrawerTitle className="break-words">{preview.definition.name}</DrawerTitle>
					<DrawerDescription>
						<WorkTypeLabel artifactKind={preview.definition.artifactKind} />
					</DrawerDescription>
					{availability.badged && <StatusBadge def={availability} className="mt-1.5" />}
				</div>
			</DetailDrawerHeader>

			<DrawerBody className="space-y-6">
				{action === "stale" && (
					<Alert variant="warning">
						<CircleAlert />
						<AlertTitle>The catalog changed while you were reading</AlertTitle>
						<AlertDescription>
							This is the current definition and outcome. Nothing was added.
						</AlertDescription>
					</Alert>
				)}
				{unavailable && (
					<Alert variant="warning">
						<CircleAlert />
						<AlertTitle>{availability.label}</AlertTitle>
						<AlertDescription>{availability.description}</AlertDescription>
					</Alert>
				)}

				<PracticeDefinitionPreview definition={preview.definition} options={definitionOptions} />

				{!unavailable && (
					<>
						<Separator />
						<Section size="sm" title="Adding this practice will">
							<ItemGroup className="gap-2">
								<Item variant="muted" size="sm" role="listitem">
									<ItemMedia variant="icon">
										<Copy />
									</ItemMedia>
									<ItemContent>
										<ItemTitle>Create a copy this workspace owns</ItemTitle>
										<ItemDescription>
											Edit it freely. Later catalog changes never reach it, and yours never reach
											the catalog.
										</ItemDescription>
									</ItemContent>
								</Item>
								<GroupOutcome preview={preview} />
								<AutonomyOutcome preview={preview} />
							</ItemGroup>
						</Section>
					</>
				)}

				<Accordion aria-label="Catalog provenance">
					<AccordionItem value="catalog-details">
						<AccordionTrigger>Catalog details</AccordionTrigger>
						<AccordionContent>
							<dl className="divide-y">
								<DetailRow label="Source">
									<code>{preview.slug}</code>
								</DetailRow>
								<DetailRow label="Review version">
									<code>{preview.sourceReviewRuleFingerprint}</code>
								</DetailRow>
							</dl>
						</AccordionContent>
					</AccordionItem>
				</Accordion>
			</DrawerBody>

			<DrawerFooter>
				{unavailable ? (
					<DetailStackLink
						entry={{ kind: "practice", id: preview.slug }}
						className={cn(buttonVariants(), "w-full sm:w-auto")}
					>
						Open workspace practice
					</DetailStackLink>
				) : (
					<Button onClick={onAdopt} disabled={action === "adding"} className="w-full sm:w-auto">
						<ShieldCheck /> {action === "adding" ? "Adding…" : "Add practice"}
					</Button>
				)}
			</DrawerFooter>
		</>
	);
}

/**
 * A level that has not resolved still owes the drawer a title. Base UI does not enforce one: with no
 * `Title` it drops `aria-labelledby` and the panel renders silently with no accessible name, so the
 * failure surfaces only in an axe run, or in a screen reader announcing an unnamed dialog.
 */
function PracticeAdoptionPlaceholder({
	state,
	nested,
}: {
	state: Extract<PracticeAdoptionState, { status: "loading" } | { status: "error" }>;
	nested?: boolean;
}) {
	return (
		<>
			<DetailDrawerHeader nested={nested}>
				<DrawerTitle>Practice</DrawerTitle>
			</DetailDrawerHeader>
			<DrawerBody>
				{state.status === "loading" ? (
					<PracticeDefinitionSkeleton />
				) : (
					<QueryErrorAlert
						error={state.error}
						title="Couldn't load the adoption preview"
						onRetry={state.onRetry}
					/>
				)}
			</DrawerBody>
		</>
	);
}

function GroupOutcome({ preview }: { preview: CatalogPracticePreview }) {
	return (
		<Item variant="muted" size="sm" role="listitem">
			<ItemMedia variant="icon" className="bg-transparent">
				<GroupPill
					size="sm"
					slug={preview.group.slug}
					name={preview.group.definition?.name}
					icon={preview.group.definition?.icon}
					color={preview.group.definition?.color}
				/>
			</ItemMedia>
			<ItemContent>
				<ItemTitle>{groupTitle(preview)}</ItemTitle>
				{preview.group.definition?.description && (
					<ItemDescription className="line-clamp-none">
						{preview.group.definition.description}
					</ItemDescription>
				)}
			</ItemContent>
		</Item>
	);
}

function AutonomyOutcome({ preview }: { preview: CatalogPracticePreview }) {
	const autonomy = AUTONOMY_DEFS[preview.initialAutonomy];
	return (
		// No bare icon beside the badge: `StatusBadge` already renders the registry's icon, and the
		// same mark twice in one row reads as two facts.
		<Item variant="muted" size="sm" role="listitem">
			<ItemContent>
				<ItemTitle>
					<StatusBadge def={autonomy} />
				</ItemTitle>
				<ItemDescription className="line-clamp-none">{autonomy.description}</ItemDescription>
			</ItemContent>
		</Item>
	);
}

function groupTitle(preview: CatalogPracticePreview): string {
	if (preview.group.disposition === "UNASSIGNED") return "Stay unassigned";
	const name = preview.group.definition?.name ?? preview.group.slug ?? "catalog group";
	if (preview.group.disposition === "REUSE_EXISTING_GROUP") return `Join “${name}”`;
	return `Create “${name}”`;
}
