import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
	AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

interface AdminLeagueSettingsProps {
	isResetting: boolean;
	onResetLeagues: () => void;
}

/**
 * Component for managing league settings in admin view
 */
export function AdminLeagueSettings({ isResetting, onResetLeagues }: AdminLeagueSettingsProps) {
	return (
		<div className="space-y-6">
			<div>
				<h2 className="mb-4 text-lg font-semibold">Leagues</h2>
				<Card>
					<CardContent>
						<div className="space-y-4">
							<p className="mb-4 text-sm text-muted-foreground">
								Reset and recalculate all leagues. This will clear current league assignments and
								recalculate based on the latest data.
							</p>

							<AlertDialog>
								<AlertDialogTrigger
									render={<Button variant="destructive">Reset and Recalculate Leagues</Button>}
								/>
								<AlertDialogContent>
									<AlertDialogHeader>
										<AlertDialogTitle>Reset and recalculate leagues?</AlertDialogTitle>
										<AlertDialogDescription>
											This action will clear all current league assignments and recalculate them
											based on the latest data. This process cannot be undone and may take some time
											to complete.
										</AlertDialogDescription>
									</AlertDialogHeader>
									<AlertDialogFooter>
										<AlertDialogCancel>Cancel</AlertDialogCancel>
										<AlertDialogAction onClick={onResetLeagues} disabled={isResetting}>
											{isResetting ? "Resetting..." : "Reset and Recalculate"}
										</AlertDialogAction>
									</AlertDialogFooter>
								</AlertDialogContent>
							</AlertDialog>
						</div>
					</CardContent>
				</Card>
			</div>
		</div>
	);
}
