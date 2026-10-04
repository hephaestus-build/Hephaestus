import { useId, useState } from "react";
import type { PracticeReviewSettings, UpdatePracticeReviewSettingsRequest } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { useUnsavedChanges } from "@/hooks/use-unsaved-changes";
import type { RepositoryCoverageOptions } from "./PracticeReviewCoverageSettings";

export interface GeneratedPathsSettingsProps {
	settings: PracticeReviewSettings;
	repositories: RepositoryCoverageOptions;
	isSaving: boolean;
	onSave: (patch: UpdatePracticeReviewSettingsRequest, sourceEtag?: string) => Promise<void>;
}

export function GeneratedPathsSettings(props: GeneratedPathsSettingsProps) {
	const { repositories } = props;
	return (
		<section aria-labelledby="generated-paths-heading" className="space-y-4">
			<div className="space-y-1">
				<h2 id="generated-paths-heading" className="text-lg font-semibold">
					Generated paths
				</h2>
				<p className="text-sm text-muted-foreground">
					Mark intentionally committed generated output for each repository. Reviews keep it as
					generated content, not hand-written work. Coverage changes do not clear these settings.
				</p>
			</div>
			{repositories.status === "loading" && <Skeleton className="h-32 w-full" />}
			{repositories.status === "error" && (
				<QueryErrorAlert
					error={repositories.error}
					onRetry={repositories.onRetry}
					title="We could not load generated-path settings"
				/>
			)}
			{repositories.status === "ready" && repositories.options.length === 0 && (
				<p className="text-sm text-muted-foreground">No monitored repositories.</p>
			)}
			{repositories.status === "ready" &&
				repositories.options.map((option) => (
					<RepositoryGeneratedPaths key={option.value} {...props} repository={option.value} />
				))}
		</section>
	);
}

function RepositoryGeneratedPaths({
	settings,
	repository,
	isSaving,
	onSave,
}: GeneratedPathsSettingsProps & { repository: string }) {
	const id = useId();
	const persisted = (settings.generatedPaths[repository] ?? []).join("\n");
	const [stored, setStored] = useState({ text: persisted, base: persisted, etag: settings.etag });
	const [state, setState] = useState<"editing" | "saving" | "error">("editing");
	const unmerged = stored.text !== stored.base && stored.text !== persisted;
	const text = unmerged ? stored.text : persisted;
	const dirty = text !== persisted;
	const conflicted = unmerged && stored.base !== persisted;
	const sourceEtag = conflicted ? stored.etag : settings.etag;
	const busy = state === "saving" || isSaving;
	const patterns = [
		...new Set(
			text
				.split("\n")
				.map((line) => line.trim())
				.filter(Boolean),
		),
	];
	const invalid = patterns.length > 100 || patterns.some((pattern) => pattern.length > 512);
	const guard = useUnsavedChanges({
		isDirty: dirty,
		disabled: state === "saving",
		description: "Your generated-path changes will be lost if you leave.",
	});
	const errorMessage = conflicted
		? "Saved patterns changed while you were editing. Your draft is not saved. Use the saved patterns, then make your changes again."
		: "We could not save generated paths. Check the patterns and try again.";
	const save = async () => {
		setState("saving");
		try {
			await onSave({ generatedPaths: { [repository]: patterns } }, sourceEtag);
			setStored({ text, base: text, etag: settings.etag });
			setState("editing");
		} catch {
			setState("error");
		}
	};
	return (
		<>
			<Field data-invalid={invalid || undefined}>
				<FieldLabel htmlFor={id} className="break-all">
					{repository}
				</FieldLabel>
				<Textarea
					id={id}
					value={text}
					disabled={busy}
					aria-invalid={invalid || undefined}
					aria-describedby={`${id}-description${invalid || conflicted || state === "error" ? ` ${id}-error` : ""}`}
					onChange={(event) => {
						setStored({
							text: event.currentTarget.value,
							base: unmerged ? stored.base : persisted,
							etag: unmerged ? stored.etag : settings.etag,
						});
						setState("editing");
					}}
				/>
				<FieldDescription id={`${id}-description`}>
					One repository-root pattern per line, such as <code>src/api/**</code> or{" "}
					<code>**/*.generated.ts</code>. Case-sensitive. <code>*</code> stays within a directory.{" "}
					<code>**</code> crosses directories. Clear all lines and save to reset.
				</FieldDescription>
				{invalid || conflicted || state === "error" ? (
					<FieldError id={`${id}-error`}>
						{invalid
							? "Use at most 100 patterns, each no longer than 512 characters."
							: errorMessage}
					</FieldError>
				) : null}
				{conflicted && (
					<Button
						variant="link"
						size="sm"
						onClick={() => {
							setStored({ text: persisted, base: persisted, etag: settings.etag });
							setState("editing");
						}}
					>
						Use saved patterns
					</Button>
				)}
				<div>
					<Button
						variant="outline"
						size="sm"
						aria-label={state === "saving" ? undefined : `Save generated paths for ${repository}`}
						disabled={busy || !dirty || invalid || conflicted}
						onClick={() => {
							save().catch(() => setState("error"));
						}}
					>
						{state === "saving" ? <Spinner /> : null}
						{state === "saving" ? "Saving…" : "Save generated paths"}
					</Button>
				</div>
			</Field>
			{guard.dialog}
		</>
	);
}
