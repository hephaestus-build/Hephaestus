import { useId, useState } from "react";

import type { EditProductFeedbackDraft } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";

/** The server's `EditProductFeedbackDraftDTO` limits. */
export const DRAFT_TITLE_MAX_LENGTH = 80;
export const DRAFT_DESCRIPTION_MAX_LENGTH = 2000;

export interface ProductFeedbackDraftFormProps {
	/** The drafted issue the form starts from. Key the form on the report so a new report reseeds it. */
	draft: EditProductFeedbackDraft;
	saving: boolean;
	/** Resolves to whether the server took the edit; the form closes only then. */
	onSave: (draft: EditProductFeedbackDraft) => Promise<boolean>;
	onCancel: () => void;
}

/** Edits the drafted title and description of a held report before it is filed. */
export function ProductFeedbackDraftForm({
	draft,
	saving,
	onSave,
	onCancel,
}: ProductFeedbackDraftFormProps) {
	const id = useId();
	const [title, setTitle] = useState(draft.title);
	const [description, setDescription] = useState(draft.description);
	const ready = title.trim().length > 0 && description.trim().length > 0;
	return (
		<form
			className="flex flex-col gap-3"
			aria-busy={saving}
			aria-label="Edit the issue"
			onSubmit={(event) => {
				event.preventDefault();
				if (!ready || saving) {
					return;
				}
				void onSave({ title: title.trim(), description: description.trim() }).then((saved) => {
					if (saved) {
						onCancel();
					}
				});
			}}
		>
			<FieldGroup className="gap-3">
				<Field>
					<FieldLabel htmlFor={`${id}-title`}>Issue title</FieldLabel>
					<Input
						id={`${id}-title`}
						value={title}
						required
						maxLength={DRAFT_TITLE_MAX_LENGTH}
						disabled={saving}
						onChange={(event) => setTitle(event.target.value)}
					/>
				</Field>
				<Field>
					<FieldLabel htmlFor={`${id}-description`}>Issue description</FieldLabel>
					<Textarea
						id={`${id}-description`}
						value={description}
						required
						rows={5}
						maxLength={DRAFT_DESCRIPTION_MAX_LENGTH}
						disabled={saving}
						onChange={(event) => setDescription(event.target.value)}
					/>
				</Field>
			</FieldGroup>
			<div className="flex flex-wrap justify-end gap-2">
				<Button type="button" variant="outline" size="sm" disabled={saving} onClick={onCancel}>
					Cancel
				</Button>
				<Button type="submit" size="sm" disabled={!ready || saving}>
					{saving && <Spinner />}
					{saving ? "Saving…" : "Save issue"}
				</Button>
			</div>
		</form>
	);
}
