import { AppWindowIcon, PanelTopIcon } from "lucide-react";

/**
 * Where the extension shows up once a site is allowed, and how a change is made: the practice review
 * sits in the work's own page, and anything that changes a review is confirmed in the extension's own
 * window.
 */
export function UsageGuide() {
	return (
		<ol className="grid gap-3 sm:grid-cols-2">
			<li className="flex gap-3 rounded-lg border border-border p-3.5">
				<span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted">
					<PanelTopIcon aria-hidden className="size-4 text-mentor" />
				</span>
				<div className="flex min-w-0 flex-col gap-1">
					<h3 className="text-sm font-medium">On the work itself</h3>
					<p className="text-xs text-muted-foreground">
						Open a pull request, merge request or issue to see its review status and your comments.
						Expand the practice review to go to a comment or see the observations behind the review.
						On a list, select the Hephaestus button after a title to preview that work.
					</p>
				</div>
			</li>
			<li className="flex gap-3 rounded-lg border border-border p-3.5">
				<span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted">
					<AppWindowIcon aria-hidden className="size-4 text-mentor" />
				</span>
				<div className="flex min-w-0 flex-col gap-1">
					<h3 className="text-sm font-medium">You confirm every change</h3>
					<p className="text-xs text-muted-foreground">
						Requesting a review opens a small Hephaestus window that names the work and the
						workspace. Nothing changes until you confirm there. You approve feedback in Hephaestus,
						not in the extension.
					</p>
				</div>
			</li>
		</ol>
	);
}
