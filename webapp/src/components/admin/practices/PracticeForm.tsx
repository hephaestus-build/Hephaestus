import { Link } from "@tanstack/react-router";
import { type ReactNode, useState } from "react";
import { currentDefinition } from "@/components/admin/practices/practice-definition-fields";

import { cn } from "cn";
import type {
	CreatePracticeRequest,
	Practice,
	PracticeDefinitionOptions,
	PracticeGroup,
	PracticeVersion,
	UpdatePracticeRequest,
} from "@/api/types.gen";
import {
	PracticeDefinitionForm,
	type PracticeDefinitionValue,
} from "@/components/admin/practice-editor/PracticeDefinitionForm";
import { buttonVariants } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

interface PracticeFormCreateProps {
	mode: "create";
	workspaceSlug: string;
	groups: PracticeGroup[];
	/** Rejects when the save failed, which is what keeps the unsaved-changes guard honest. */
	onSubmit: (request: CreatePracticeRequest) => void | Promise<void>;
	isPending: boolean;
	definitionOptions: PracticeDefinitionOptions;
	initialData?: never;
	notice?: never;
}

interface PracticeFormEditProps {
	mode: "edit";
	workspaceSlug: string;
	initialData: Practice;
	groups: PracticeGroup[];
	/**
	 * The complete definition, pinned to the version it was edited from, and the group the form
	 * chose. Where it sits is not part of the definition, so the host moves the practice separately.
	 * Rejects when the save failed, which is what keeps the unsaved-changes guard honest.
	 */
	onSubmit: (request: UpdatePracticeRequest, groupSlug: string | null) => void | Promise<void>;
	isPending: boolean;
	definitionOptions: PracticeDefinitionOptions;
	notice?: ReactNode;
}

interface PracticeFormHostProps {
	cancel: ReactNode;
	/** The host’s alert for a failed refresh while the draft stays open. */
	refreshAlert?: ReactNode;
}

export type PracticeFormProps = (PracticeFormCreateProps | PracticeFormEditProps) &
	PracticeFormHostProps;

function asDefinitionValue(practice: Practice, version: PracticeVersion): PracticeDefinitionValue {
	return {
		slug: practice.slug,
		kind: practice.kind,
		groupSlug: practice.groupSlug,
		definition: currentDefinition({ slug: practice.slug, currentVersion: version }),
	};
}

export function PracticeForm(props: PracticeFormProps) {
	if (props.mode === "edit") {
		return <EditPracticeForm {...props} />;
	}
	const { workspaceSlug, groups, isPending, definitionOptions, cancel, refreshAlert, onSubmit } =
		props;
	return (
		<PracticeDefinitionForm
			beforeFields={refreshAlert}
			mode="create"
			groups={groups}
			isPending={isPending}
			definitionOptions={definitionOptions}
			cancelAction={cancel}
			practiceScriptModelsLink={modelsLink(workspaceSlug)}
			// Preserve a void return; the unsaved-changes guard tracks only an actual save promise.
			onSubmit={({ slug, kind, groupSlug, definition }): void | Promise<void> =>
				onSubmit({ slug, kind, groupSlug, definition, position: null })
			}
		/>
	);
}

function modelsLink(workspaceSlug: string) {
	return <Link to="/w/$workspaceSlug/admin/models" params={{ workspaceSlug }} />;
}

function EditPracticeForm({
	workspaceSlug,
	groups,
	isPending,
	definitionOptions,
	cancel,
	refreshAlert,
	initialData: practice,
	onSubmit,
	notice,
}: PracticeFormEditProps & PracticeFormHostProps) {
	// Pin saves to the version that seeded the draft so background refreshes cannot overwrite newer edits.
	const [seeded] = useState(practice.currentVersion);
	return (
		<PracticeDefinitionForm
			mode="edit"
			initialData={asDefinitionValue(practice, seeded)}
			groups={groups}
			isPending={isPending}
			definitionOptions={definitionOptions}
			cancelAction={cancel}
			beforeFields={
				<>
					{refreshAlert}
					{notice}
				</>
			}
			afterFields={
				<>
					<Separator />
					<section className="space-y-4">
						<div>
							<h2 className="text-lg font-semibold">What the reviews recorded</h2>
							<p className="text-sm text-muted-foreground">
								How its observations turned out and what feedback cited them, with the observations
								most worth acting on. Saving keeps them: the change becomes version{" "}
								{seeded.number + 1}.
							</p>
						</div>
						<Link
							to="/w/$workspaceSlug/admin/practices/reviews/results"
							params={{ workspaceSlug }}
							search={{ practiceSlug: [practice.slug] }}
							className={cn(buttonVariants({ variant: "outline" }), "w-full sm:w-auto")}
						>
							See review outcomes
						</Link>
					</section>
				</>
			}
			practiceScriptModelsLink={modelsLink(workspaceSlug)}
			onSubmit={({ groupSlug, definition }): void | Promise<void> =>
				onSubmit({ definition, expectedVersionId: seeded.id }, groupSlug)
			}
		/>
	);
}
