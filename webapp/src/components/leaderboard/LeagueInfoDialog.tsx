import { Info, Star } from "lucide-react";

import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";

import { LeagueIcon } from "./LeagueIcon";
import { Leagues } from "./utils";

interface LeagueInfoDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
}

export function LeagueInfoDialog({ open, onOpenChange }: LeagueInfoDialogProps) {
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent className="max-w-2xl">
				<DialogHeader>
					<DialogTitle className="flex items-center gap-2">
						<Info className="h-5 w-5" />
						All Leagues
					</DialogTitle>
					<DialogDescription>League tiers and point calculations</DialogDescription>
				</DialogHeader>

				<div className="flex flex-col gap-2">
					{Leagues.map((league) => (
						<div key={league.name} className="flex items-center gap-4">
							<LeagueIcon leaguePoints={league.minPoints + 1} size="default" />
							<span className="font-semibold">{league.name}</span>
							<div className="flex items-center gap-1 text-sm text-muted-foreground">
								<Star className="h-4 w-4" />
								{league.maxPoints === Number.POSITIVE_INFINITY ? (
									<span>{league.minPoints}+</span>
								) : (
									<span>
										{league.minPoints} - {league.maxPoints}
									</span>
								)}
							</div>
						</div>
					))}
				</div>

				<div className="border-t pt-4">
					<h4 className="mb-2 text-sm font-semibold">League Points Calculation</h4>
					<div className="text-sm text-muted-foreground">
						<p className="mb-2">
							Your league points are updated weekly using the following formula:
						</p>
						<div className="rounded-md bg-muted p-3 font-mono text-xs">
							<p>newPoints = oldPoints + (K × (performanceBonus + placementBonus - decay))</p>
							<p className="mt-2">Where:</p>
							<ul className="mt-1 ml-5 list-disc space-y-1">
								<li>K: sensitivity factor (1.1 - 2.0, higher for newer players)</li>
								<li>performanceBonus = 10 × √score</li>
								<li>placementBonus = 20 × (4 - rank) for top 3, 0 otherwise</li>
								<li>decay = max(10, 5% of current points)</li>
							</ul>
						</div>
						<p className="mt-2">
							This system is inspired by the Elo rating system used in chess. New players start with
							1000 points.
						</p>
					</div>
				</div>
			</DialogContent>
		</Dialog>
	);
}
