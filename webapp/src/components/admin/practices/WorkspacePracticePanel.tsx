import { Link } from "@tanstack/react-router";
import { ClipboardCheck, Inbox, Pencil, RotateCcw } from "lucide-react";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import { cn } from "cn";
import type {
	Practice,
	PracticeDefinitionOptions,
	PracticeScriptSummary,
	PracticeScriptAsOf,
	PracticeScriptNeed,
} from "@/api/types.gen";
import { reviewLevel } from "@/components/admin/practice-reviews/review-levels";
import { canResetToCatalog } from "@/components/admin/practices/CatalogOriginBadge";
import {
	PRACTICE_SETUP_LEVEL_LABELS,
	practiceFormLevel,
} from "@/components/admin/practices/practice-search";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { WorkspacePracticeDefinition } from "@/components/admin/practices/WorkspacePracticeDefinition";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { PracticeScriptModelLink } from "@/components/practice-trace/PracticeScriptModelLink";
import {
	AGENT_PURPOSE_DEFS,
	type AgentPurpose,
	assignModelLabel,
	isPracticeScriptPurpose,
} from "@/components/practice-vocabulary/agent-purpose-defs";
import {
	type DataHandlingTier,
	tierMembersPhrase,
} from "@/components/practice-vocabulary/data-handling-defs";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { ModelKindMark } from "@/components/practice-vocabulary/ModelKindMark";
import { practiceScriptNeedBadge } from "@/components/practice-vocabulary/practice-script-need-defs";
import { PURPOSE_STATUS_DEFS } from "@/components/practice-vocabulary/purpose-status-defs";
import { UnmetTiers } from "@/components/practice-vocabulary/UnmetTiers";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemTitle,
} from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { formatShortDay } from "@/lib/dates";
import { capitalise } from "@/lib/text";

export type WorkspacePracticeState = PanelState<{
	practice: Practice;
	definitionOptions: PracticeDefinitionOptions;
	/** The group's display name, for the inheritance sentence. Absent when unassigned. */
	groupName?: string;
}>;

/** Needs apply only to the current script version; earlier runs must not describe other code. */
export type PracticeScriptNeedsState = PanelState<{ summary: PracticeScriptSummary | undefined }>;

export interface WorkspacePracticePanelProps {
	workspaceSlug: string;
	state: WorkspacePracticeState;
	practiceScript: PracticeScriptNeedsState;
	nested?: boolean;
	/** Where the level sits, from the drawer. */
	path: LevelPath;
	onResetToCatalog: () => void;
}

const SLOT_PURPOSE = {
	chat: "PRACTICE_REVIEW",
	decision: "PRACTICE_DECISION",
	embedding: "PRACTICE_EMBEDDING",
	reranking: "PRACTICE_RERANKING",
} as const satisfies Record<PracticeScriptNeed["slot"], AgentPurpose>;

export function WorkspacePracticePanel({
	workspaceSlug,
	state,
	practiceScript,
	nested,
	path,
	onResetToCatalog,
}: WorkspacePracticePanelProps) {
	if (state.status !== "ready") {
		return (
			<>
				<LevelHeader
					nested={nested}
					path={path}
					current={PRACTICE_SETUP_LEVEL_LABELS.practice}
					loading={state.status === "loading"}
				/>
				<DrawerBody>
					{state.status === "loading" ? (
						<PracticeDefinitionSkeleton />
					) : (
						<QueryErrorAlert
							error={state.error}
							title="We could not load this practice"
							onRetry={state.onRetry}
						/>
					)}
				</DrawerBody>
			</>
		);
	}

	const { practice, definitionOptions, groupName } = state;
	const definition = currentDefinition(practice);
	const scripted = definition.script !== null;
	const blocking = scripted ? blockingNeeds(practiceScript) : [];

	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={PRACTICE_SETUP_LEVEL_LABELS.practice}
				mark={<GroupPill size="lg" slug={practice.groupSlug} name={groupName} />}
				title={definition.name}
				description={<WorkTypeLabel itemKind={practice.kind} />}
			/>

			<DrawerBody>
				<WorkspacePracticeDefinition
					practice={practice}
					definitionOptions={definitionOptions}
					groupName={groupName}
					originActions={
						canResetToCatalog(practice.catalogOrigin) && (
							<div className="mt-2 flex flex-wrap gap-2">
								{practice.catalogOrigin.state === "UPDATE_WAITING" && (
									<Button
										size="sm"
										variant="outline"
										nativeButton={false}
										render={
											<Link
												to="/w/$workspaceSlug/admin/practices/releases"
												params={{ workspaceSlug }}
											/>
										}
									>
										<Inbox /> Catalog updates
									</Button>
								)}
								<Button size="sm" variant="outline" onClick={onResetToCatalog}>
									<RotateCcw /> Reset to catalog
								</Button>
							</div>
						)
					}
					notice={
						blocking.length > 0 && (
							<BlockingNeedsAlert workspaceSlug={workspaceSlug} needs={blocking} />
						)
					}
					practiceScript={
						scripted && <PracticeScriptNeeds workspaceSlug={workspaceSlug} state={practiceScript} />
					}
				/>
			</DrawerBody>

			<DrawerFooter>
				<Link
					to="/w/$workspaceSlug/admin/practices/reviews/results"
					params={{ workspaceSlug }}
					search={{ practiceSlug: [practice.slug] }}
					className={cn(buttonVariants({ variant: "outline" }), "w-full sm:w-auto")}
				>
					<ClipboardCheck /> See review outcomes
				</Link>
				<DetailStackLink
					entry={practiceFormLevel(practice.slug)}
					className={cn(buttonVariants(), "w-full sm:w-auto")}
				>
					<Pencil /> Edit practice
				</DetailStackLink>
			</DrawerFooter>
		</>
	);
}

function currentNeeds(state: PracticeScriptNeedsState): PracticeScriptNeed[] {
	if (state.status !== "ready" || state.summary === undefined) {
		return [];
	}
	const { summary } = state;
	return summary.scriptChanged || summary.asOf === null ? [] : summary.needs;
}

// Only unserved required direct models block the script; the review model is admitted separately.
function blockingNeeds(state: PracticeScriptNeedsState): PracticeScriptNeed[] {
	return currentNeeds(state).filter(
		(need) =>
			need.need === "required" &&
			isPracticeScriptPurpose(SLOT_PURPOSE[need.slot]) &&
			need.unmetTiers.length > 0,
	);
}

function workOf(tiers: readonly DataHandlingTier[]): string {
	const members = tierMembersPhrase(tiers);
	return tiers.includes("UNDECLARED") ? `the work of ${members}` : `${members}’ work`;
}

const { badgeVariant: BLOCKING_TONE, icon: BlockingIcon } = PURPOSE_STATUS_DEFS.NEEDS_ATTENTION;

// Static content must not announce itself as a new alert when the panel opens.
function BlockingNeedsAlert({
	workspaceSlug,
	needs,
}: {
	workspaceSlug: string;
	needs: PracticeScriptNeed[];
}) {
	return (
		<Alert variant={BLOCKING_TONE} role="note">
			<BlockingIcon aria-hidden />
			<AlertDescription>
				{needs.map((need) => {
					const purpose = SLOT_PURPOSE[need.slot];
					return (
						<p key={need.slot}>
							The practice script does not run for {workOf(need.unmetTiers)}: no{" "}
							{AGENT_PURPOSE_DEFS[purpose].noun} is assigned for them.{" "}
							<PracticeScriptModelLink workspaceSlug={workspaceSlug} purpose={purpose}>
								{assignModelLabel(purpose)}
							</PracticeScriptModelLink>
						</p>
					);
				})}
			</AlertDescription>
		</Alert>
	);
}

interface PracticeScriptNeedsProps {
	workspaceSlug: string;
	state: PracticeScriptNeedsState;
}

function PracticeScriptNeeds({ workspaceSlug, state }: PracticeScriptNeedsProps) {
	if (state.status === "loading") {
		return (
			<div className="space-y-2" aria-hidden>
				<Skeleton className="h-4 w-64 max-w-full" />
				<Skeleton className="h-4 w-48 max-w-full" />
			</div>
		);
	}
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="We could not load what this script needs"
				onRetry={state.onRetry}
			/>
		);
	}
	const { summary } = state;
	if (summary?.scriptChanged === true) {
		return <NeedsNote>The script changed. Its models show after its next review.</NeedsNote>;
	}
	if (summary === undefined || summary.asOf === null) {
		return <NeedsNote>Its models show after its next review.</NeedsNote>;
	}
	const models = summary.needs.filter((need) => isPracticeScriptPurpose(SLOT_PURPOSE[need.slot]));
	return (
		<div className="space-y-2">
			{models.length === 0 ? (
				<NeedsNote>
					{summary.needs.length === 0
						? "It uses no model."
						: `It uses only the ${AGENT_PURPOSE_DEFS.PRACTICE_REVIEW.noun}.`}
				</NeedsNote>
			) : (
				<div className="rounded-lg border">
					<ItemGroup aria-label="Models the script uses" className="gap-0">
						{models.map((need) => (
							<NeedItem key={need.slot} workspaceSlug={workspaceSlug} need={need} />
						))}
					</ItemGroup>
				</div>
			)}
			<AsOfReview workspaceSlug={workspaceSlug} asOf={summary.asOf} />
		</div>
	);
}

function NeedsNote({ children }: { children: string }) {
	return <p className="text-sm text-muted-foreground">{children}</p>;
}

function NeedItem({ workspaceSlug, need }: { workspaceSlug: string; need: PracticeScriptNeed }) {
	const unmet = need.unmetTiers.length > 0;
	const purpose = SLOT_PURPOSE[need.slot];
	const badge = practiceScriptNeedBadge(need.need);
	return (
		<Item variant="row" size="sm" role="listitem">
			<ItemContent>
				<ItemTitle className="flex flex-wrap items-center gap-2">
					<ModelKindMark
						purpose={purpose}
						size="md"
						name={capitalise(AGENT_PURPOSE_DEFS[purpose].noun)}
					/>
					{badge && <StatusBadge def={badge} />}
				</ItemTitle>
				{unmet && (
					<ItemDescription className="pl-10">
						<UnmetTiers tiers={need.unmetTiers} className="text-sm" />
					</ItemDescription>
				)}
			</ItemContent>
			{unmet && (
				<ItemActions className="basis-full pl-10 sm:basis-auto sm:pl-0">
					<PracticeScriptModelLink workspaceSlug={workspaceSlug} purpose={purpose}>
						{assignModelLabel(purpose)}
					</PracticeScriptModelLink>
				</ItemActions>
			)}
		</Item>
	);
}

function AsOfReview({ workspaceSlug, asOf }: { workspaceSlug: string; asOf: PracticeScriptAsOf }) {
	return (
		<p className="text-xs text-muted-foreground">
			<Link
				to="/w/$workspaceSlug/admin/practices/reviews"
				params={{ workspaceSlug }}
				search={detailSearch(reviewLevel(asOf.reviewId))}
				className="underline-offset-4 hover:text-foreground hover:underline"
			>
				As of the review on {formatShortDay(asOf.finishedAt)}
			</Link>
		</p>
	);
}
