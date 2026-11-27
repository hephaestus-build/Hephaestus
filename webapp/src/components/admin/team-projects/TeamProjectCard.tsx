import { useState } from "react";

import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { getInitials } from "@/lib/avatar";
import { formatNumber } from "@/lib/numbers";
import { hasText } from "@/lib/text";

import { personLabel, type TeamProjectPerson, type TeamProjectRow } from "./team-projects";

export interface TeamProjectCardProps {
	team: TeamProjectRow;
	/** Current workspace members who are not in this team yet. */
	candidates: readonly TeamProjectPerson[];
	onAddMember: (team: TeamProjectRow, accountId: number) => void;
	/** Asks before the removal; see `RemoveTeamMemberDialog`. */
	onRemoveMember: (team: TeamProjectRow, member: TeamProjectPerson) => void;
	/** An add or a removal for this team is in flight. */
	pending?: boolean;
}

/** One team project on the staff screen: its members, its review count, and adding or removing a member. */
export function TeamProjectCard({
	team,
	candidates,
	onAddMember,
	onRemoveMember,
	pending = false,
}: TeamProjectCardProps) {
	// The select only holds the choice: Base UI commits a typed match on a closed select, so a keystroke must never add.
	const [chosen, setChosen] = useState<string | null>(null);
	const items = candidates.map((person) => ({
		value: String(person.accountId),
		label: personLabel(person),
	}));
	return (
		<article aria-label={`Team ${team.name}`} className="flex flex-col gap-3 rounded-lg border p-4">
			<div className="flex flex-wrap items-baseline justify-between gap-2">
				<h3 className="text-base font-semibold">
					Team <bdi>{team.name}</bdi>
				</h3>
				<p className="text-sm text-muted-foreground">
					{formatNumber(team.members.length)} {team.members.length === 1 ? "member" : "members"} ·{" "}
					{formatNumber(team.reviewCount)} {team.reviewCount === 1 ? "review" : "reviews"}
				</p>
			</div>
			{team.members.length === 0 ? (
				<p className="text-sm text-muted-foreground">No members yet. Add one below.</p>
			) : (
				<ul aria-label={`Members of Team ${team.name}`} className="flex flex-col gap-2">
					{team.members.map((member) => (
						<li key={member.accountId} className="flex items-center gap-2 text-sm">
							<Avatar className="size-6">
								{hasText(member.avatarUrl) && <AvatarImage src={member.avatarUrl} alt="" />}
								<AvatarFallback>{getInitials(member.displayName)}</AvatarFallback>
							</Avatar>
							<bdi className="min-w-0 flex-1 truncate">{personLabel(member)}</bdi>
							<Button
								variant="ghost"
								size="xs"
								aria-label={`Remove ${member.displayName} from Team ${team.name}`}
								disabled={pending}
								onClick={() => onRemoveMember(team, member)}
							>
								Remove
							</Button>
						</li>
					))}
				</ul>
			)}
			{items.length > 0 && (
				<div className="flex flex-wrap items-center gap-2">
					<Select<string> value={chosen} items={items} disabled={pending} onValueChange={setChosen}>
						<SelectTrigger
							size="sm"
							className="w-full sm:w-64"
							aria-label={`Choose a member to add to Team ${team.name}`}
						>
							<SelectValue placeholder="Choose a workspace member" />
						</SelectTrigger>
						<SelectContent aria-label={`Choose a member to add to Team ${team.name}`}>
							{items.map((item) => (
								<SelectItem key={item.value} value={item.value}>
									{item.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
					<Button
						variant="outline"
						size="sm"
						pending={pending}
						disabled={chosen === null}
						onClick={() => {
							if (chosen !== null) {
								onAddMember(team, Number(chosen));
								setChosen(null);
							}
						}}
					>
						Add to team
					</Button>
				</div>
			)}
		</article>
	);
}
