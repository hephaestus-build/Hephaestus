import { ChevronDownIcon } from "lucide-react";
import { useId, useState } from "react";
import type { PracticeReviewSettings, UpdatePracticeReviewSettingsRequest } from "@/api/types.gen";
import { LOADING_MORE_ROWS } from "@/components/common/InfiniteListEnd";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	Item,
	ItemActions,
	ItemContent,
	ItemDescription,
	ItemGroup,
	ItemTitle,
} from "@/components/ui/item";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { useUnsavedChanges } from "@/hooks/use-unsaved-changes";
import type { MorePages } from "@/runtime/tanstack-query/infinite-list";
import {
	type RepositoryOption,
	type RepositoryScopeOptions,
	isRemoved,
	repositoryLabel,
} from "./PracticeReviewScopeSettings";

export interface GeneratedPathsSettingsProps {
	settings: PracticeReviewSettings;
	repositories: RepositoryScopeOptions;
	isSaving: boolean;
	onSave: (patch: UpdatePracticeReviewSettingsRequest, sourceEtag?: string) => Promise<void>;
}

function matches(repository: RepositoryOption, needle: string): boolean {
	return (
		needle === "" ||
		repositoryLabel(repository).toLowerCase().includes(needle) ||
		repository.itemId.includes(needle)
	);
}

function countText(matched: number, loaded: number, total: number | undefined, searching: boolean) {
	const of = total !== undefined && total > loaded ? ` of ${String(total)}` : "";
	const noun = loaded === 1 && of === "" ? "repository" : "repositories";
	return searching
		? `${String(matched)} of ${String(loaded)} loaded ${noun} match`
		: `${String(loaded)}${of} ${noun} loaded`;
}

export function GeneratedPathsSettings(props: GeneratedPathsSettingsProps) {
	const { repositories } = props;
	const searchId = useId();
	const [search, setSearch] = useState("");
	const needle = search.trim().toLowerCase();
	return (
		<section aria-labelledby="generated-paths-heading" className="space-y-4">
			<div className="space-y-1">
				<h2 id="generated-paths-heading" className="text-lg font-semibold">
					Generated paths
				</h2>
				<p className="text-sm text-muted-foreground">
					Mark intentionally committed generated output for each repository. Reviews and scans treat
					it as generated content, not hand-written work. You can save generated paths only for
					repositories this workspace currently monitors. Review scope changes do not clear these
					settings.
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
				<p className="text-sm text-muted-foreground">No repositories are available in this list.</p>
			)}
			{repositories.status === "ready" && repositories.options.length > 0 && (
				<>
					{repositories.more?.refreshFailure !== undefined && (
						<QueryErrorAlert
							title="Repositories may be out of date"
							{...repositories.more.refreshFailure}
						/>
					)}
					<Field>
						<FieldLabel htmlFor={searchId}>Search repositories</FieldLabel>
						<Input
							id={searchId}
							type="search"
							value={search}
							placeholder="Name or ID"
							aria-describedby={`${searchId}-count`}
							onChange={(event) => setSearch(event.currentTarget.value)}
						/>
						<FieldDescription id={`${searchId}-count`} aria-live="polite">
							{countText(
								repositories.options.filter((repository) => matches(repository, needle)).length,
								repositories.options.length,
								repositories.total,
								needle !== "",
							)}
							. A repository with unsaved changes stays listed.
						</FieldDescription>
					</Field>
					{!repositories.options.some((repository) => matches(repository, needle)) && (
						<p className="text-sm text-muted-foreground">
							No loaded repository matches “{search.trim()}”.{" "}
							<Button variant="link" size="inline" onClick={() => setSearch("")}>
								Clear search
							</Button>
						</p>
					)}
					{/* Rows that do not match are hidden, not unmounted, so their drafts and guards stay. */}
					<ItemGroup aria-busy={repositories.more?.isLoadingMore === true || undefined}>
						{repositories.options.map((option) => (
							<RepositoryGeneratedPaths
								key={option.itemId}
								{...props}
								repository={option}
								matched={matches(option, needle)}
							/>
						))}
						{repositories.more?.isLoadingMore === true &&
							Array.from({ length: LOADING_MORE_ROWS }, (_, index) => (
								<RepositorySkeleton key={index} />
							))}
					</ItemGroup>
					{repositories.more !== undefined && <MoreRepositories more={repositories.more} />}
				</>
			)}
		</section>
	);
}

/** A repository's row while the next page loads: its name, its summary and the press. */
function RepositorySkeleton() {
	return (
		<Item variant="outline" size="sm" aria-hidden data-loading-row>
			<ItemContent className="min-w-0 gap-1.5">
				<Skeleton className="h-4 w-48 max-w-full" />
				<Skeleton className="h-3.5 w-28" />
			</ItemContent>
			<ItemActions>
				<Skeleton className="h-8 w-36" />
			</ItemActions>
		</Item>
	);
}

/** The next page of repositories; a failed page keeps the loaded ones and asks again. */
function MoreRepositories({ more }: { more: MorePages }) {
	return (
		<div className="space-y-2">
			{more.loadMoreError !== undefined && (
				<QueryErrorAlert
					error={more.loadMoreError}
					title="We could not load more repositories"
					onRetry={more.onLoadMore}
				/>
			)}
			{more.hasMore && more.loadMoreError === undefined && (
				<Button variant="outline" size="sm" disabled={more.isLoadingMore} onClick={more.onLoadMore}>
					{more.isLoadingMore ? <Spinner /> : null}
					{more.isLoadingMore ? "Loading repositories…" : "Show more repositories"}
				</Button>
			)}
		</div>
	);
}

function RepositoryGeneratedPaths({
	settings,
	repository,
	matched,
	isSaving,
	onSave,
}: GeneratedPathsSettingsProps & { repository: RepositoryOption; matched: boolean }) {
	const id = useId();
	const name = repositoryLabel(repository);
	const removed = isRemoved(repository);
	const saved = settings.generatedPaths[repository.itemId] ?? [];
	const persisted = saved.join("\n");
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
			await onSave({ generatedPaths: { [repository.itemId]: patterns } }, sourceEtag);
			setStored({ text, base: text, etag: settings.etag });
			setState("editing");
		} catch {
			setState("error");
		}
	};
	let summary = "No generated paths";
	if (dirty) {
		summary = "Unsaved changes";
	} else if (saved.length > 0) {
		summary = `${String(saved.length)} ${saved.length === 1 ? "pattern" : "patterns"} saved`;
	}
	return (
		<Collapsible defaultOpen={saved.length > 0}>
			{/* A row with unsaved changes stays listed whatever the search, so no draft is out of sight. */}
			<Item
				variant="outline"
				size="sm"
				role="listitem"
				className="flex-wrap"
				hidden={!matched && !dirty}
			>
				<ItemContent className="min-w-0">
					<ItemTitle className="w-full min-w-0 flex-wrap gap-2">
						<span className="truncate font-mono" title={`${name} · item ${repository.itemId}`}>
							{name}
						</span>
						{removed && <Badge variant="outline">Removed</Badge>}
						{repository.nameKnown === false && <Badge variant="outline">Name unavailable</Badge>}
					</ItemTitle>
					<ItemDescription>{summary}</ItemDescription>
				</ItemContent>
				<ItemActions>
					<CollapsibleTrigger
						render={
							<Button
								variant="outline"
								size="sm"
								className="group/paths"
								aria-label={`Generated paths for ${name}`}
							>
								Generated paths
								<ChevronDownIcon
									aria-hidden
									className="transition-transform group-aria-expanded/paths:rotate-180"
								/>
							</Button>
						}
					/>
				</ItemActions>
				{/* Kept mounted while closed, so collapsing a row never discards its draft. */}
				<CollapsibleContent keepMounted className="w-full">
					<Field data-invalid={invalid || undefined} className="pt-2">
						<FieldLabel htmlFor={id} className="break-all">
							{name}
						</FieldLabel>
						<Textarea
							id={id}
							value={text}
							disabled={busy}
							readOnly={removed}
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
							{removed ? (
								"The workspace removed this repository, so its generated paths cannot be saved."
							) : (
								<>
									One repository-root pattern per line, such as <code>src/api/**</code> or{" "}
									<code>**/*.generated.ts</code>. Case-sensitive. <code>*</code> stays within a
									directory. <code>**</code> crosses directories. Clear all lines and save to reset.
								</>
							)}
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
						{!removed && (
							<div>
								<Button
									variant="outline"
									size="sm"
									aria-label={state === "saving" ? undefined : `Save generated paths for ${name}`}
									disabled={busy || !dirty || invalid || conflicted}
									onClick={() => {
										save().catch(() => setState("error"));
									}}
								>
									{state === "saving" ? <Spinner /> : null}
									{state === "saving" ? "Saving…" : "Save generated paths"}
								</Button>
							</div>
						)}
					</Field>
				</CollapsibleContent>
			</Item>
			{guard.dialog}
		</Collapsible>
	);
}
