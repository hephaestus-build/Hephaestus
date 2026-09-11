import { SearchIcon } from "lucide-react";
import { useState } from "react";

import { cn } from "cn";
import type { GitLabGroup } from "@/api/types.gen";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
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
			<p role="status" className="text-sm text-muted-foreground text-center py-4">
				No groups found. Your token may lack the required scopes, or you are not a member of any
				group.
			</p>
		);
	}

	return (
		<div className="flex flex-col gap-3">
			<div className="relative">
				<SearchIcon className="absolute left-2.5 top-1/2 -translate-y-1/2 size-3.5 text-muted-foreground pointer-events-none" />
				<Input
					placeholder="Search groups..."
					value={search}
					onChange={(e) => setSearch(e.target.value)}
					className="pl-8"
					aria-label="Search groups"
				/>
			</div>

			<RadioGroup
				value={state.selectedGroup?.fullPath ?? ""}
				onValueChange={(value) => {
					const group = state.groups.find((g) => g.fullPath === value);
					if (group) dispatch({ type: "SELECT_GROUP", group });
				}}
				className="max-h-64 overflow-y-auto rounded-lg border divide-y"
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
					<p role="status" className="text-sm text-muted-foreground p-4 text-center">
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
				"flex items-center gap-3 px-3 py-2.5 cursor-pointer hover:bg-muted/50 transition-colors",
				isSelected && "bg-muted",
			)}
		>
			<RadioGroupItem id={inputId} value={group.fullPath} />
			<Avatar className="size-7 rounded-md">
				{group.avatarUrl && <AvatarImage src={group.avatarUrl} alt={group.name} />}
				<AvatarFallback className="rounded-md text-xs bg-muted">
					{group.name.slice(0, 2).toUpperCase()}
				</AvatarFallback>
			</Avatar>
			<div className="flex flex-col min-w-0 flex-1">
				<span className="text-sm font-medium truncate">{group.name}</span>
				<span className="text-xs text-muted-foreground truncate">{group.fullPath}</span>
			</div>
			{group.visibility && (
				<Badge variant="outline" className="text-[10px] shrink-0">
					{group.visibility}
				</Badge>
			)}
		</label>
	);
}
