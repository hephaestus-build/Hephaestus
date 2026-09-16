import { SearchIcon } from "lucide-react";
import { useState } from "react";

import { cn } from "cn";
import type { GitLabGroup } from "@/api/types.gen";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";

import { useWizard } from "./wizard-context";

export function SelectGroupStep() {
	const { state, dispatch } = useWizard();
	const [search, setSearch] = useState("");

	const query = search.toLowerCase();
	const filteredGroups = state.groups.filter(
		(g) => g.name.toLowerCase().includes(query) || g.fullPath.toLowerCase().includes(query),
	);

	if (state.groups.length === 0) {
		return (
			<p role="status" className="py-4 text-center text-sm text-muted-foreground">
				No groups found. Your token may lack the required scopes, or you are not a member of any
				group.
			</p>
		);
	}

	return (
		<div className="flex flex-col gap-3">
			<InputGroup>
				<InputGroupAddon>
					<SearchIcon />
				</InputGroupAddon>
				<InputGroupInput
					placeholder="Search groups..."
					value={search}
					onChange={(e) => setSearch(e.target.value)}
					aria-label="Search groups"
				/>
			</InputGroup>

			<RadioGroup
				value={state.selectedGroup?.fullPath ?? ""}
				onValueChange={(value) => {
					const group = state.groups.find((g) => g.fullPath === value);
					if (group) dispatch({ type: "SELECT_GROUP", group });
				}}
				className="max-h-64 divide-y overflow-y-auto rounded-lg border"
				aria-label="Available GitLab groups"
			>
				{filteredGroups.map((group) => (
					<GroupItem
						key={group.id}
						group={group}
						isSelected={state.selectedGroup?.fullPath === group.fullPath}
					/>
				))}
				{filteredGroups.length === 0 && (
					<p role="status" className="p-4 text-center text-sm text-muted-foreground">
						No groups match &ldquo;{search}&rdquo;
					</p>
				)}
			</RadioGroup>
		</div>
	);
}

function GroupItem({ group, isSelected }: { group: GitLabGroup; isSelected: boolean }) {
	const inputId = `group-${group.id}`;
	return (
		<label
			htmlFor={inputId}
			className={cn(
				"flex cursor-pointer items-center gap-3 px-3 py-2.5 transition-colors hover:bg-muted/50",
				isSelected && "bg-muted",
			)}
		>
			<RadioGroupItem id={inputId} value={group.fullPath} />
			<Avatar className="size-7 rounded-md">
				{group.avatarUrl && <AvatarImage src={group.avatarUrl} alt={group.name} />}
				<AvatarFallback className="rounded-md bg-muted text-xs">
					{group.name.slice(0, 2).toUpperCase()}
				</AvatarFallback>
			</Avatar>
			<div className="flex min-w-0 flex-1 flex-col">
				<span className="truncate text-sm font-medium">{group.name}</span>
				<span className="truncate text-xs text-muted-foreground">{group.fullPath}</span>
			</div>
			{group.visibility && (
				<Badge variant="outline" size="xs" className="shrink-0">
					{group.visibility}
				</Badge>
			)}
		</label>
	);
}
