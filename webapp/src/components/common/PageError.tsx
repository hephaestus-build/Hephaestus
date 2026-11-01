import { Link } from "@tanstack/react-router";
import { AlertCircleIcon, LockIcon, SearchXIcon } from "lucide-react";

import { EmptyState } from "@/components/common/EmptyState";
import { Button, buttonVariants } from "@/components/ui/button";
import { copyToClipboard } from "@/lib/clipboard";
import { PROBLEM_COPY } from "@/lib/problem-detail";

/** Why the page cannot be shown. Only a failed load offers a retry, and only where one can help. */
export type PageErrorProps =
	| { cause: "not-found" }
	| { cause: "no-access" }
	| { cause: "failed"; onRetry?: () => void; reference?: string };

const NOT_FOUND = PROBLEM_COPY["not-found"];
const NO_ACCESS = PROBLEM_COPY["no-access"];

function copyOf(props: PageErrorProps) {
	switch (props.cause) {
		case "not-found": {
			return {
				icon: <SearchXIcon />,
				title: NOT_FOUND.retitle("this page"),
				// Not the shared `next`, which says to go back: this page offers the home page instead.
				description: "It may have been deleted or moved. Go to the home page to continue.",
			};
		}
		case "no-access": {
			return {
				icon: <LockIcon />,
				title: NO_ACCESS.retitle("this page"),
				description: NO_ACCESS.next,
			};
		}
		case "failed": {
			return {
				icon: <AlertCircleIcon />,
				title: "We could not load this page",
				// A failure that a retry cannot fix, such as a refused request, sends the reader on instead.
				description:
					props.onRetry === undefined
						? "Go to the home page to continue. If it keeps failing, contact your instance operator."
						: "Try again. If it keeps failing, contact your instance operator.",
			};
		}
	}
}

/**
 * Fills the page in place of its content: the page's own title is its heading. A caller announces
 * an error as an alert. A missing page is not an error and is not announced: the reader arrives on
 * it like any page.
 */
export function PageError(props: PageErrorProps) {
	const onRetry = props.cause === "failed" ? props.onRetry : undefined;
	const reference = props.cause === "failed" ? props.reference : undefined;
	return (
		<EmptyState
			variant="plain"
			headingLevel={1}
			{...copyOf(props)}
			action={
				<div className="flex flex-col items-center gap-3">
					<div className="flex flex-wrap justify-center gap-2">
						{onRetry && <Button onClick={onRetry}>Retry</Button>}
						<Link to="/" className={buttonVariants({ variant: onRetry ? "outline" : "default" })}>
							Go to the home page
						</Link>
					</div>
					{reference !== undefined && (
						// The first eight characters are enough to read out; the copy is the whole reference.
						<p className="flex items-center gap-1 text-sm text-muted-foreground">
							Reference <span className="font-mono">{reference.slice(0, 8)}</span>
							<Button
								variant="ghost"
								size="sm"
								onClick={() => {
									copyToClipboard(reference, "Reference copied");
								}}
							>
								Copy reference
							</Button>
						</p>
					)}
				</div>
			}
		/>
	);
}
