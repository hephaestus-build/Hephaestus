import { RotateCcw } from "lucide-react";
import { useId } from "react";

import { cn } from "cn";
import type {
	CatalogEntryStatus,
	CuratedGroupRequest,
	CuratedPracticeDefinition,
	PracticeDefinitionOptions,
} from "@/api/types.gen";
import { PracticeEvidenceSummary } from "@/components/admin/practice-catalog/PracticeEvidenceSummary";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Spinner } from "@/components/ui/spinner";
import { artifactKindLabel } from "@/lib/artifact-kinds";
import { humanizeToken } from "@/lib/humanize";

import {
	canKeepCurrentDefinition,
	canUseHephaestusVersion,
	curatedEntryCopy,
} from "./curated-entry-state";

type ShippedDefinition = Readonly<Record<string, unknown>>;

interface HephaestusVersionPanelBaseProps {
	status: CatalogEntryStatus;
	groupNames?: Readonly<Record<string, string>>;
	isResetPending: boolean;
	isKeepPending?: boolean;
	disabled: boolean;
	onUseHephaestusVersion?: () => void;
	onKeepCurrentDefinition?: () => void;
}

export type HephaestusVersionPanelProps = HephaestusVersionPanelBaseProps &
	(
		| {
				kind: "practice";
				shipped?: CuratedPracticeDefinition;
				definitionOptions: PracticeDefinitionOptions;
		  }
		| { kind: "group"; shipped?: CuratedGroupRequest }
	);

const GROUP_FIELDS = {
	name: "Name",
	description: "Description",
	icon: "Icon",
	color: "Color",
} satisfies Record<keyof CuratedGroupRequest, string>;

const PRACTICE_FIELDS = {
	name: "Name",
	artifactKind: "Work reviewed",
	groupSlug: "Group",
	criteria: "What to look for",
	whyItMatters: "Why it matters",
	whatGoodLooksLike: "What good looks like",
	precomputeScript: "Static analysis",
	// The occasions and their evidence render under a heading of their own, which this must not repeat.
	automatedReviewPolicy: "How it is reviewed",
} satisfies Record<
	Exclude<keyof CuratedPracticeDefinition, "automatedReviewValidation" | "bindings">,
	string
>;

function displayValue(
	field: string,
	value: unknown,
	groupNames: Readonly<Record<string, string>>,
): string {
	if (value === null || value === undefined || value === "") {
		return field === "groupSlug" ? "Unassigned" : "Not set";
	}
	if (field === "artifactKind") {
		return artifactKindLabel(typeof value === "string" ? value : JSON.stringify(value));
	}
	if (field === "groupSlug" && typeof value === "string") {
		return groupNames[value] ?? "Group no longer exists";
	}
	if ((field === "icon" || field === "color") && typeof value === "string")
		return humanizeToken(value);
	if (Array.isArray(value)) return value.join("\n");
	if (typeof value === "string") return value;
	if (typeof value === "number" || typeof value === "boolean") return String(value);
	// A nested object has no readable `toString`; show its shape rather than "[object Object]".
	return JSON.stringify(value);
}

export function HephaestusVersionPanel(props: HephaestusVersionPanelProps) {
	const {
		status,
		kind,
		shipped,
		groupNames = {},
		isResetPending,
		isKeepPending = false,
		disabled,
		onUseHephaestusVersion,
		onKeepCurrentDefinition,
	} = props;
	const shippedDefinition: ShippedDefinition | undefined = shipped;
	const shippedPractice = props.kind === "practice" ? props.shipped : undefined;
	const shippedDefinitionOptions =
		props.kind === "practice" && shippedPractice
			? props.definitionOptions.workTypes.find(
					(option) => option.artifactKind === shippedPractice.artifactKind,
				)
			: undefined;
	const headingId = useId();
	const copy = curatedEntryCopy(status, kind);
	const canReset = canUseHephaestusVersion(status) && onUseHephaestusVersion !== undefined;
	const canKeep = canKeepCurrentDefinition(status) && onKeepCurrentDefinition !== undefined;
	const busy = isResetPending || isKeepPending || disabled;
	const updateAvailable = status.state === "UPDATE_WAITING";
	const viewLabel = updateAvailable ? "Review Hephaestus update" : "View Hephaestus default";
	const useLabel = updateAvailable ? "Apply Hephaestus update" : "Restore Hephaestus default";
	const keepLabel =
		status.state === "NO_LONGER_SHIPPED" ? "Keep saved version as custom" : "Keep saved version";

	return (
		<section
			aria-labelledby={headingId}
			className={cn(
				"rounded-lg border p-4 text-sm",
				copy.tone === "attention" ? "border-warning/50 bg-warning/5" : "bg-card",
			)}
		>
			<div className="flex items-start gap-3">
				<RotateCcw className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
				<div className="min-w-0 flex-1">
					<h2 id={headingId} className="font-medium">
						{copy.label}
					</h2>
					<p className="mt-1 text-muted-foreground">{copy.detail}</p>

					{shippedDefinition && (
						<Collapsible className="mt-3 w-full">
							<CollapsibleTrigger
								render={
									<Button type="button" variant="outline" size="sm">
										{viewLabel}
									</Button>
								}
							/>
							<CollapsibleContent
								render={<dl />}
								className="mt-2 space-y-3 rounded-md border bg-muted/40 p-3"
							>
								{Object.entries(kind === "group" ? GROUP_FIELDS : PRACTICE_FIELDS).map(
									([field, label]) => (
										<div key={field} className="space-y-1">
											<dt className="font-medium text-xs">{label}</dt>
											<dd
												className={cn(
													"whitespace-pre-wrap break-words text-muted-foreground text-xs",
													field === "precomputeScript" && "font-mono",
												)}
											>
												{field === "automatedReviewPolicy" && shippedPractice ? (
													shippedDefinitionOptions ? (
														<PracticeEvidenceSummary
															policy={shippedPractice.automatedReviewPolicy}
															bindings={shippedPractice.bindings}
															validation={shippedPractice.automatedReviewValidation}
															sources={shippedDefinitionOptions.allowedSources}
															signals={shippedDefinitionOptions.signals}
															workTypeLabel={artifactKindLabel(shippedPractice.artifactKind)}
														/>
													) : (
														"Evidence details are unavailable for this work type."
													)
												) : (
													displayValue(field, shippedDefinition[field], groupNames)
												)}
											</dd>
										</div>
									),
								)}
							</CollapsibleContent>
						</Collapsible>
					)}

					{(canReset || canKeep) && (
						<div className="mt-3 flex flex-wrap items-center gap-2">
							{canReset && (
								<Button
									type="button"
									variant="outline"
									size="sm"
									disabled={busy}
									onClick={onUseHephaestusVersion}
								>
									{isResetPending && <Spinner className="mr-1.5 size-3.5" />}
									{isResetPending ? `${useLabel}…` : useLabel}
								</Button>
							)}
							{canKeep && (
								<Button
									type="button"
									variant="ghost"
									size="sm"
									disabled={busy}
									onClick={onKeepCurrentDefinition}
								>
									{isKeepPending && <Spinner className="mr-1.5 size-3.5" />}
									{isKeepPending ? `${keepLabel}…` : keepLabel}
								</Button>
							)}
						</div>
					)}
				</div>
			</div>
		</section>
	);
}
