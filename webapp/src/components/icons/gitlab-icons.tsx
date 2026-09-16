import type { SVGProps } from "react";
/**
 * React wrapper components for GitLab SVG icons from `@gitlab/svgs`.
 *
 * Path data is copied from the installed `@gitlab/svgs` dist so we avoid
 * adding an SVGR build plugin for just a handful of icons.  All source SVGs
 * use `viewBox="0 0 16 16"` and a single `<path>` element.
 *
 * The factory mirrors the API surface of `@primer/octicons-react`:
 * - rest-prop spreading for native SVG attributes, `ref` among them
 * - conditional `aria-hidden` based on `aria-label` / `aria-labelledby`
 */

import { hasText } from "@/lib/text";

export interface GitLabIconProps extends Omit<SVGProps<SVGSVGElement>, "children"> {
	/** Icon size in pixels. Defaults to 16. */
	size?: number;
}

function createGitLabIcon(pathData: string, displayName: string) {
	function Icon({ size = 16, ...rest }: GitLabIconProps) {
		const labelled = hasText(rest["aria-label"]) || hasText(rest["aria-labelledby"]);
		return (
			<svg
				width={size}
				height={size}
				viewBox="0 0 16 16"
				fill="currentColor"
				{...rest}
				aria-hidden={labelled ? undefined : "true"}
				role={labelled ? "img" : undefined}
				focusable="false"
			>
				<path fillRule="evenodd" clipRule="evenodd" d={pathData} />
			</svg>
		);
	}
	Icon.displayName = displayName;
	return Icon;
}

/** Open merge request — `merge-request.svg` */
export const GitLabMergeRequestIcon = createGitLabIcon(
	"M10.34 1.22a.75.75 0 00-1.06 0L7.53 2.97 7 3.5l.53.53 1.75 1.75a.75.75 0 101.06-1.06l-.47-.47h.63c.69 0 1.25.56 1.25 1.25v4.614a2.501 2.501 0 101.5 0V5.5a2.75 2.75 0 00-2.75-2.75h-.63l.47-.47a.75.75 0 000-1.06zM13.5 12.5a1 1 0 11-2 0 1 1 0 012 0zm-9 0a1 1 0 11-2 0 1 1 0 012 0zm1.5 0a2.5 2.5 0 11-3.25-2.386V5.886a2.501 2.501 0 111.5 0v4.228A2.501 2.501 0 016 12.5zm-1.5-9a1 1 0 11-2 0 1 1 0 012 0z",
	"GitLabMergeRequestIcon",
);

/** Closed merge request — `merge-request-close.svg` */
export const GitLabMergeRequestClosedIcon = createGitLabIcon(
	"M1.22 1.22a.75.75 0 011.06 0L3.5 2.44l1.22-1.22a.75.75 0 011.06 1.06L4.56 3.5l1.22 1.22a.75.75 0 01-1.06 1.06L3.5 4.56 2.28 5.78a.75.75 0 01-1.06-1.06L2.44 3.5 1.22 2.28a.75.75 0 010-1.06zM7.5 3.5a.75.75 0 01.75-.75h2.25a2.75 2.75 0 012.75 2.75v4.614a2.501 2.501 0 11-1.5 0V5.5c0-.69-.56-1.25-1.25-1.25H8.25a.75.75 0 01-.75-.75zm5 10a1 1 0 100-2 1 1 0 000 2zm-8-1a1 1 0 11-2 0 1 1 0 012 0zm1.5 0a2.5 2.5 0 11-3.25-2.386V7.75a.75.75 0 011.5 0v2.364A2.501 2.501 0 016 12.5z",
	"GitLabMergeRequestClosedIcon",
);

/** Merged merge request — `merge.svg` */
export const GitLabMergeIcon = createGitLabIcon(
	"M5.5 3.5a1 1 0 11-2 0 1 1 0 012 0zm-.044 2.31a2.5 2.5 0 10-1.706.076v4.228a2.501 2.501 0 101.5 0V8.373a5.735 5.735 0 003.86 1.864 2.501 2.501 0 10.01-1.504 4.254 4.254 0 01-3.664-2.922zM11.5 10.5a1 1 0 100-2 1 1 0 000 2zm-6 2a1 1 0 11-2 0 1 1 0 012 0z",
	"GitLabMergeIcon",
);

/**
 * Draft merge request — uses the same icon as open (`merge-request.svg`)
 * because GitLab does not have a dedicated draft MR icon.
 */
export const GitLabMergeRequestDraftIcon = GitLabMergeRequestIcon;
