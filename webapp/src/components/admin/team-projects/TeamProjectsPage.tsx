import { UsersIcon } from "lucide-react";
import { type SubmitEvent, useState } from "react";

import { EmptyState } from "@/components/common/EmptyState";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Button } from "@/components/ui/button";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { hasText } from "@/lib/text";

import type { TeamProjectPerson, TeamProjectRow } from "./team-projects";
import { TeamProjectCard, type TeamProjectCardProps } from "./TeamProjectCard";

export type TeamProjectsState =
	| { status: "loading"; rows: number }
	| { status: "error"; error: unknown; onRetry?: () => void }
	| {
			status: "ready";
			teams: readonly TeamProjectRow[];
			/** Every current workspace member, the only people a team can draw from. */
			workspaceMembers: readonly TeamProjectPerson[];
	  };

export interface TeamProjectsPageProps extends Pick<
	TeamProjectCardProps,
	"onAddMember" | "onRemoveMember"
> {
	state: TeamProjectsState;
	onCreate: (name: string) => void;
	creating?: boolean;
	/** The team whose add or removal is in flight. */
	pendingTeamId?: string;
}

function CreateTeamProject({
	onCreate,
	creating,
}: Pick<TeamProjectsPageProps, "onCreate" | "creating">) {
	const [name, setName] = useState("");
	function submit(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		if (hasText(name.trim())) {
			onCreate(name.trim());
			setName("");
		}
	}
	return (
		<form onSubmit={submit} className="flex flex-wrap items-end gap-2">
			<Field className="w-full sm:w-64">
				<FieldLabel htmlFor="team-project-name">Team project name</FieldLabel>
				<Input
					id="team-project-name"
					value={name}
					onChange={(event) => setName(event.target.value)}
				/>
			</Field>
			<Button type="submit" pending={creating} disabled={!hasText(name.trim())}>
				{creating === true ? "Creating…" : "Create team project"}
			</Button>
		</form>
	);
}

/**
 * The workspace admin's team projects: create one, and add or remove its members from the current workspace members.
 * The admin sees each team, its members and its counts, never its content, and the page says so.
 */
export function TeamProjectsPage({
	state,
	onCreate,
	creating = false,
	onAddMember,
	onRemoveMember,
	pendingTeamId,
}: TeamProjectsPageProps) {
	return (
		<section aria-labelledby="team-projects-heading" className="flex flex-col gap-4">
			<div className="flex flex-col gap-1">
				<h2 id="team-projects-heading" className="text-lg font-semibold">
					Team projects
				</h2>
				<p className="text-sm text-muted-foreground">
					Only a team’s current members read its discussion, decisions and reviewed versions.
					Workspace admins see each team, its members and its counts.
				</p>
				<p className="text-sm text-muted-foreground">
					A member you remove loses access to the team at once.
				</p>
			</div>
			<CreateTeamProject onCreate={onCreate} creating={creating} />
			<TeamProjectsBody
				state={state}
				onAddMember={onAddMember}
				onRemoveMember={onRemoveMember}
				pendingTeamId={pendingTeamId}
			/>
		</section>
	);
}

function TeamProjectsBody({
	state,
	onAddMember,
	onRemoveMember,
	pendingTeamId,
}: Omit<TeamProjectsPageProps, "onCreate" | "creating">) {
	switch (state.status) {
		case "loading": {
			return (
				<ul aria-hidden className="flex flex-col gap-3">
					{Array.from({ length: state.rows }, (_, index) => (
						<li key={index}>
							<Skeleton className="h-32 w-full" />
						</li>
					))}
				</ul>
			);
		}
		case "error": {
			return (
				<QueryErrorAlert
					error={state.error}
					title="Team projects could not load"
					onRetry={state.onRetry}
				/>
			);
		}
		case "ready": {
			if (state.teams.length === 0) {
				return (
					<EmptyState
						icon={<UsersIcon />}
						title="No team projects yet"
						headingLevel={3}
						description="Create a team project, then add its members from this workspace."
					/>
				);
			}
			return (
				<ul className="flex flex-col gap-3">
					{state.teams.map((team) => {
						const inTeam = new Set(team.members.map((member) => member.accountId));
						return (
							<li key={team.id}>
								<TeamProjectCard
									team={team}
									candidates={state.workspaceMembers.filter(
										(person) => !inTeam.has(person.accountId),
									)}
									onAddMember={onAddMember}
									onRemoveMember={onRemoveMember}
									pending={team.id === pendingTeamId}
								/>
							</li>
						);
					})}
				</ul>
			);
		}
	}
}
