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
						Open a pull request, merge request or issue to see its review status and your feedback.
						Expand it to jump to comments or explore supporting observations. On a list, press the
						Hephaestus mark beside a title to preview that work.
					</p>
				</div>
			</li>
			<li className="flex gap-3 rounded-lg border border-border p-3.5">
				<span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted">
					<AppWindowIcon aria-hidden className="size-4 text-mentor" />
				</span>
				<div className="flex min-w-0 flex-col gap-1">
					<h3 className="text-sm font-medium">Changes are confirmed</h3>
					<p className="text-xs text-muted-foreground">
						Asking for a review opens a small Hephaestus window that shows exactly what will happen;
						nothing changes until you confirm there. Approving feedback stays in Hephaestus.
					</p>
				</div>
			</li>
		</ol>
	);
}
