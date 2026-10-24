import { Link } from "@tanstack/react-router";

import type { Practice, PracticeDefinitionOptions, PracticeVersion } from "@/api/types.gen";
import { PRACTICE_DEFINITION_FIELDS } from "@/components/admin/practices/practice-definition-fields";
import { practiceFormLevel } from "@/components/admin/practices/practice-search";
import { PracticeDefinitionSkeleton } from "@/components/admin/practices/PracticeSkeletons";
import { WorkspacePracticeDefinition } from "@/components/admin/practices/WorkspacePracticeDefinition";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { StatusBadge } from "@/components/common/StatusBadge";
import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { SectionStack } from "@/components/layout/SectionStack";
import { FEEDBACK_MODE_DEFS } from "@/components/practice-vocabulary/feedback-mode-defs";
import { FeedbackModeSourceNote } from "@/components/practice-vocabulary/FeedbackModeSourceNote";
import { DrawerBody } from "@/components/ui/drawer";
import { feedbackModeSourceOf } from "@/lib/practice-feedback-mode";
import { hasText } from "@/lib/text";

import { levelFoldKey } from "./level-folds";
import {
	type SheetEvent,
	SheetHistory,
	SheetSection,
	SheetStatusLine,
	SheetTechnical,
} from "./RecordSheet";
import { ReviewLevelHeader } from "./ReviewLevelHeader";

export interface PracticeLevelProps {
	workspaceSlug: string;
	nested?: boolean;
	path: LevelPath;
	/**
	 * The practice as Practice setup defines it, with the options its definition is read against;
	 * `practice` is `undefined` for one the workspace no longer has.
	 */
	practice: PanelState<{
		practice: Practice | undefined;
		definitionOptions: PracticeDefinitionOptions;
		groupName?: string;
	}>;
	/** Every stored version of its definition, newest first, with what changed in each. */
	versions?: PanelState<{ versions: PracticeVersion[] }>;
}

export function PracticeLevel({
	workspaceSlug,
	nested,
	path,
	practice: state,
	versions,
}: PracticeLevelProps) {
	const practice = state.status === "ready" ? state.practice : undefined;
	const groupName = state.status === "ready" ? state.groupName : undefined;
	const header = (
		<ReviewLevelHeader
			nested={nested}
			path={path}
			kind="practice"
			loading={state.status === "loading"}
			chips={
				practice && (
					<SheetStatusLine
						status={<StatusBadge def={FEEDBACK_MODE_DEFS[practice.feedbackMode.effective]} />}
						flag={
							<FeedbackModeSourceNote
								source={feedbackModeSourceOf(practice.feedbackMode, "PRACTICE", groupName ?? null)}
								className="text-muted-foreground"
							/>
						}
						at={
							practice.updatedAt === null
								? { label: "created", date: practice.createdAt }
								: { label: "changed", date: practice.updatedAt }
						}
					/>
				)
			}
			title={practice && (practice.currentVersion.name ?? practice.slug)}
			description={
				practice && (
					<InlineLink
						render={
							<Link
								to="/w/$workspaceSlug/admin/practices"
								params={{ workspaceSlug }}
								search={{ detail: [detailStackKey(practiceFormLevel(practice.slug))] }}
							/>
						}
					>
						Edit in Practice setup
					</InlineLink>
				)
			}
		/>
	);
	if (state.status !== "ready") {
		return (
			<>
				{header}
				<DrawerBody className="pt-2">
					{state.status === "error" ? (
						<QueryErrorAlert
							error={state.error}
							title="We could not load this practice"
							onRetry={state.onRetry}
						/>
					) : (
						<PracticeDefinitionSkeleton />
					)}
				</DrawerBody>
			</>
		);
	}
	if (practice === undefined) {
		return (
			<>
				{header}
				<DrawerBody className="pt-2">
					<p className="text-sm text-muted-foreground">
						This workspace no longer has this practice.
					</p>
				</DrawerBody>
			</>
		);
	}
	return (
		<>
			{header}
			<DrawerBody className="pt-2">
				<SectionStack>
					<SheetSection part="what" foldKey={levelFoldKey("practice", "what")}>
						<WorkspacePracticeDefinition
							practice={practice}
							definitionOptions={state.definitionOptions}
							groupName={groupName}
							showStanding={false}
							headingLevel={4}
						/>
					</SheetSection>
					<SheetSection
						part="state"
						foldKey={versions?.status === "error" ? undefined : levelFoldKey("practice", "state")}
					>
						<SheetHistory
							events={[
								...practiceHistory(practice),
								...(versions?.status === "ready" ? versions.versions.map(versionEvent) : []),
							]}
						/>
						{versions?.status === "error" && (
							<QueryErrorAlert
								error={versions.error}
								title="We could not load the versions of this practice"
								onRetry={versions.onRetry}
							/>
						)}
					</SheetSection>
					<SheetTechnical
						foldKey={levelFoldKey("practice", "technical")}
						facts={[
							{ label: "Slug", value: <code>{practice.slug}</code>, copy: practice.slug },
							{ label: "Practice ID", value: practice.id, copy: practice.id },
							{
								label: "Group",
								value: hasText(practice.groupSlug) ? (
									<code>{practice.groupSlug}</code>
								) : (
									"Unassigned"
								),
							},
							{ label: "Current version", value: String(practice.currentVersion.number) },
							{
								label: "Practice script",
								value: hasText(practice.currentVersion.definition?.script)
									? "Written for this practice"
									: "Built-in",
							},
						]}
					/>
				</SectionStack>
			</DrawerBody>
		</>
	);
}

function practiceHistory(practice: Practice): SheetEvent[] {
	const events: SheetEvent[] = [
		{
			key: "created",
			at: practice.createdAt,
			title: "Created",
			detail:
				practice.catalogOrigin.catalogSlug === null
					? "Written in this workspace."
					: `Adopted from the catalog (${practice.catalogOrigin.catalogSlug}).`,
		},
	];
	if (
		practice.updatedAt !== null &&
		practice.updatedAt.getTime() !== practice.createdAt.getTime()
	) {
		events.push({ key: "changed", at: practice.updatedAt, title: "Last changed" });
	}
	return events;
}

/** One version of the definition: what changed in it, and whether what a review checks changed. */
function versionEvent(version: PracticeVersion): SheetEvent {
	const changed = version.changed.map((field) => PRACTICE_DEFINITION_FIELDS[field].label);
	let detail: string | undefined;
	if (version.number === 1) {
		detail = "The first version of the definition.";
	} else if (changed.length === 0) {
		detail = "Recorded again without a change to the definition.";
	} else {
		detail = `Changed: ${changed.join(", ")}.`;
	}
	return {
		key: `version-${version.id}`,
		at: version.createdAt,
		title: `Version ${version.number}`,
		detail: (
			<>
				<span className="block">{detail}</span>
				{version.reviewChanged && (
					<span className="block">
						What a review checks changed, so earlier observations no longer reflect it.
					</span>
				)}
			</>
		),
	};
}
