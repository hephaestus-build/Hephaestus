import { type ReactNode, useState } from "react";
import {
	LlmModelCapabilities,
	type LlmModelCapabilitiesProps,
} from "@/components/admin/instance-llm/LlmModelCapabilities";
import { FieldSeparator } from "@/components/ui/field";

import type {
	CreateWorkspaceLlmModelRequest,
	UpdateWorkspaceLlmModelRequest,
	WorkspaceLlmModel,
} from "@/api/types.gen";
import {
	modelDetailsBodyOf,
	LlmModelFields,
	type LlmModelFieldsValue,
	modelFieldsValueOf,
	validateModelFields,
} from "@/components/admin/instance-llm/LlmModelFields";
import type { PriceModeValue } from "@/components/admin/instance-llm/PriceModeEditor";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogClose,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";
import type { FieldErrors, LlmModelFormField } from "@/lib/llm-form-validation";
import { reasoningEffortOf, reasoningEffortUpdateOf } from "@/lib/reasoning-effort";

export interface WorkspaceLlmModelFormDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	editing: WorkspaceLlmModel | null;
	/** Shown above the fields, such as a failed refresh of the models the form edits. */
	notice?: ReactNode;
	isSubmitting: boolean;
	capabilities?: LlmModelCapabilitiesProps;
	onCreate: (body: CreateWorkspaceLlmModelRequest) => void;
	onUpdate: (id: number, body: UpdateWorkspaceLlmModelRequest) => void;
}

function priceValueOf(model: WorkspaceLlmModel | null): PriceModeValue {
	return {
		pricingMode: model?.pricingMode ?? "UNPRICED",
		per1mInputUsd: model?.per1mInputUsd,
		per1mOutputUsd: model?.per1mOutputUsd,
		per1mCacheReadUsd: model?.per1mCacheReadUsd,
		per1mCacheWriteUsd: model?.per1mCacheWriteUsd,
		note: model?.priceNote,
	};
}

/** Price is set inline: the workspace scope has no separate price endpoint, unlike the instance
 * catalog. */
export function WorkspaceLlmModelFormDialog({
	open,
	onOpenChange,
	editing,
	...contentProps
}: WorkspaceLlmModelFormDialogProps) {
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			{open && (
				<WorkspaceLlmModelFormDialogContent
					key={editing?.id ?? "new"}
					editing={editing}
					{...contentProps}
				/>
			)}
		</Dialog>
	);
}

type WorkspaceLlmModelFormDialogContentProps = Omit<
	WorkspaceLlmModelFormDialogProps,
	"open" | "onOpenChange"
>;

function WorkspaceLlmModelFormDialogContent({
	editing,
	notice,
	isSubmitting,
	capabilities,
	onCreate,
	onUpdate,
}: WorkspaceLlmModelFormDialogContentProps) {
	const isEdit = editing !== null;
	const submitLabel = isEdit ? "Save changes" : "Add inactive model";
	const [fields, setFields] = useState<LlmModelFieldsValue>(() =>
		modelFieldsValueOf(editing, priceValueOf(editing)),
	);
	const [errors, setErrors] = useState<FieldErrors<LlmModelFormField>>({});

	const handleSubmit = (event: React.SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		const found = validateModelFields(fields, isEdit);
		setErrors(found);
		if (Object.keys(found).length > 0) {
			return;
		}

		const { price } = fields;
		const shared = {
			displayName: fields.displayName.trim(),
			contextWindow: fields.contextWindow.trim() ? Number(fields.contextWindow) : undefined,
			maxOutputTokens: fields.maxOutputTokens.trim() ? Number(fields.maxOutputTokens) : undefined,
			...modelDetailsBodyOf(fields),
			enabled: isEdit ? fields.enabled : false,
			pricingMode: price.pricingMode,
			per1mInputUsd: price.pricingMode === "PRICED" ? price.per1mInputUsd : undefined,
			per1mOutputUsd: price.pricingMode === "PRICED" ? price.per1mOutputUsd : undefined,
			per1mCacheReadUsd: price.pricingMode === "PRICED" ? price.per1mCacheReadUsd : undefined,
			per1mCacheWriteUsd: price.pricingMode === "PRICED" ? price.per1mCacheWriteUsd : undefined,
			priceNote: price.pricingMode === "NO_CHARGE" ? price.note?.trim() : undefined,
		};

		if (isEdit) {
			onUpdate(editing.id, {
				...shared,
				clearBrand: fields.brand === undefined,
				...reasoningEffortUpdateOf(fields.reasoningEffort),
			} satisfies UpdateWorkspaceLlmModelRequest);
			return;
		}
		onCreate({
			...shared,
			reasoningEffort: reasoningEffortOf(fields.reasoningEffort),
			upstreamModelId: fields.upstreamModelId.trim(),
		} satisfies CreateWorkspaceLlmModelRequest);
	};

	return (
		<DialogContent className="sm:max-w-lg">
			<DialogForm onSubmit={handleSubmit}>
				<DialogHeader>
					<DialogTitle>{isEdit ? "Edit model" : "Add model"}</DialogTitle>
					<DialogDescription>
						Name the model and declare how it handles data. Workspaces and developers see the
						declaration as a badge.
					</DialogDescription>
				</DialogHeader>

				{/* This form outgrows a 320 px viewport; without an internal scroll region the popup hangs
				    off both edges and neither the title nor the submit button can be reached. */}
				<DialogBody className="space-y-4 py-1">
					{notice}
					<LlmModelFields
						audience="workspace"
						idPrefix="wm"
						isEdit={isEdit}
						wasEnabled={editing?.enabled ?? false}
						savedTier={editing?.dataHandlingTier}
						value={fields}
						onChange={setFields}
						errors={errors}
					/>
					{isEdit && capabilities && (
						<>
							<FieldSeparator />
							<LlmModelCapabilities {...capabilities} />
						</>
					)}
				</DialogBody>

				<DialogFooter>
					<DialogClose render={<Button type="button" variant="outline" />}>Cancel</DialogClose>
					<Button type="submit" disabled={isSubmitting}>
						{isSubmitting && <Spinner aria-hidden />}
						{isSubmitting ? "Saving…" : submitLabel}
					</Button>
				</DialogFooter>
			</DialogForm>
		</DialogContent>
	);
}
