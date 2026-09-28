import { Link } from "@tanstack/react-router";
import type { ComponentProps } from "react";

import type { ReviewListTarget } from "./review-outcomes";

export interface ReviewListLinkProps extends Omit<ComponentProps<"a">, "href"> {
	workspaceSlug: string;
	destination: ReviewListTarget;
}

/**
 * A list tab with its filters set. One branch per list because a route's search is typed by its
 * literal path, which a union of paths loses. No `detail` is carried, so opening a list closes any
 * open level, and no overview range either: the destination's own days say what it lists.
 */
export function ReviewListLink({ workspaceSlug, destination, ...props }: ReviewListLinkProps) {
	switch (destination.list) {
		case "runs": {
			return (
				<Link
					{...props}
					to="/w/$workspaceSlug/admin/practices/reviews/runs"
					params={{ workspaceSlug }}
					search={destination.search}
				/>
			);
		}
		case "observations": {
			return (
				<Link
					{...props}
					to="/w/$workspaceSlug/admin/practices/reviews/observations"
					params={{ workspaceSlug }}
					search={destination.search}
				/>
			);
		}
		case "feedback": {
			return (
				<Link
					{...props}
					to="/w/$workspaceSlug/admin/practices/reviews/feedback"
					params={{ workspaceSlug }}
					search={destination.search}
				/>
			);
		}
	}
}
