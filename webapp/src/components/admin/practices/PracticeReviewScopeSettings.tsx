import deepEqual from "fast-deep-equal";
import { AlertCircle, ChevronDownIcon } from "lucide-react";
import { type ReactNode, useId, useState } from "react";
import type {
	PracticeReviewScopePreview,
	PracticeReviewSettings,
	WorkspaceReviewScope,
} from "@/api/types.gen";
import { FacetMultiSelect, type FacetOption } from "@/components/common/FacetMultiSelect";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RemovableToken } from "@/components/common/RemovableToken";
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Field, FieldDescription, FieldError, FieldLabel, FieldTitle } from "@/components/ui/field";
import {
	InputGroup,
	InputGroupAddon,
	InputGroupButton,
	InputGroupInput,
} from "@/components/ui/input-group";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemTitle,
} from "@/components/ui/item";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Spinner } from "@/components/ui/spinner";
import { useUnsavedChanges } from "@/hooks/use-unsaved-changes";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";

type ScopeOptionState<TOption> =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; options: TOption[] };

/** A repository item of this workspace, as the item directory records it — current or retained. */
export interface RepositoryOption {
	itemId: string;
	name: string;
	/** When the workspace removed it; a removed repository keeps its item and its history. */
	removedAt?: Date | null;
	/** False where no repository record names the item, so `name` is only the kind's word. */
	nameKnown?: boolean;
}

/**
 * The repositories loaded so far. The directory is paged, so an item missing here may be on a page
 * not yet loaded; `more` says whether one is.
 */
export type RepositoryScopeOptions =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "ready"; options: RepositoryOption[]; total?: number; more?: MorePages };

export function isRemoved(option: Pick<RepositoryOption, "removedAt">): boolean {
	return option.removedAt !== undefined && option.removedAt !== null;
}

/** How a repository reads where its record may lack a name: never the bare kind's word twice. */
export function repositoryLabel(
	option: Pick<RepositoryOption, "itemId" | "name" | "nameKnown">,
): string {
	return option.nameKnown === false ? `Repository ${option.itemId}` : option.name;
}
/** Members by account id. An unclaimed account is a member like any other. */
export type PeopleScopeOptions = ScopeOptionState<FacetOption>;

export interface PracticeReviewScopeSettingsProps {
	settings: PracticeReviewSettings;
	preview: (scope: WorkspaceReviewScope) => Promise<PracticeReviewScopePreview>;
	onSave: (scope: WorkspaceReviewScope, sourceEtag: string) => Promise<void>;
	repositories: RepositoryScopeOptions;
	people: PeopleScopeOptions;
}

type Workflow =
	| { status: "editing" }
	| { status: "checking" }
	| { status: "saving" }
	| { status: "error"; action: "preview" | "save" }
	| {
			status: "confirm";
			preview: PracticeReviewScopePreview;
			scope: WorkspaceReviewScope;
			sourceEtag: string;
	  };

export function PracticeReviewScopeSettings({
	settings,
	preview,
	onSave,
	repositories,
	people,
}: PracticeReviewScopeSettingsProps) {
	const repositoryScopeId = useId();
	const personScopeId = useId();
	const [storedDraft, setStoredDraft] = useState({
		baseScope: settings.reviewScope,
		baseEtag: settings.etag,
		scope: settings.reviewScope,
	});
	const storedDraftChanged = !deepEqual(storedDraft.scope, storedDraft.baseScope);
	const persistedScopeChanged = !deepEqual(storedDraft.baseScope, settings.reviewScope);
	const draft =
		persistedScopeChanged && !storedDraftChanged ? settings.reviewScope : storedDraft.scope;
	const setDraft = (scope: WorkspaceReviewScope) =>
		setStoredDraft((current) => {
			const hasUnmergedChanges =
				!deepEqual(current.scope, current.baseScope) &&
				!deepEqual(current.scope, settings.reviewScope);
			return {
				baseScope: hasUnmergedChanges ? current.baseScope : settings.reviewScope,
				baseEtag: hasUnmergedChanges ? current.baseEtag : settings.etag,
				scope,
			};
		});
	const [workflow, setWorkflow] = useState<Workflow>({ status: "editing" });
	const persisted = settings.reviewScope;
	const dirty = !deepEqual(draft, persisted);
	const conflicted = storedDraftChanged && persistedScopeChanged;
	const sourceEtag = persistedScopeChanged ? storedDraft.baseEtag : settings.etag;
	const busy = workflow.status === "checking" || workflow.status === "saving";
	const unsavedChanges = useUnsavedChanges({
		isDirty: dirty,
		disabled: busy,
		description: "Your review scope draft will be lost if you leave.",
	});

	const repositoryOf = (itemId: string) =>
		repositories.status === "ready"
			? repositories.options.find((option) => option.itemId === itemId)
			: undefined;
	// Every page loaded: an item missing from them is in no repository record of this workspace.
	const listComplete = repositories.status === "ready" && repositories.more?.hasMore !== true;
	const repositoryIds = draft.selectedRepositories.map((repository) => repository.repositoryItemId);
	const repositoryOptions = withPersistedOptions(
		repositories.status === "ready"
			? {
					status: "ready",
					options: repositories.options.map((option) => ({
						value: option.itemId,
						label: isRemoved(option)
							? `${repositoryLabel(option)} (removed)`
							: repositoryLabel(option),
					})),
				}
			: repositories,
		repositoryIds,
		(id) => ({ value: id, label: `Repository ${id}` }),
		(id) => ({
			value: id,
			label: listComplete ? `Repository ${id} (source unavailable)` : `Repository ${id}`,
		}),
	);
	const personOptions = withPersistedOptions(
		people,
		draft.accountIds,
		(id) => ({ value: id, label: `Member ${id}` }),
		(id) => ({ value: id, label: `Member ${id} (unavailable)` }),
	);

	// A selection counts as selected whatever its repository's state: the server decides what of it
	// is reviewed, and the reader can see and remove every entry.
	const selectedRepositories =
		draft.repositories === "ALL"
			? settings.scopeSummary.monitoredRepositories
			: repositoryIds.length;
	const eligible = (id: string) =>
		people.status !== "ready" || people.options.some((option) => option.value === id);
	const selectedPeople =
		draft.people === "ALL"
			? settings.scopeSummary.eligiblePeople
			: draft.accountIds.filter(eligible).length;

	const save = async (scope: WorkspaceReviewScope, etag: string) => {
		setWorkflow({ status: "saving" });
		try {
			await onSave(scope, etag);
			setStoredDraft({ baseScope: scope, baseEtag: etag, scope });
			setWorkflow({ status: "editing" });
		} catch {
			setWorkflow({ status: "error", action: "save" });
		}
	};
	const review = async () => {
		if (conflicted) {
			return;
		}
		const scope = draft;
		const etag = sourceEtag;
		setWorkflow({ status: "checking" });
		try {
			const result = await preview(scope);
			if (result.widens) {
				setWorkflow({ status: "confirm", preview: result, scope, sourceEtag: etag });
			} else {
				await save(scope, etag);
			}
		} catch {
			setWorkflow({ status: "error", action: "preview" });
		}
	};

	let reviewButtonContent: ReactNode;
	if (workflow.status === "checking") {
		reviewButtonContent = (
			<>
				<Spinner />
				Checking impact…
			</>
		);
	} else if (workflow.status === "saving") {
		reviewButtonContent = (
			<>
				<Spinner />
				Saving…
			</>
		);
	} else {
		reviewButtonContent = "Review changes";
	}

	return (
		<section className="space-y-6" aria-labelledby="review-scope-heading">
			{unsavedChanges.dialog}
			<div className="space-y-1">
				<h2 id="review-scope-heading" className="text-lg font-semibold">
					Review scope
				</h2>
				<p className="text-sm text-muted-foreground">
					Hephaestus reviews repository work when both its repository and its linked person are in
					scope. The linked person is usually the author, and reviewer practices use the reviewer.
					Slack conversations and Outline documents follow the people selection and their own
					collection permissions. About {settings.scopeSummary.recentReviewVolume} reviews started
					in this workspace during the last {settings.scopeSummary.estimateWindowDays} days.
				</p>
			</div>
			{conflicted ? (
				<Alert variant="warning" role="alert">
					<AlertCircle />
					<AlertTitle>Review scope changed elsewhere</AlertTitle>
					<AlertDescription>
						Your draft no longer matches the saved settings. Discard it to load the latest settings,
						then make your changes again.
						<Button
							variant="outline"
							size="sm"
							className="mt-2"
							onClick={() => {
								setStoredDraft({
									baseScope: settings.reviewScope,
									baseEtag: settings.etag,
									scope: settings.reviewScope,
								});
								setWorkflow({ status: "editing" });
							}}
						>
							Discard draft and load latest
						</Button>
					</AlertDescription>
				</Alert>
			) : null}

			<Field>
				<ScopeLabel
					id={`${repositoryScopeId}-label`}
					label="Repositories"
					// A selection can hold repositories the workspace no longer monitors, so it is not a
					// share of the monitored ones.
					summary={`${String(selectedRepositories)} selected · ${String(settings.scopeSummary.monitoredRepositories)} monitored repositories`}
				/>
				<RadioGroup
					aria-labelledby={`${repositoryScopeId}-label`}
					value={draft.repositories}
					disabled={busy}
					onValueChange={(mode) => setDraft({ ...draft, repositories: mode })}
				>
					<label className="flex items-center gap-2" htmlFor={`${repositoryScopeId}-all`}>
						<RadioGroupItem id={`${repositoryScopeId}-all`} value="ALL" />
						All monitored repositories
					</label>
					<label className="flex items-center gap-2" htmlFor={`${repositoryScopeId}-selected`}>
						<RadioGroupItem id={`${repositoryScopeId}-selected`} value="SELECTED" />
						Selected repositories
					</label>
				</RadioGroup>
				{draft.repositories === "SELECTED" ? (
					<div className="space-y-3">
						{repositories.status === "error" ? (
							<QueryErrorAlert
								error={repositories.error}
								title="We could not load repositories"
								onRetry={repositories.onRetry}
							/>
						) : null}
						<FacetMultiSelect
							id="scope-repositories"
							title="Choose repositories"
							variant="field"
							options={repositoryOptions}
							selected={repositoryIds}
							onChange={(ids) => {
								const byId = new Map(
									draft.selectedRepositories.map((repository) => [
										repository.repositoryItemId,
										repository,
									]),
								);
								setDraft({
									...draft,
									selectedRepositories: ids.map(
										(id) => byId.get(id) ?? { repositoryItemId: id, baseBranches: [] },
									),
								});
							}}
							disabled={busy || repositories.status !== "ready"}
							emptyLabel="No repositories"
						/>
						{draft.selectedRepositories.length === 0 ? (
							<EmptySelection noun="repositories" allLabel="all monitored repositories" />
						) : (
							<ItemGroup>
								{draft.selectedRepositories.map((repository) => (
									<RepositoryScopeRow
										key={repository.repositoryItemId}
										name={repositoryLabel(
											repositoryOf(repository.repositoryItemId) ?? {
												itemId: repository.repositoryItemId,
												name: "",
												nameKnown: false,
											},
										)}
										baseBranches={repository.baseBranches}
										record={recordState(repositoryOf(repository.repositoryItemId), listComplete)}
										disabled={busy}
										onChange={(baseBranches) =>
											setDraft({
												...draft,
												selectedRepositories: draft.selectedRepositories.map((entry) =>
													entry.repositoryItemId === repository.repositoryItemId
														? { ...entry, baseBranches }
														: entry,
												),
											})
										}
									/>
								))}
							</ItemGroup>
						)}
					</div>
				) : null}
			</Field>

			<Field>
				<ScopeLabel
					id={`${personScopeId}-label`}
					label="People"
					summary={`${String(selectedPeople)} of ${String(settings.scopeSummary.eligiblePeople)} eligible people`}
				/>
				<FieldDescription>
					Only eligible human members can be selected, including members who have not signed in yet.
					Outside contributors, unlinked reviewers, and bots stay out of scope.
				</FieldDescription>
				<RadioGroup
					aria-labelledby={`${personScopeId}-label`}
					value={draft.people}
					disabled={busy}
					onValueChange={(mode) => setDraft({ ...draft, people: mode })}
				>
					<label className="flex items-center gap-2" htmlFor={`${personScopeId}-all`}>
						<RadioGroupItem id={`${personScopeId}-all`} value="ALL" />
						All eligible members
					</label>
					<label className="flex items-center gap-2" htmlFor={`${personScopeId}-selected`}>
						<RadioGroupItem id={`${personScopeId}-selected`} value="SELECTED" />
						Selected people
					</label>
				</RadioGroup>
				{draft.people === "SELECTED" ? (
					<div className="space-y-3">
						{people.status === "error" ? (
							<QueryErrorAlert
								error={people.error}
								title="We could not load eligible members"
								onRetry={people.onRetry}
							/>
						) : null}
						<FacetMultiSelect
							id="scope-people"
							title="Choose people"
							variant="field"
							options={personOptions}
							selected={draft.accountIds}
							onChange={(accountIds) => setDraft({ ...draft, accountIds })}
							disabled={busy || people.status !== "ready"}
							emptyLabel="No eligible members"
						/>
						{draft.accountIds.length === 0 ? (
							<EmptySelection noun="people" allLabel="all eligible members" />
						) : (
							<ul className="flex flex-wrap gap-2">
								{draft.accountIds.map((accountId) => {
									const label =
										personOptions.find((option) => option.value === accountId)?.label ??
										`Member ${accountId}`;
									return (
										<li key={accountId}>
											<RemovableToken
												label={label}
												removeLabel={`Remove ${label} from the review scope`}
												disabled={busy}
												onRemove={() =>
													setDraft({
														...draft,
														accountIds: draft.accountIds.filter((id) => id !== accountId),
													})
												}
											/>
										</li>
									);
								})}
							</ul>
						)}
					</div>
				) : null}
			</Field>

			<div className="flex min-h-12 flex-wrap items-center justify-between gap-3 border-t pt-4">
				<ScopeWorkflowStatus workflow={workflow} dirty={dirty} />
				<div className="flex flex-wrap gap-2">
					<Button
						variant="outline"
						disabled={!dirty || busy}
						onClick={() => {
							setDraft(persisted);
							setWorkflow({ status: "editing" });
						}}
					>
						Discard changes
					</Button>
					<Button
						className="min-w-36"
						disabled={!dirty || busy || conflicted}
						onClick={() => {
							void review();
						}}
					>
						{reviewButtonContent}
					</Button>
				</div>
			</div>

			<AlertDialog
				open={workflow.status === "confirm"}
				onOpenChange={(open) => {
					if (!open) {
						setWorkflow({ status: "editing" });
					}
				}}
			>
				<AlertDialogContent className="max-w-[calc(100vw-2rem)] min-w-0 overflow-hidden sm:max-w-lg">
					<AlertDialogHeader>
						<AlertDialogTitle>Widen the review scope?</AlertDialogTitle>
						<AlertDialogDescription>
							Hephaestus can review more work. This does not review earlier work again or release
							existing feedback. Feedback mode, developer preferences, and workspace delivery still
							apply.
						</AlertDialogDescription>
					</AlertDialogHeader>
					{workflow.status === "confirm" ? <ScopeImpact preview={workflow.preview} /> : null}
					<AlertDialogFooter>
						<AlertDialogCancel>Keep editing</AlertDialogCancel>
						<AlertDialogAction
							onClick={() => {
								if (workflow.status === "confirm") {
									void save(workflow.scope, workflow.sourceEtag);
								}
							}}
						>
							Widen review scope
						</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</section>
	);
}

function withPersistedOptions(
	state: ScopeOptionState<FacetOption>,
	selected: readonly string[],
	unknown: (value: string) => FacetOption,
	unavailable: (value: string) => FacetOption,
): FacetOption[] {
	if (state.status !== "ready") {
		return selected.map(unknown);
	}
	return [
		...state.options,
		...selected
			.filter((value) => !state.options.some((option) => option.value === value))
			.map(unavailable),
	];
}

function ScopeWorkflowStatus({ workflow, dirty }: { workflow: Workflow; dirty: boolean }) {
	if (workflow.status === "error") {
		return (
			<p role="alert" className="max-w-md text-sm text-destructive">
				{workflow.action === "preview"
					? "We could not estimate the impact. Your draft is unchanged. Try again."
					: "We could not save the review scope. Your draft is unchanged. Try again."}
			</p>
		);
	}
	let status = dirty ? "You have unsaved review scope changes." : "Review scope is up to date.";
	if (workflow.status === "checking") {
		status = "Checking impact…";
	} else if (workflow.status === "saving") {
		status = "Saving review scope…";
	}
	return (
		<p role="status" className="max-w-md text-sm text-muted-foreground">
			{status}
		</p>
	);
}

function ScopeImpact({ preview }: { preview: PracticeReviewScopePreview }) {
	return (
		<div className="min-w-0 space-y-2 text-sm break-words">
			<p>
				Monitored repositories in scope: <strong>{preview.current.selectedRepositories}</strong>
				{" → "}
				<strong>{preview.proposed.selectedRepositories}</strong> of{" "}
				{preview.proposed.monitoredRepositories}
			</p>
			<p>
				Eligible members in scope: <strong>{preview.current.selectedPeople}</strong>
				{" → "}
				<strong>{preview.proposed.selectedPeople}</strong> of {preview.proposed.eligiblePeople}
			</p>
			<p className="text-xs text-muted-foreground">
				For scale, {preview.proposed.recentReviewVolume} reviews started in this workspace during
				the last {preview.proposed.estimateWindowDays} days.
			</p>
		</div>
	);
}

// An empty selected scope must remain distinct from all.
function EmptySelection({ noun, allLabel }: { noun: "repositories" | "people"; allLabel: string }) {
	return (
		<Alert variant="warning" role="status">
			<AlertCircle />
			<AlertTitle>No {noun} are selected</AlertTitle>
			<AlertDescription>
				{noun === "people"
					? "With no people selected, Hephaestus reviews nobody’s work."
					: "With no repositories selected, Hephaestus reviews no repository work."}{" "}
				Choose {noun === "people" ? "a person" : "a repository"} or select {allLabel}.
			</AlertDescription>
		</Alert>
	);
}

function ScopeLabel({ id, label, summary }: { id: string; label: string; summary: string }) {
	return (
		<div className="flex items-baseline justify-between gap-3">
			<FieldTitle id={id}>{label}</FieldTitle>
			<span className="text-sm text-muted-foreground">{summary}</span>
		</div>
	);
}

/** What the loaded repository records say about a selected item, and nothing they do not. */
type RepositoryRecordState = "listed" | "removed" | "unnamed" | "unlisted" | "not-loaded";

function recordState(
	option: RepositoryOption | undefined,
	listComplete: boolean,
): RepositoryRecordState {
	if (option === undefined) {
		return listComplete ? "unlisted" : "not-loaded";
	}
	if (isRemoved(option)) {
		return "removed";
	}
	return option.nameKnown === false ? "unnamed" : "listed";
}

const RECORD_NOTES: Record<RepositoryRecordState, string | undefined> = {
	listed: undefined,
	removed: "The workspace removed this repository.",
	unnamed: "No record names this repository.",
	unlisted: "No record of this workspace has this repository.",
	"not-loaded": "Not among the repositories loaded so far.",
};

const RECORD_BADGES: Record<RepositoryRecordState, string | undefined> = {
	listed: undefined,
	removed: "Removed",
	unnamed: "Name unavailable",
	unlisted: "Source unavailable",
	"not-loaded": undefined,
};

function scopeDescription(record: RepositoryRecordState, baseBranches: string[]): string {
	const branches = branchesDescription(baseBranches);
	const note = RECORD_NOTES[record];
	return note === undefined ? branches : `${note} ${branches}.`;
}

function branchesDescription(baseBranches: string[]): string {
	if (baseBranches.length === 0) {
		return "Every base branch";
	}
	return `Only ${baseBranches.join(", ")}`;
}

function RepositoryScopeRow({
	name,
	baseBranches,
	record,
	disabled,
	onChange,
}: {
	name: string;
	baseBranches: string[];
	record: RepositoryRecordState;
	disabled: boolean;
	onChange: (next: string[]) => void;
}) {
	const editorId = useId();
	return (
		<Collapsible>
			<Item variant="outline" size="sm" role="listitem" className="flex-wrap">
				<ItemContent className="min-w-0">
					<ItemTitle className="w-full min-w-0 gap-2">
						<span className="truncate font-mono" title={name}>
							{name}
						</span>
						{RECORD_BADGES[record] === undefined ? null : (
							<Badge variant="outline">{RECORD_BADGES[record]}</Badge>
						)}
					</ItemTitle>
					<ItemDescription className="break-all">
						{scopeDescription(record, baseBranches)}
					</ItemDescription>
				</ItemContent>
				<ItemActions>
					<CollapsibleTrigger
						render={
							<Button variant="outline" size="sm" disabled={disabled} className="group/branches">
								Base branches<span className="sr-only"> for {name}</span>
								<ChevronDownIcon
									aria-hidden
									className="transition-transform group-aria-expanded/branches:rotate-180"
								/>
							</Button>
						}
					/>
				</ItemActions>
				<CollapsibleContent className="w-full">
					<BaseBranchEditor
						id={editorId}
						repositoryName={name}
						values={baseBranches}
						disabled={disabled}
						onChange={onChange}
					/>
				</CollapsibleContent>
			</Item>
		</Collapsible>
	);
}

function BaseBranchEditor({
	id,
	repositoryName,
	values,
	disabled,
	onChange,
}: {
	id: string;
	repositoryName: string;
	values: string[];
	disabled: boolean;
	onChange: (next: string[]) => void;
}) {
	const [draft, setDraft] = useState("");
	const trimmed = draft.trim();
	const duplicate = trimmed.length > 0 && values.includes(trimmed);
	const descriptionId = `${id}-description`;
	const errorId = `${id}-error`;
	const add = () => {
		if (trimmed.length === 0 || duplicate) {
			return;
		}
		onChange([...values, trimmed]);
		setDraft("");
	};
	return (
		<Field data-invalid={duplicate || undefined} className="border-t pt-3">
			<FieldLabel htmlFor={id}>
				Base branch limits<span className="sr-only"> for {repositoryName}</span>
			</FieldLabel>
			<FieldDescription id={descriptionId}>
				Leave empty to cover every base branch. Otherwise enter exact branch names.
			</FieldDescription>
			<InputGroup data-invalid={duplicate || undefined}>
				<InputGroupInput
					id={id}
					name={`base-branch-${repositoryName}`}
					value={draft}
					autoComplete="off"
					spellCheck={false}
					placeholder="main"
					disabled={disabled}
					aria-invalid={duplicate || undefined}
					aria-describedby={duplicate ? `${descriptionId} ${errorId}` : descriptionId}
					onChange={(event) => setDraft(event.target.value)}
					onKeyDown={(event) => {
						if (event.key === "Enter") {
							event.preventDefault();
							add();
						}
					}}
				/>
				<InputGroupAddon align="inline-end">
					<InputGroupButton
						variant="outline"
						disabled={disabled || trimmed.length === 0 || duplicate}
						onClick={add}
					>
						Add<span className="sr-only"> to base branches for {repositoryName}</span>
					</InputGroupButton>
				</InputGroupAddon>
			</InputGroup>
			{duplicate ? <FieldError id={errorId}>{trimmed} is already listed.</FieldError> : null}
			{values.length > 0 ? (
				<ul className="flex flex-wrap gap-2">
					{values.map((value) => (
						<li key={value}>
							<RemovableToken
								label={value}
								className="font-mono"
								removeLabel={`Remove ${value} from base branches for ${repositoryName}`}
								disabled={disabled}
								onRemove={() => onChange(values.filter((entry) => entry !== value))}
							/>
						</li>
					))}
				</ul>
			) : null}
		</Field>
	);
}
