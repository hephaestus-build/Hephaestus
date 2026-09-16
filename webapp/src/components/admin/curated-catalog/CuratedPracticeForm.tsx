import { RotateCcw } from "lucide-react";
import { useState } from "react";

import type {
	CatalogEntryStatus,
	CuratedPracticeDefinition,
	PracticeDefinitionOptions,
} from "@/api/types.gen";
import {
	PracticeDefinitionForm,
	type PracticeDefinitionValue,
} from "@/components/admin/practice-editor/PracticeDefinitionForm";
import { PracticeAutomatedReviewValidationSummary } from "@/components/admin/practice-editor/PracticeEvidenceSummary";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

import { canUseHephaestusVersion } from "./curated-entry-state";
import { HephaestusVersionPanel } from "./HephaestusVersionPanel";

export type CuratedPracticeFormValue = PracticeDefinitionValue;

export interface CuratedPracticeFormInitialValue extends CuratedPracticeFormValue {
	status: CatalogEntryStatus;
	automatedReviewPolicy: CuratedPracticeDefinition["automatedReviewPolicy"];
	automatedReviewValidation: CuratedPracticeDefinition["automatedReviewValidation"];
	shipped?: CuratedPracticeDefinition;
}

interface CuratedPracticeFormBaseProps {
	groups: readonly { slug: string; name: string }[];
	isPending: boolean;
	conflict?: boolean;
	onContinueWithDraft?: () => void;
	isResetPending?: boolean;
	isKeepPending?: boolean;
	onUseHephaestusVersion?: () => void;
	onKeepCurrentDefinition?: () => void;
	definitionOptions: PracticeDefinitionOptions;
	/** What "leave without saving" does. The host owns it, because only the host knows where back is. */
	cancel: React.ReactNode;
}

interface CuratedPracticeFormCreateProps extends CuratedPracticeFormBaseProps {
	mode: "create";
	initialData?: never;
	onSubmit: (value: CuratedPracticeFormValue) => void;
}

interface CuratedPracticeFormEditProps extends CuratedPracticeFormBaseProps {
	mode: "edit";
	initialData: CuratedPracticeFormInitialValue;
	onSubmit: (value: CuratedPracticeFormValue) => void;
}

export type CuratedPracticeFormProps =
	| CuratedPracticeFormCreateProps
	| CuratedPracticeFormEditProps;

export function CuratedPracticeForm(props: CuratedPracticeFormProps) {
	const {
		mode,
		groups,
		isPending,
		conflict,
		onContinueWithDraft,
		isResetPending = false,
		isKeepPending = false,
		onUseHephaestusVersion,
		initialData,
		definitionOptions,
		onKeepCurrentDefinition,
		cancel,
	} = props;
	const [resetOpen, setResetOpen] = useState(false);
	const canReset =
		mode === "edit" &&
		canUseHephaestusVersion(initialData.status) &&
		onUseHephaestusVersion !== undefined;
	const updateAvailable = mode === "edit" && initialData.status.state === "UPDATE_WAITING";
	const formDisabled = isResetPending || isKeepPending;
	/** The host's own banners, handed to the form so they land inside its padded, scrolling body. */
	const banners = (
		<>
			{mode === "edit" && (
				<HephaestusVersionPanel
					status={initialData.status}
					kind="practice"
					shipped={initialData.shipped}
					definitionOptions={definitionOptions}
					groupNames={Object.fromEntries(groups.map((group) => [group.slug, group.name]))}
					isResetPending={isResetPending}
					isKeepPending={isKeepPending}
					disabled={conflict ?? false}
					onUseHephaestusVersion={canReset ? () => setResetOpen(true) : undefined}
					onKeepCurrentDefinition={onKeepCurrentDefinition}
				/>
			)}

			{conflict === true && (
				<div className="space-y-2">
					<Alert variant="warning" role="alert">
						<RotateCcw />
						<AlertTitle>This practice changed while you were editing</AlertTitle>
						<AlertDescription>
							Your draft is safe. Continue with it and save to replace the latest changes, or leave
							this page and reopen the practice to see them.
						</AlertDescription>
					</Alert>
					{onContinueWithDraft && (
						<Button type="button" variant="outline" size="sm" onClick={onContinueWithDraft}>
							Continue with my draft
						</Button>
					)}
				</div>
			)}
		</>
	);

	const resetLabel = updateAvailable ? "Apply Hephaestus update" : "Restore Hephaestus default";

	return (
		<>
			<AlertDialog open={resetOpen} onOpenChange={setResetOpen}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>{resetLabel}?</AlertDialogTitle>
						<AlertDialogDescription>
							This replaces the customization and discards unsaved changes. It does not change
							whether workspace administrators can add the practice. Existing workspace copies
							remain unchanged. Future updates apply automatically until the practice is customized
							again.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel disabled={isResetPending}>Cancel</AlertDialogCancel>
						<AlertDialogAction
							disabled={isResetPending}
							onClick={() => {
								setResetOpen(false);
								onUseHephaestusVersion?.();
							}}
						>
							{isResetPending ? `${resetLabel}…` : resetLabel}
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>

			{mode === "create" ? (
				<PracticeDefinitionForm
					mode="create"
					beforeFields={banners}
					groups={groups}
					isPending={isPending}
					definitionOptions={definitionOptions}
					disabled={formDisabled}
					cancelAction={cancel}
					onSubmit={props.onSubmit}
				/>
			) : (
				<PracticeDefinitionForm
					mode="edit"
					beforeFields={banners}
					initialData={initialData}
					groups={groups}
					isPending={isPending}
					definitionOptions={definitionOptions}
					disabled={formDisabled}
					isSubmitDisabled={(conflict ?? false) || isResetPending || isKeepPending}
					cancelAction={cancel}
					afterFields={
						<>
							<Separator />
							<section className="space-y-4">
								<div>
									<h2 className="text-lg font-semibold">What the author declared</h2>
									<p className="text-sm text-muted-foreground">
										The evidence requirements above are the author’s own claim about this practice.
										Nobody has checked them independently. The digests record the exact rules that
										were declared, so a later change to them is visible rather than silent.
									</p>
								</div>
								<PracticeAutomatedReviewValidationSummary
									validation={initialData.automatedReviewValidation}
								/>
							</section>
						</>
					}
					onSubmit={props.onSubmit}
				/>
			)}
		</>
	);
}
