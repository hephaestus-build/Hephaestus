import { Link } from "@tanstack/react-router";

import { cn } from "cn";
import type {
	CreatePracticeRequest,
	Practice,
	PracticeDefinitionOptions,
	PracticeEvidenceOutcome,
	PracticeGroup,
	UpdatePracticeRequest,
} from "@/api/types.gen";
import { soleBinding } from "@/components/admin/practice-catalog/bindings";
import {
	PracticeDefinitionForm,
	type PracticeDefinitionValue,
} from "@/components/admin/practice-catalog/PracticeDefinitionForm";
import { PracticeAutomatedReviewValidationSummary } from "@/components/admin/practice-catalog/PracticeEvidenceSummary";
import { buttonVariants } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

interface PracticeFormCreateProps {
	mode: "create";
	workspaceSlug: string;
	groups: PracticeGroup[];
	/** Rejects when the save failed, which is what keeps the unsaved-changes guard honest. */
	onSubmit: (data: CreatePracticeRequest, groupSlug: string | null) => void | Promise<void>;
	isPending: boolean;
	definitionOptions: PracticeDefinitionOptions;
	initialData?: never;
}

interface PracticeFormEditProps {
	mode: "edit";
	workspaceSlug: string;
	initialData: Practice;
	groups: PracticeGroup[];
	/** Rejects when the save failed, which is what keeps the unsaved-changes guard honest. */
	onSubmit: (
		slug: string,
		data: UpdatePracticeRequest,
		groupSlug: string | null,
	) => void | Promise<void>;
	isPending: boolean;
	definitionOptions: PracticeDefinitionOptions;
	/** Absent until the practice has been reviewed at least once. */
	evidenceOutcome?: PracticeEvidenceOutcome;
}

/** What "leave without saving" does. The host owns it, because only the host knows where back is. */
interface PracticeFormHostProps {
	cancel: React.ReactNode;
}

export type PracticeFormProps = (PracticeFormCreateProps | PracticeFormEditProps) &
	PracticeFormHostProps;

function asDefinitionValue(practice: Practice): PracticeDefinitionValue {
	return {
		slug: practice.slug,
		name: practice.name,
		bindings: [soleBinding(practice.bindings)],
		criteria: practice.criteria,
		...(practice.groupSlug ? { groupSlug: practice.groupSlug } : {}),
		...(practice.whyItMatters ? { whyItMatters: practice.whyItMatters } : {}),
		...(practice.whatGoodLooksLike ? { whatGoodLooksLike: practice.whatGoodLooksLike } : {}),
		...(practice.precomputeScript ? { precomputeScript: practice.precomputeScript } : {}),
		automatedReviewPolicy: practice.automatedReviewPolicy,
	};
}

export function PracticeForm(props: PracticeFormProps) {
	const { mode, workspaceSlug, groups, isPending, initialData, definitionOptions, cancel } = props;
	const submit = (value: PracticeDefinitionValue) => {
		const { groupSlug, ...definition } = value;
		if (props.mode === "create") {
			return props.onSubmit(definition, groupSlug ?? null);
		}

		const clear: NonNullable<UpdatePracticeRequest["clear"]> = [];
		if (!definition.precomputeScript) clear.push("PRECOMPUTE_SCRIPT");
		if (!definition.whyItMatters) clear.push("WHY_IT_MATTERS");
		if (!definition.whatGoodLooksLike) clear.push("WHAT_GOOD_LOOKS_LIKE");
		return props.onSubmit(
			props.initialData.slug,
			{
				name: definition.name,
				criteria: definition.criteria,
				bindings: definition.bindings,
				whyItMatters: definition.whyItMatters,
				whatGoodLooksLike: definition.whatGoodLooksLike,
				precomputeScript: definition.precomputeScript,
				automatedReviewPolicy: definition.automatedReviewPolicy,
				clear: clear.length > 0 ? clear : undefined,
			},
			groupSlug ?? null,
		);
	};
	const reviewResults =
		mode === "edit" ? (
			<>
				<Separator />
				<section className="space-y-4">
					<div>
						<h2 className="text-lg font-semibold">What the author declared</h2>
						<p className="text-sm text-muted-foreground">
							The requirements above are the author's own claim about this practice. Nobody has
							checked them independently, and nothing here says the observations recorded under it
							are correct. The digests record the exact rules that were declared, so a later change
							to them is visible rather than silent.
						</p>
					</div>
					<PracticeAutomatedReviewValidationSummary
						validation={initialData.automatedReviewValidation}
					/>
				</section>
				<Separator />
				<section className="space-y-4">
					<div>
						<h2 className="text-lg font-semibold">What the reviews observed</h2>
						<p className="text-sm text-muted-foreground">
							Every observation recorded for this practice across the workspace.
						</p>
					</div>
					<Link
						to="/w/$workspaceSlug/admin/practices/reviews/observations"
						params={{ workspaceSlug }}
						search={{ practiceSlug: [initialData.slug] }}
						className={cn(buttonVariants({ variant: "outline" }), "w-full sm:w-auto")}
					>
						View observations
					</Link>
				</section>
			</>
		) : undefined;

	return mode === "create" ? (
		<PracticeDefinitionForm
			mode="create"
			groups={groups}
			isPending={isPending}
			definitionOptions={definitionOptions}
			cancelAction={cancel}
			onSubmit={submit}
		/>
	) : (
		<PracticeDefinitionForm
			mode="edit"
			initialData={asDefinitionValue(initialData)}
			groups={groups}
			isPending={isPending}
			definitionOptions={definitionOptions}
			cancelAction={cancel}
			afterFields={reviewResults}
			evidenceOutcome={props.evidenceOutcome}
			onSubmit={submit}
		/>
	);
}
