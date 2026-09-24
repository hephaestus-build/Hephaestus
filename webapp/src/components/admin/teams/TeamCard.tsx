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
		<Card variant={team.hidden ? "muted" : "default"} className="flex flex-col">
			<CardHeader className="pb-4">
				<div className="flex flex-wrap items-start justify-between gap-2">
					<div className="min-w-0 flex-1">
						<div className="flex min-w-0 items-center gap-2">
							<Heading
								className={cn(
									"truncate text-lg font-semibold",
									team.hidden ? "text-muted-foreground" : "",
								)}
								title={team.name}
							>
								{team.name}
							</Heading>
							{team.hidden && (
								<span className="rounded bg-muted px-1.5 py-0.5 text-2xs tracking-wide text-muted-foreground uppercase">
									Hidden
								</span>
							)}
						</div>
						<div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground sm:gap-4">
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
