import { Link } from "@tanstack/react-router";
import { ClipboardCheck, Pencil } from "lucide-react";

import { cn } from "cn";
import type {
	Practice,
	PracticeDefinitionOptions,
	PracticePrecomputeSummary,
	PrecomputeAsOf,
	PrecomputeNeed,
} from "@/api/types.gen";
import { practiceLevel, reviewLevel } from "@/components/admin/practice-reviews/review-levels";
import { CatalogOriginNote } from "@/components/admin/practices/CatalogOriginBadge";
import {
	PRACTICE_SETUP_LEVEL_LABELS,
	practiceFormLevel,
} from "@/components/admin/practices/practice-search";
import { PracticeDefinitionPreview } from "@/components/admin/practices/PracticeDefinitionPreview";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { LevelHeader } from "@/components/layout/detail-drawer/LevelHeader";
import { PrecomputeModelLink } from "@/components/practice-trace/PrecomputeModelLink";
import {
	AGENT_PURPOSE_DEFS,
	assignModelLabel,
	isPrecomputePurpose,
} from "@/components/practice-vocabulary/agent-purpose-defs";
import { AUTONOMY_DEFS } from "@/components/practice-vocabulary/autonomy-defs";
import { AutonomySourceNote } from "@/components/practice-vocabulary/AutonomySourceNote";
import {
	type DataHandlingTier,
	tierMembersPhrase,
} from "@/components/practice-vocabulary/data-handling-defs";
import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { ModelKindMark } from "@/components/practice-vocabulary/ModelKindMark";
import { precomputeNeedBadge } from "@/components/practice-vocabulary/precompute-need-defs";
import { PURPOSE_STATUS_DEFS } from "@/components/practice-vocabulary/purpose-status-defs";
import { UnmetTiers } from "@/components/practice-vocabulary/UnmetTiers";
import { WorkTypeLabel } from "@/components/practice-vocabulary/WorkTypeLabel";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { buttonVariants } from "@/components/ui/button";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemTitle,
} from "@/components/ui/item";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { formatShortDay } from "@/lib/dates";
import { autonomySourceOf } from "@/lib/practice-autonomy";
import { capitalise, hasText } from "@/lib/text";

export type WorkspacePracticeState = PanelState<{
	practice: Practice;
	definitionOptions: PracticeDefinitionOptions;
	/** The group's display name, for the inheritance sentence. Absent when unassigned. */
	groupName?: string;
}>;

/**
 * What the practice's precompute script needs, from the newest review that ran its current version.
 * Ready without a summary when no review has reported on the script yet.
 */
export type PrecomputeNeedsState = PanelState<{ summary: PracticePrecomputeSummary | undefined }>;

export interface WorkspacePracticePanelProps {
	workspaceSlug: string;
	state: WorkspacePracticeState;
	/** Read only when the practice has a precompute script. */
	precompute: PrecomputeNeedsState;
	nested?: boolean;
	/** Where the level sits, from the drawer. */
	path: LevelPath;
}

/**
 * A workspace practice, read-only, so that opening one is not the same act as editing it.
 *
 * Renders the same {@link PracticeDefinitionPreview} the catalog drawer uses — `Practice` is
 * structurally a `CuratedPracticeDefinition` — so a practice reads identically whether it was met in
 * the catalog or in the workspace's own tree. Editing stays a route: a form that must ask before
 * discarding work is not a dismissible surface.
 */
export function WorkspacePracticePanel({
	workspaceSlug,
	state,
	precompute,
	nested,
	path,
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
	const autonomy = AUTONOMY_DEFS[practice.autonomy.effective];
	const autonomySource = autonomySourceOf(practice.autonomy, groupName ?? null);
	const scripted = hasText(practice.precomputeScript);
	const blocking = scripted ? blockingNeeds(precompute) : [];

	return (
		<>
			<LevelHeader
				nested={nested}
				path={path}
				current={PRACTICE_SETUP_LEVEL_LABELS.practice}
				mark={<GroupPill size="lg" slug={practice.groupSlug} name={groupName} />}
				title={practice.name}
				description={<WorkTypeLabel artifactKind={practice.artifactKind} />}
			/>

			<DrawerBody className="space-y-6">
				<ItemGroup className="gap-2">
					<CatalogOriginNote origin={practice.catalogOrigin} kind="practice" />
					<Item variant="muted" size="sm" role="listitem">
						<ItemContent>
							<ItemTitle className="flex flex-wrap items-center gap-2">
								<StatusBadge def={autonomy} />
								<AutonomySourceNote
									source={autonomySource}
									className="text-xs font-normal text-muted-foreground"
								/>
							</ItemTitle>
							<ItemDescription className="line-clamp-none">{autonomy.description}</ItemDescription>
						</ItemContent>
					</Item>
				</ItemGroup>

				{blocking.length > 0 && (
					<BlockingNeedsAlert workspaceSlug={workspaceSlug} needs={blocking} />
				)}

				<Separator />

				<PracticeDefinitionPreview
					definition={practice}
					options={definitionOptions}
					precompute={
						scripted && <PrecomputeScriptNeeds workspaceSlug={workspaceSlug} state={precompute} />
					}
				/>
			</DrawerBody>

			<DrawerFooter>
				<Link
					to="/w/$workspaceSlug/admin/practices/reviews"
					params={{ workspaceSlug }}
					search={detailSearch(practiceLevel(practice.slug))}
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

/** The current script's needs, or none while they load, failed, or describe an earlier script. */
function currentNeeds(state: PrecomputeNeedsState): PrecomputeNeed[] {
	if (state.status !== "ready" || state.summary === undefined) {
		return [];
	}
	const { summary } = state;
	return summary.scriptChanged || summary.asOf === undefined ? [] : summary.needs;
}

/**
 * The needs that stop the script for some members: a required precompute model with no assignment
 * for their tier. The review model always runs the review, so the script requiring it stops nothing.
 */
function blockingNeeds(state: PrecomputeNeedsState): PrecomputeNeed[] {
	return currentNeeds(state).filter(
		(need) =>
			need.need === "REQUIRED" && isPrecomputePurpose(need.purpose) && need.unmetTiers.length > 0,
	);
}

/** "In-house members’ work", or "the work of members who have not chosen". */
function workOf(tiers: readonly DataHandlingTier[]): string {
	const members = tierMembersPhrase(tiers);
	// The phrase ends with the unchosen members whenever it names them, and they take no apostrophe.
	return tiers.includes("UNDECLARED") ? `the work of ${members}` : `${members}’ work`;
}

/**
 * On AI models, an unmet required need makes the model's row `NEEDS_ATTENTION`. The alert takes that
 * status's tone and icon, so both surfaces show one state the same way.
 */
const { badgeVariant: BLOCKING_TONE, icon: BlockingIcon } = PURPOSE_STATUS_DEFS.NEEDS_ATTENTION;

/** Static page content, so no live role: opening the panel must not announce it as news. */
function BlockingNeedsAlert({
	workspaceSlug,
	needs,
}: {
	workspaceSlug: string;
	needs: PrecomputeNeed[];
}) {
	return (
		<Alert variant={BLOCKING_TONE} role="note">
			<BlockingIcon aria-hidden />
			<AlertDescription>
				{needs.map((need) => (
					<p key={need.purpose}>
						The precompute script does not run for {workOf(need.unmetTiers)}: no{" "}
						{AGENT_PURPOSE_DEFS[need.purpose].noun} is assigned for them.{" "}
						<PrecomputeModelLink workspaceSlug={workspaceSlug} purpose={need.purpose}>
							{assignModelLabel(need.purpose)}
						</PrecomputeModelLink>
					</p>
				))}
			</AlertDescription>
		</Alert>
	);
}

interface PrecomputeScriptNeedsProps {
	workspaceSlug: string;
	state: PrecomputeNeedsState;
}

/**
 * The models the script calls and the members no model serves, as the newest review that ran this
 * version of the script reported them. Nothing here is a health verdict: a run's own outcome is on
 * that review's trace.
 */
function PrecomputeScriptNeeds({ workspaceSlug, state }: PrecomputeScriptNeedsProps) {
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
	if (summary?.asOf === undefined) {
		return <NeedsNote>Its models show after its next review.</NeedsNote>;
	}
	// The review model always runs the review, so a row for it would have nothing to say.
	const models = summary.needs.filter((need) => isPrecomputePurpose(need.purpose));
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
							<NeedItem key={need.purpose} workspaceSlug={workspaceSlug} need={need} />
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

function NeedItem({ workspaceSlug, need }: { workspaceSlug: string; need: PrecomputeNeed }) {
	const unmet = need.unmetTiers.length > 0;
	const badge = precomputeNeedBadge(need.need);
	return (
		<Item variant="row" size="sm" role="listitem">
			<ItemContent>
				<ItemTitle className="flex flex-wrap items-center gap-2">
					<ModelKindMark
						purpose={need.purpose}
						size="md"
						name={capitalise(AGENT_PURPOSE_DEFS[need.purpose].noun)}
					/>
					{badge && <StatusBadge def={badge} />}
				</ItemTitle>
				{unmet && (
					// Indented past the mark, so the line sits under the name it is about.
					<ItemDescription className="pl-10">
						<UnmetTiers tiers={need.unmetTiers} className="text-sm" />
					</ItemDescription>
				)}
			</ItemContent>
			{unmet && (
				// Below `sm` under the line it fixes, so the name and the line keep the row's width.
				<ItemActions className="basis-full pl-10 sm:basis-auto sm:pl-0">
					<PrecomputeModelLink workspaceSlug={workspaceSlug} purpose={need.purpose}>
						{assignModelLabel(need.purpose)}
					</PrecomputeModelLink>
				</ItemActions>
			)}
		</Item>
	);
}

function AsOfReview({ workspaceSlug, asOf }: { workspaceSlug: string; asOf: PrecomputeAsOf }) {
	return (
		<p className="text-xs text-muted-foreground">
			<Link
				to="/w/$workspaceSlug/admin/practices/reviews"
				params={{ workspaceSlug }}
				search={detailSearch(reviewLevel(asOf.jobId))}
				className="underline-offset-4 hover:text-foreground hover:underline"
			>
				As of the review on {formatShortDay(asOf.finishedAt)}
			</Link>
		</p>
	);
}
