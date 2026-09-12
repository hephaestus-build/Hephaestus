import { Eye, EyeOff, Users } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { LabelInfo, TeamInfo } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";

export interface TeamCardProps {
	team: TeamInfo;
	memberCount: number;
	labelFilter?: string;
	headingLevel?: 2 | 3 | 4 | 5 | 6;
	onToggleVisibility: (hidden: boolean) => void;
	getCatalogLabels: (repoId: number) => LabelInfo[];
	children?: ReactNode;
}

export function TeamCard({
	team,
	memberCount,
	headingLevel = 2,
	onToggleVisibility,
	children,
}: TeamCardProps) {
	const Heading = `h${headingLevel}` as const;

	return (
		<Card className={cn("flex flex-col gap-3", team.hidden ? "bg-muted/40" : "")}>
			<CardHeader className="pb-4">
				<div className="flex flex-wrap items-start justify-between gap-2">
					<div className="min-w-0 flex-1">
						<div className="flex items-center gap-2 min-w-0">
							<Heading
								className={cn(
									"font-semibold text-lg truncate",
									team.hidden ? "text-muted-foreground" : "",
								)}
								title={team.name}
							>
								{team.name}
							</Heading>
							{team.hidden && (
								<span className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground uppercase tracking-wide">
									Hidden
								</span>
							)}
						</div>
						<div className="flex flex-wrap items-center gap-2 sm:gap-4 text-sm text-muted-foreground">
							<span className="flex items-center gap-1">
								<Users className="h-3 w-3" /> {memberCount}{" "}
								{memberCount === 1 ? "member" : "members"}
							</span>
							<span>
								{team.repositories.length} {team.repositories.length === 1 ? "repo" : "repos"}
							</span>
						</div>
					</div>
					<div className="flex items-center gap-1">
						<Button
							variant="ghost"
							size="icon"
							onClick={() => onToggleVisibility(!team.hidden)}
							className="h-8 w-8"
							title={team.hidden ? "Show team" : "Hide team"}
						>
							{team.hidden ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
						</Button>
					</div>
				</div>
			</CardHeader>
			<CardContent>{children}</CardContent>
		</Card>
	);
}

export default TeamCard;
