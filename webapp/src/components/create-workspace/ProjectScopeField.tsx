import { cn } from "cn";
import { SearchIcon } from "lucide-react";
import { type SubmitEvent, useId, useState } from "react";

import type { GitLabProject } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
	FieldLegend,
	FieldSet,
} from "@/components/ui/field";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";

import type { ProjectScope } from "./wizard-context";

/** One page of the group's projects, as the server reads it with the token. */
export type ProjectChoices =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			projects: GitLabProject[];
			/** The page shown, from 1. */
			page: number;
			hasNextPage: boolean;
			/** The search this page answers; empty for the whole group. */
			search: string;
	  };

export interface ProjectScopeFieldProps {
	scope: ProjectScope;
	onScopeChange: (scope: ProjectScope) => void;
	/** The chosen project IDs, on this page or another. */
	selectedIds: readonly number[];
	onSelectedChange: (projectId: number, selected: boolean) => void;
	/** The projects to choose from. Shown only while the scope is selected projects. */
	choices: ProjectChoices;
	onSearch: (search: string) => void;
	onPreviousPage: () => void;
	onNextPage: () => void;
}

/**
 * Which of the group's projects the workspace monitors. All projects is the default and needs no
 * list; choosing selected projects lists the group's projects a page at a time, and a choice stays
 * when the page or the search changes.
 */
export function ProjectScopeField({
	scope,
	onScopeChange,
	selectedIds,
	onSelectedChange,
	choices,
	onSearch,
	onPreviousPage,
	onNextPage,
}: ProjectScopeFieldProps) {
	const id = useId();
	return (
		<FieldSet>
			<FieldLegend variant="label">Projects to monitor</FieldLegend>
			<FieldDescription>
				This choice covers project content, such as merge requests and issues. Workspace members and
				teams still come from the whole group.
			</FieldDescription>
			<RadioGroup
				value={scope}
				onValueChange={(value) => onScopeChange(value)}
				aria-label="Projects to monitor"
			>
				<Field orientation="horizontal">
					<RadioGroupItem id={`${id}-all`} value="ALL" />
					<FieldContent>
						<FieldLabel htmlFor={`${id}-all`}>All projects</FieldLabel>
						<FieldDescription>
							Every project in the group and its subgroups, including projects added later.
						</FieldDescription>
					</FieldContent>
				</Field>
				<Field orientation="horizontal">
					<RadioGroupItem id={`${id}-selected`} value="SELECTED" />
					<FieldContent>
						<FieldLabel htmlFor={`${id}-selected`}>Selected projects</FieldLabel>
						<FieldDescription>
							Only the projects you choose. Projects added to the group later are not monitored.
						</FieldDescription>
					</FieldContent>
				</Field>
			</RadioGroup>
			{scope === "SELECTED" && (
				<ProjectChoiceList
					selectedIds={selectedIds}
					onSelectedChange={onSelectedChange}
					choices={choices}
					onSearch={onSearch}
					onPreviousPage={onPreviousPage}
					onNextPage={onNextPage}
				/>
			)}
		</FieldSet>
	);
}

function ProjectChoiceList({
	selectedIds,
	onSelectedChange,
	choices,
	onSearch,
	onPreviousPage,
	onNextPage,
}: Omit<ProjectScopeFieldProps, "scope" | "onScopeChange">) {
	const id = useId();
	// The search being typed; the list shows the last one sent. It starts from the search the shown
	// page answers, so returning to selected projects does not show a filtered list under an empty box.
	const [draft, setDraft] = useState(() => (choices.status === "ready" ? choices.search : ""));
	const submit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		onSearch(draft.trim());
	};
	const count = selectedIds.length;

	return (
		<div className="flex flex-col gap-3">
			<form role="search" onSubmit={submit} className="flex gap-2">
				<InputGroup>
					<InputGroupAddon>
						<SearchIcon aria-hidden />
					</InputGroupAddon>
					<InputGroupInput
						type="search"
						aria-label="Search projects"
						placeholder="Search projects"
						value={draft}
						onChange={(event) => setDraft(event.target.value)}
					/>
				</InputGroup>
				<Button type="submit" variant="outline">
					Search
				</Button>
			</form>

			{/* Exists before its words change, so a screen reader hears each new count. */}
			<p role="status" className="text-sm text-muted-foreground">
				{count === 0
					? "Choose at least one project, or monitor all projects."
					: `${count} ${count === 1 ? "project" : "projects"} selected`}
			</p>

			{choices.status === "loading" && (
				<div className="flex flex-col gap-3" aria-busy="true">
					<span className="sr-only">Loading projects…</span>
					{["w-40", "w-56", "w-32"].map((width) => (
						<div key={width} className="flex items-start gap-3" aria-hidden>
							<Skeleton className="size-4 shrink-0" />
							<div className="flex flex-col gap-1.5">
								<Skeleton className={cn("h-4", width)} />
								<Skeleton className="h-3 w-48" />
							</div>
						</div>
					))}
				</div>
			)}

			{choices.status === "error" && (
				<QueryErrorAlert
					error={choices.error}
					title="We could not load the group’s projects"
					onRetry={choices.onRetry}
				/>
			)}

			{/* About this page only: a page can come back empty while later pages still hold projects. */}
			{choices.status === "ready" && choices.projects.length === 0 && (
				<p className="text-sm text-muted-foreground">
					{hasText(choices.search)
						? "No matching projects on this page."
						: "No projects to show on this page."}
				</p>
			)}

			{choices.status === "ready" && choices.projects.length > 0 && (
				<FieldGroup aria-label="Projects" role="group" className="gap-3">
					{choices.projects.map((project) => (
						<Field key={project.id} orientation="horizontal">
							<Checkbox
								id={`${id}-${project.id}`}
								checked={selectedIds.includes(project.id)}
								onCheckedChange={(checked) => onSelectedChange(project.id, checked)}
							/>
							<FieldContent className="min-w-0">
								<FieldLabel htmlFor={`${id}-${project.id}`}>{project.name}</FieldLabel>
								<FieldDescription>
									<span className="break-all">{project.fullPath}</span>
								</FieldDescription>
							</FieldContent>
						</Field>
					))}
				</FieldGroup>
			)}

			{choices.status === "ready" && (choices.page > 1 || choices.hasNextPage) && (
				<nav aria-label="Project pages" className="flex items-center justify-between gap-2">
					<Button variant="outline" size="sm" disabled={choices.page <= 1} onClick={onPreviousPage}>
						Previous page
					</Button>
					<span className="text-sm text-muted-foreground">Page {choices.page}</span>
					<Button variant="outline" size="sm" disabled={!choices.hasNextPage} onClick={onNextPage}>
						Next page
					</Button>
				</nav>
			)}
		</div>
	);
}
