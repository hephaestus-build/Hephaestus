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
import { Spinner } from "@/components/ui/spinner";

export interface AdminProductFeedbackMoveReportsProps {
	/** Saved reports that the private repository can take now. */
	count: number;
	moving: boolean;
	onMove: () => void;
}

function reports(count: number): string {
	return count === 1 ? "1 report" : `${count} reports`;
}

/** Offers what waits for the admins to the repository that can now take it. Renders nothing otherwise. */
export function AdminProductFeedbackMoveReports({
	count,
	moving,
	onMove,
}: AdminProductFeedbackMoveReportsProps) {
	if (count === 0) {
		return null;
	}
	return (
		<div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border p-3">
			<p className="text-sm">{reports(count)} can move to the private repository.</p>
			<AlertDialog>
				<AlertDialogTrigger
					render={
						<Button type="button" size="sm" disabled={moving}>
							{moving && <Spinner className="size-4" />}
							{moving ? "Moving…" : "Move to GitHub"}
						</Button>
					}
				/>
				<AlertDialogContent>
					<AlertDialogHeader>
						<AlertDialogTitle>Move {reports(count)} to GitHub?</AlertDialogTitle>
						<AlertDialogDescription>
							Each one becomes a private issue, so its text leaves this instance and the sender’s
							name stays here. An AI model drafts the newer ones. Older reports go as written, and
							no AI model reads them. You cannot take this back.
						</AlertDialogDescription>
					</AlertDialogHeader>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction onClick={onMove}>Move to GitHub</AlertDialogAction>
					</AlertDialogFooter>
				</AlertDialogContent>
			</AlertDialog>
		</div>
	);
}
