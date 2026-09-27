import type { SVGProps } from "react";
/**
 * React wrapper components for GitLab SVG icons from `@gitlab/svgs`.
 *
 * Path data is copied from the installed `@gitlab/svgs` dist so we avoid
 * adding an SVGR build plugin for just a handful of icons. Most source SVGs
 * use `viewBox="0 0 16 16"` and a single `<path>` element; a status icon drawn
 * on GitLab's 14px grid passes its own box, and its paths are joined into one,
 * which `evenodd` fills the same way.
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

function createGitLabIcon(pathData: string, displayName: string, box = 16) {
	function Icon({ size = 16, ...rest }: GitLabIconProps) {
		const labelled = hasText(rest["aria-label"]) || hasText(rest["aria-labelledby"]);
		return (
			<svg
				width={size}
				height={size}
				viewBox={`0 0 ${box} ${box}`}
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

/** Open issue — `issue-open-m.svg` */
export const GitLabIssueOpenIcon = createGitLabIcon(
	"M8 14.5a6.5 6.5 0 100-13 6.5 6.5 0 000 13zM8 16A8 8 0 108 0a8 8 0 000 16z",
	"GitLabIssueOpenIcon",
);

/** Closed issue — `issue-close.svg` */
export const GitLabIssueClosedIcon = createGitLabIcon(
	"M14.5 8a6.5 6.5 0 11-13 0 6.5 6.5 0 0113 0zM16 8A8 8 0 110 8a8 8 0 0116 0zM3.75 7.25a.75.75 0 000 1.5h8.5a.75.75 0 000-1.5h-8.5z",
	"GitLabIssueClosedIcon",
);

/** Approved — `check-circle.svg` */
export const GitLabCheckCircleIcon = createGitLabIcon(
	"M14.5 8a6.5 6.5 0 11-13 0 6.5 6.5 0 0113 0zM16 8A8 8 0 110 8a8 8 0 0116 0zm-4.22-1.72a.75.75 0 00-1.06-1.06L6.75 9.19 5.53 7.97a.75.75 0 00-1.06 1.06l1.75 1.75a.75.75 0 001.06 0l4.5-4.5z",
	"GitLabCheckCircleIcon",
);

/** Changes requested — `error.svg` */
export const GitLabErrorIcon = createGitLabIcon(
	"M8 14.5a6.5 6.5 0 100-13 6.5 6.5 0 000 13zM8 16A8 8 0 108 0a8 8 0 000 16zm1-5a1 1 0 11-2 0 1 1 0 012 0zm-.25-6.25a.75.75 0 00-1.5 0v3.5a.75.75 0 001.5 0v-3.5z",
	"GitLabErrorIcon",
);

/** Comment — `comment.svg` */
export const GitLabCommentIcon = createGitLabIcon(
	"M0 4a3 3 0 013-3h10a3 3 0 013 3v6a3 3 0 01-3 3H4.063L1.28 15.78A.75.75 0 010 15.25V4zm3-1.5A1.5 1.5 0 001.5 4v9.44l1.723-1.72.22-.22H13a1.5 1.5 0 001.5-1.5V4A1.5 1.5 0 0013 2.5H3z",
	"GitLabCommentIcon",
);

/** Comments — `comments.svg` */
export const GitLabCommentsIcon = createGitLabIcon(
	"M2 0a2 2 0 00-2 2v10.06l1.28-1.28 1.53-1.53H4V11a2 2 0 002 2h7l1.5 1.5L16 16V6a2 2 0 00-2-2h-2V2a2 2 0 00-2-2H2zm8.5 4V2a.5.5 0 00-.5-.5H2a.5.5 0 00-.5.5v6.44l.47-.47.22-.22H4V6a2 2 0 012-2h4.5zm3.56 7.94l.44.439V6a.5.5 0 00-.5-.5H6a.5.5 0 00-.5.5v5a.5.5 0 00.5.5h7.621l.44.44z",
	"GitLabCommentsIcon",
);

/** Code — `code.svg` */
export const GitLabCodeIcon = createGitLabIcon(
	"M9.424 2.023a.75.75 0 01.556.904L7.48 13.42a.75.75 0 01-1.46-.348L8.52 2.58a.75.75 0 01.904-.556zM11.16 4.22a.75.75 0 011.06 0l3.25 3.25L16 8l-.53.53-3.25 3.25a.75.75 0 11-1.06-1.06L13.88 8l-2.72-2.72a.75.75 0 010-1.06zM4.84 5.28a.75.75 0 10-1.06-1.06L.53 7.47 0 8l.53.53 3.25 3.25a.75.75 0 001.06-1.06L2.12 8l2.72-2.72z",
	"GitLabCodeIcon",
);

/** Review — `eye.svg` */
export const GitLabEyeIcon = createGitLabIcon(
	"M0 8s3-6 8-6 8 6 8 6-3 6-8 6-8-6-8-6zm1.81.13A13.593 13.593 0 011.73 8l.082-.13c.326-.51.806-1.187 1.42-1.856C4.494 4.635 6.12 3.5 8 3.5c1.878 0 3.506 1.135 4.77 2.514A13.705 13.705 0 0114.27 8a14.021 14.021 0 01-1.502 1.986C11.506 11.365 9.88 12.5 8 12.5c-1.878 0-3.506-1.135-4.77-2.514A13.703 13.703 0 011.81 8.13zM11 8a3 3 0 11-2.117-2.868 1.5 1.5 0 101.985 1.985A3 3 0 0111 8z",
	"GitLabEyeIcon",
);

/** Review requested — `review-list.svg` */
export const GitLabReviewListIcon = createGitLabIcon(
	"M9 2.5a1 1 0 11-2 0 1 1 0 012 0zm1.45-.5a2.5 2.5 0 00-4.9 0H3a1 1 0 00-1 1v12a1 1 0 001 1h10a1 1 0 001-1V3a1 1 0 00-1-1h-2.55zM8 5H5.5V3.5h-2v11h9v-11h-2V5H8zM5 7.75A.75.75 0 015.75 7h4.5a.75.75 0 010 1.5h-4.5A.75.75 0 015 7.75zm.75 1.75a.75.75 0 000 1.5h4.5a.75.75 0 000-1.5h-4.5z",
	"GitLabReviewListIcon",
);

/** Returned for changes — `review-warning.svg` */
export const GitLabReviewWarningIcon = createGitLabIcon(
	"M9 2.5a1 1 0 11-2 0 1 1 0 012 0zm1.45-.5a2.5 2.5 0 00-4.9 0H3a1 1 0 00-1 1v12a1 1 0 001 1h10a1 1 0 001-1V3a1 1 0 00-1-1h-2.55zM8 5H5.5V3.5h-2v11h9v-11h-2V5H8zm1 7a1 1 0 11-2 0 1 1 0 012 0zm-.25-4.75a.75.75 0 00-1.5 0v2a.75.75 0 001.5 0v-2z",
	"GitLabReviewWarningIcon",
);

/** Approved review — `review-checkmark.svg` */
export const GitLabReviewCheckmarkIcon = createGitLabIcon(
	"M8 3.5a1 1 0 100-2 1 1 0 000 2zM8 0a2.5 2.5 0 012.45 2H13a1 1 0 011 1v12a1 1 0 01-1 1H3a1 1 0 01-1-1V3a1 1 0 011-1h2.55A2.5 2.5 0 018 0zM7 5h3.5V3.5h2v11h-9v-11h2V5H7zm3.53 3.28a.75.75 0 10-1.06-1.06L7.5 9.19l-.47-.47a.75.75 0 00-1.06 1.06l1 1a.75.75 0 001.06 0l2.5-2.5z",
	"GitLabReviewCheckmarkIcon",
);

/** Waiting — `hourglass.svg` */
export const GitLabHourglassIcon = createGitLabIcon(
	"M2.75 0a.75.75 0 000 1.5H3v.593c0 1.26.5 2.468 1.391 3.359L6.94 8l-2.548 2.548A4.75 4.75 0 003 13.907v.593h-.25a.75.75 0 000 1.5h10.5a.75.75 0 000-1.5H13v-.593c0-1.26-.5-2.468-1.391-3.359L9.06 8l2.548-2.548A4.75 4.75 0 0013 2.093V1.5h.25a.75.75 0 000-1.5H2.75zm8.75 1.5h-7v.593c0 .69.219 1.356.618 1.907h5.764a3.25 3.25 0 00.618-1.907V1.5zM8 6.94L6.56 5.5h2.88L8 6.94zm3.5 7.56v-.593a3.25 3.25 0 00-.952-2.298L8 9.06l-2.548 2.548a3.25 3.25 0 00-.952 2.298v.593h7z",
	"GitLabHourglassIcon",
);

/** Users — `users.svg` */
export const GitLabUsersIcon = createGitLabIcon(
	"M6.5 4a1.5 1.5 0 11-3 0 1.5 1.5 0 013 0zm.63 2.113a3 3 0 10-4.259 0A3.997 3.997 0 001 9.5V13a2 2 0 002 2h4c.597 0 1.134-.262 1.5-.677.366.415.903.677 1.5.677h3a2 2 0 002-2v-2c0-1.218-.622-2.29-1.565-2.917a2.5 2.5 0 10-3.87 0c-.241.16-.462.35-.656.564a4.005 4.005 0 00-1.78-2.534zM5 7a2.5 2.5 0 00-2.5 2.5V13a.5.5 0 00.5.5h4a.5.5 0 00.5-.5V9.5A2.5 2.5 0 005 7zm7.5-.5a1 1 0 11-2 0 1 1 0 012 0zm-1 2.5a2 2 0 00-2 2v2a.5.5 0 00.5.5h3a.5.5 0 00.5-.5v-2a2 2 0 00-2-2z",
	"GitLabUsersIcon",
);

/** Clock — `clock.svg` */
export const GitLabClockIcon = createGitLabIcon(
	"M14.5 8a6.5 6.5 0 11-13 0 6.5 6.5 0 0113 0zM16 8A8 8 0 110 8a8 8 0 0116 0zM8.75 3.75a.75.75 0 00-1.5 0v4.56l.22.22 2.254 2.254a.75.75 0 101.06-1.06L8.75 7.689V3.75z",
	"GitLabClockIcon",
);

/** Done — `check.svg` */
export const GitLabCheckIcon = createGitLabIcon(
	"M12.78 4.62a.75.75 0 010 1.06l-6.097 6.097a.75.75 0 01-1.069-.009L3.211 9.284a.75.75 0 111.078-1.043l1.873 1.936L11.72 4.62a.75.75 0 011.06 0z",
	"GitLabCheckIcon",
);

/** Failed pipeline — `status_failed.svg`, on GitLab's 14px status grid */
export const GitLabStatusFailedIcon = createGitLabIcon(
	"M7 0a7 7 0 110 14A7 7 0 017 0zm0 1a6 6 0 100 12A6 6 0 007 1zM7 5.969L5.599 4.568a.29.29 0 00-.413.004l-.614.614a.294.294 0 00-.004.413L5.968 7l-1.4 1.401a.29.29 0 00.004.413l.614.614c.113.114.3.117.413.004L7 8.032l1.401 1.4a.29.29 0 00.413-.004l.614-.614a.294.294 0 00.004-.413L8.032 7l1.4-1.401a.29.29 0 00-.004-.413l-.614-.614a.294.294 0 00-.413-.004L7 5.968z",
	"GitLabStatusFailedIcon",
	14,
);
