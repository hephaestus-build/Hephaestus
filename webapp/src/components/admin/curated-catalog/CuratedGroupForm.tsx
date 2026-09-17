import deepEqual from "fast-deep-equal";
import { RotateCcw } from "lucide-react";
import { useState } from "react";

import type { CatalogEntryStatus, CuratedGroupRequest } from "@/api/types.gen";
import { generateSlug, isValidSlug } from "@/components/admin/practice-editor/constants";
import { GroupVisualPicker } from "@/components/admin/practice-editor/GroupVisualPicker";
import { type FormError, FormErrorSummary } from "@/components/common/FormErrorSummary";
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
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { useUnsavedChanges } from "@/hooks/use-unsaved-changes";

import { canUseHephaestusVersion } from "./curated-entry-state";
import { HephaestusVersionPanel } from "./HephaestusVersionPanel";

export interface CuratedGroupFormValue {
	slug: string;
	name: string;
	description?: string;
	icon?: string;
	color?: string;
}

export interface CuratedGroupFormInitialValue extends CuratedGroupFormValue {
	status: CatalogEntryStatus;
	shipped?: CuratedGroupRequest;
}

interface FormState {
	slug: string;
	name: string;
	description: string;
	icon: string | null;
	color: string | null;
}

interface CuratedGroupFormBaseProps {
	isPending: boolean;
	conflict?: boolean;
	onContinueWithDraft?: () => void;
	isResetPending?: boolean;
	isKeepPending?: boolean;
	onUseHephaestusVersion?: () => void;
	onKeepCurrentDefinition?: () => void;
	onSubmit: (value: CuratedGroupFormValue) => void;
	/** What "leave without saving" does. The host owns it, because only the host knows where back is. */
	cancel: React.ReactNode;
}

export type CuratedGroupFormProps = CuratedGroupFormBaseProps &
	(
		| { mode: "create"; initialData?: never }
		| { mode: "edit"; initialData: CuratedGroupFormInitialValue }
	);

const SUBMIT_LABELS = {
	create: { idle: "Create group", pending: "Creating…" },
	edit: { idle: "Save changes", pending: "Saving…" },
} as const;

function initialState(initialData?: CuratedGroupFormValue): FormState {
	return {
		slug: initialData?.slug ?? "",
		name: initialData?.name ?? "",
		description: initialData?.description ?? "",
		icon: initialData?.icon ?? null,
		color: initialData?.color ?? null,
	};
}

export function CuratedGroupForm(props: CuratedGroupFormProps) {
	const {
		mode,
		isPending,
		conflict,
		onContinueWithDraft,
		isResetPending = false,
		isKeepPending = false,
		onUseHephaestusVersion,
		cancel,
		initialData,
		onKeepCurrentDefinition,
		onSubmit,
	} = props;
	const [resetOpen, setResetOpen] = useState(false);
	const [form, setForm] = useState<FormState>(() => initialState(initialData));
	const [refusals, setRefusals] = useState(0);
	const formDisabled = isPending || isResetPending || isKeepPending;
	// `deepEqual`, not `JSON.stringify`: the latter reports a difference between two equal objects
	// whose keys happen to have been inserted in a different order, which armed the guard for edits
	// the reader never made.
	const unsavedChanges = useUnsavedChanges({
		isDirty: !deepEqual(form, initialState(initialData)),
		disabled: formDisabled,
	});

	const slugWasEdited = mode === "create" && form.slug !== generateSlug(form.name);
	const handleNameChange = (name: string) => {
		setForm((previous) => {
			const edited = mode === "create" && previous.slug !== generateSlug(previous.name);
			return { ...previous, name, ...(edited ? {} : { slug: generateSlug(name) }) };
		});
	};

	const nameError =
		refusals > 0 && form.name.trim().length < 3 ? "Name must be at least 3 characters." : undefined;
	const slugError =
		refusals > 0 && mode === "create" && !isValidSlug(form.slug)
			? "Use 3–64 lowercase letters, numbers and single hyphens."
			: undefined;
	const errorSummary: FormError[] = [
		form.name.trim().length < 3 && {
			fieldId: "group-name",
			message: "Give the group a name of at least three characters.",
		},
		mode === "create" &&
			!isValidSlug(form.slug) && {
				fieldId: "group-slug",
				message: "The identifier must be 3–64 lowercase letters, numbers and single hyphens.",
			},
	].filter((entry): entry is FormError => Boolean(entry));
	const valid = errorSummary.length === 0;
	const updateAvailable = mode === "edit" && initialData.status.state === "UPDATE_WAITING";
	const resetLabel = updateAvailable ? "Apply Hephaestus update" : "Restore Hephaestus default";
	const submitLabel = SUBMIT_LABELS[mode][isPending ? "pending" : "idle"];

	const submit = (event: React.SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (!valid) {
			setRefusals((count) => count + 1);
			const [first] = errorSummary;
			if (first) {
				requestAnimationFrame(() => document.getElementById(first.fieldId)?.focus());
			}
			return;
		}
		onSubmit({
			slug: form.slug,
			name: form.name.trim(),
			description: form.description.trim() || undefined,
			icon: form.icon ?? undefined,
			color: form.color ?? undefined,
		});
	};

	return (
		<>
			<AlertDialog open={resetOpen} onOpenChange={setResetOpen}>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>{resetLabel}?</AlertDialogTitle>
						<AlertDialogDescription>
							This replaces the customization and discards unsaved changes. It does not change
							whether workspace administrators can add the group. Existing workspace copies remain
							unchanged. Future updates apply automatically until the group is customized again.
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

			{unsavedChanges.dialog}

			<form onSubmit={submit} className="flex min-h-0 flex-1 flex-col" noValidate>
				<DrawerBody className="flex flex-col gap-8">
					<FormErrorSummary key={refusals} errors={refusals > 0 ? errorSummary : []} />

					{mode === "edit" && (
						<HephaestusVersionPanel
							status={initialData.status}
							kind="group"
							shipped={initialData.shipped}
							isResetPending={isResetPending}
							isKeepPending={isKeepPending}
							disabled={conflict ?? false}
							onUseHephaestusVersion={
								canUseHephaestusVersion(initialData.status) && onUseHephaestusVersion
									? () => setResetOpen(true)
									: undefined
							}
							onKeepCurrentDefinition={onKeepCurrentDefinition}
						/>
					)}

					{conflict === true && (
						<div className="space-y-2">
							<Alert variant="warning">
								<RotateCcw />
								<AlertTitle>This group changed while you were editing</AlertTitle>
								<AlertDescription>
									Your draft is safe. Continue with it and save to replace the latest changes, or
									leave this page and reopen the group to see them.
								</AlertDescription>
							</Alert>
							{onContinueWithDraft && (
								<Button type="button" variant="outline" size="sm" onClick={onContinueWithDraft}>
									Continue with my draft
								</Button>
							)}
						</div>
					)}

					<fieldset disabled={formDisabled} className="contents">
						{/* See `PracticeDefinitionForm`: the panel is the measure, prose keeps its own. */}
						<div className="space-y-8">
							<p className="max-w-2xl text-sm text-muted-foreground">
								Fields marked <span aria-hidden>*</span> are required.
							</p>

							<section className="space-y-4">
								<h2 className="text-lg font-semibold">General</h2>
								<FieldGroup className="gap-4">
									<Field data-invalid={nameError ? "true" : undefined}>
										<FieldLabel htmlFor="group-name">Name *</FieldLabel>
										<Input
											id="group-name"
											value={form.name}
											onChange={(event) => handleNameChange(event.target.value)}
											placeholder="e.g. Code review"
											required
											minLength={3}
											maxLength={128}
											aria-invalid={Boolean(nameError)}
											aria-describedby={nameError ? "group-name-error" : undefined}
										/>
										{nameError && <FieldError id="group-name-error">{nameError}</FieldError>}
									</Field>

									<Field data-invalid={slugError ? "true" : undefined}>
										<FieldLabel htmlFor="group-slug">
											Identifier {mode === "create" && "*"}
										</FieldLabel>
										<div className="flex items-center gap-2">
											<Input
												id="group-slug"
												value={form.slug}
												onChange={(event) =>
													setForm((previous) => ({ ...previous, slug: event.target.value }))
												}
												disabled={mode === "edit"}
												required={mode === "create"}
												minLength={3}
												maxLength={64}
												aria-invalid={Boolean(slugError)}
												aria-describedby={
													["group-slug-description", slugError ? "group-slug-error" : undefined]
														.filter(Boolean)
														.join(" ") || undefined
												}
											/>
											{slugWasEdited && (
												<Button
													type="button"
													variant="ghost"
													size="icon-sm"
													onClick={() =>
														setForm((previous) => ({
															...previous,
															slug: generateSlug(previous.name),
														}))
													}
													aria-label="Reset to generated identifier"
												>
													<RotateCcw className="size-3.5" aria-hidden />
												</Button>
											)}
										</div>
										<FieldDescription id="group-slug-description">
											Used in URLs and integrations. It can’t be changed later.
										</FieldDescription>
										{slugError && <FieldError id="group-slug-error">{slugError}</FieldError>}
									</Field>

									<Field>
										<FieldLabel htmlFor="group-description">Description</FieldLabel>
										<Textarea
											id="group-description"
											value={form.description}
											rows={3}
											onChange={(event) =>
												setForm((previous) => ({ ...previous, description: event.target.value }))
											}
											placeholder="e.g. Reviewing a change so problems surface early"
											maxLength={500}
											aria-describedby="group-description-help"
										/>
										<FieldDescription id="group-description-help">
											What this group develops, in the words a developer would use.
										</FieldDescription>
									</Field>
								</FieldGroup>
							</section>

							<section className="space-y-4">
								<h2 className="text-lg font-semibold">Presentation</h2>
								<FieldGroup className="gap-4">
									<Field>
										<FieldLabel htmlFor="group-appearance">Appearance</FieldLabel>
										<GroupVisualPicker
											id="group-appearance"
											describedBy="group-appearance-help"
											name={form.name}
											icon={form.icon}
											color={form.color}
											onChange={(patch) =>
												setForm((previous) => ({
													...previous,
													...(patch.icon === undefined ? {} : { icon: patch.icon }),
													...(patch.color === undefined ? {} : { color: patch.color }),
												}))
											}
											disabled={formDisabled}
										/>
										<FieldDescription id="group-appearance-help">
											New workspace copies use this icon and color.
										</FieldDescription>
									</Field>
								</FieldGroup>
							</section>
						</div>
					</fieldset>
				</DrawerBody>

				<DrawerFooter>
					{cancel}
					<Button type="submit" disabled={formDisabled || conflict}>
						{isPending && <Spinner className="size-4" />}
						{submitLabel}
					</Button>
				</DrawerFooter>
			</form>
		</>
	);
}
