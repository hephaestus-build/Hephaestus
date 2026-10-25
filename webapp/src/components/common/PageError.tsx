import { Link } from "@tanstack/react-router";
import { AlertCircleIcon, LockIcon, SearchXIcon } from "lucide-react";

import { Button, buttonVariants } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";

/** Why the page cannot be shown. Only a failed load offers a retry, and only where one can help. */
export type PageErrorProps =
	| { cause: "not-found" }
	| { cause: "no-access" }
	| { cause: "failed"; onRetry?: () => void };

const COPY = {
	"not-found": {
		icon: <SearchXIcon />,
		title: "We could not find this page",
		description: "It may have been moved or deleted, or you may not have access to it.",
	},
	"no-access": {
		icon: <LockIcon />,
		title: "You do not have access to this page",
		description: "Ask a workspace admin or instance admin for access.",
	},
	failed: {
		icon: <AlertCircleIcon />,
		title: "We could not load this page",
		description: "Try again. If it keeps failing, contact your instance operator.",
	},
} as const;

/** A failure that a retry cannot fix, such as a refused request, sends the reader on instead. */
const FAILED_FOR_GOOD =
	"Go to the home page to continue. If this keeps happening, contact your instance operator.";

/** Fills the page in place of its content: the page's own title is its heading. */
export function PageError(props: PageErrorProps) {
	const { icon, title, description } = COPY[props.cause];
	const onRetry = props.cause === "failed" ? props.onRetry : undefined;
	return (
		<Empty>
			<EmptyHeader>
				<EmptyMedia variant="icon">{icon}</EmptyMedia>
				<EmptyTitle role="heading" aria-level={1}>
					{title}
				</EmptyTitle>
				<EmptyDescription>
					{props.cause === "failed" && onRetry === undefined ? FAILED_FOR_GOOD : description}
				</EmptyDescription>
			</EmptyHeader>
			<EmptyContent className="flex-row flex-wrap justify-center">
				{onRetry && <Button onClick={onRetry}>Retry</Button>}
				<Link to="/" className={buttonVariants({ variant: onRetry ? "outline" : "default" })}>
					Go to home page
				</Link>
			</EmptyContent>
		</Empty>
	);
}
