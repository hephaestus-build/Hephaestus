import { CircleAlert, Copy, ShieldCheck } from "lucide-react";
import { definitionFor } from "@/components/common/status-def";

import { cn } from "cn";
import type { PracticeDefinitionOptions } from "@/api/types.gen";
import type { PracticeAdoptionPreview } from "@/components/admin/practices/catalog-adoption";
import { PRACTICE_SETUP_LEVEL_LABELS } from "@/components/admin/practices/practice-search";
import { PracticeDefinitionPreview } from "@/components/admin/practices/PracticeDefinitionPreview";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { DetailRow } from "@/components/common/DetailRow";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { Section } from "@/components/layout/Section";
import {
	UNKNOWN_CATALOG_AVAILABILITY_DEF,
	CATALOG_AVAILABILITY_DEFS,
} from "@/components/practice-vocabulary/catalog-availability-defs";
import { FeedbackModeBadge } from "@/components/practice-vocabulary/FeedbackModeBadge";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import {
	Accordion,
	AccordionContent,
	AccordionItem,
	AccordionTrigger,
} from "@/components/ui/accordion";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Item,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemMedia,
	ItemTitle,
} from "@/components/ui/item";
import { Separator } from "@/components/ui/separator";
import { FEEDBACK_MODE_DESCRIPTIONS } from "@/lib/practice-feedback-mode";
import { hasText } from "@/lib/text";

export type PracticeAdoptionState = PanelState<{
	preview: PracticeAdoptionPreview;
	definitionOptions: PracticeDefinitionOptions;
	action: "idle" | "adding" | "stale";
}>;

export interface PracticeAdoptionPanelProps {
	state: PracticeAdoptionState;
	onAdopt: () => void;
	nested?: boolean;
	/** Where the level sits, from the drawer. */
	path: LevelPath;
}

/** What the path calls this level: a practice the instance catalog offers, not one this workspace has. */
const LEVEL_LABEL = PRACTICE_SETUP_LEVEL_LABELS["catalog-practice"];

export function PracticeAdoptionPanel({
	state,
	onAdopt,
	nested,
	path,
}: PracticeAdoptionPanelProps) {
	if (state.status !== "ready") {
		return <PracticeAdoptionPlaceholder state={state} nested={nested} path={path} />;
	}
	const { preview, definitionOptions, action } = state;
	const availability = definitionFor(
		CATALOG_AVAILABILITY_DEFS,
		preview.availability,
		UNKNOWN_CATALOG_AVAILABILITY_DEF,
	);
	const unavailable = preview.availability !== "AVAILABLE";

	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={LEVEL_LABEL}
				mark={
					<GroupPill
						size="lg"
						slug={preview.group.slug}
						name={preview.group.definition?.name}
						icon={preview.group.definition?.icon}
						color={preview.group.definition?.color}
					/>
				}
				title={preview.definition.name}
				chips={availability.badged && <StatusBadge def={availability} />}
				description={<WorkTypeLabel itemKind={preview.kind} />}
			/>

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

				<PracticeDefinitionPreview
					slug={preview.slug}
					kind={preview.kind}
					definition={preview.definition}
					options={definitionOptions}
				/>

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
											Edit it freely. Later catalog changes do not change your copy unless you
											accept them, and your edits never reach the catalog.
										</ItemDescription>
									</ItemContent>
								</Item>
								<GroupOutcome preview={preview} />
								<FeedbackModeOutcome preview={preview} />
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
								<DetailRow label="Catalog version">
									<code className="break-all">{preview.offeredDigest}</code>
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
	path,
}: {
	state: Extract<PracticeAdoptionState, { status: "loading" } | { status: "error" }>;
	nested?: boolean;
	path: LevelPath;
}) {
	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={LEVEL_LABEL}
				loading={state.status === "loading"}
			/>
			<DrawerBody>
				{state.status === "loading" ? (
					<PracticeDefinitionSkeleton />
				) : (
					<QueryErrorAlert
						error={state.error}
						title="We could not load the adoption preview"
						onRetry={state.onRetry}
					/>
				)}
			</DrawerBody>
		</>
	);
}

function GroupOutcome({ preview }: { preview: PracticeAdoptionPreview }) {
	return (
		<Item variant="muted" size="sm" role="listitem">
			<ItemMedia variant="icon">
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
				{hasText(preview.group.definition?.description) && (
					<ItemDescription className="line-clamp-none">
						{preview.group.definition.description}
					</ItemDescription>
				)}
			</ItemContent>
		</Item>
	);
}

function FeedbackModeOutcome({ preview }: { preview: PracticeAdoptionPreview }) {
	return (
		<Item variant="muted" size="sm" role="listitem">
			<ItemContent>
				<ItemTitle>
					<FeedbackModeBadge feedbackMode={preview.initialFeedbackMode} />
				</ItemTitle>
				<ItemDescription className="line-clamp-none">
					{FEEDBACK_MODE_DESCRIPTIONS[preview.initialFeedbackMode]}
				</ItemDescription>
			</ItemContent>
		</Item>
	);
}

function groupTitle(preview: PracticeAdoptionPreview): string {
	if (preview.group.disposition === "UNASSIGNED") {
		return "Stay unassigned";
	}
	const name = preview.group.definition?.name ?? preview.group.slug ?? "catalog group";
	if (preview.group.disposition === "REUSE_EXISTING_GROUP") {
		return `Join “${name}”`;
	}
	return `Create “${name}”`;
}
