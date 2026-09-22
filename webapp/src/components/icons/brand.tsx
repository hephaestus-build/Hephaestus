import type { ReactElement, SVGProps } from "react";

// lucide-react v1 dropped brand glyphs (GitHub, GitLab, ...) for licensing reasons.
// These thin wrappers mimic the Lucide icon API (size/className/currentColor) so
// callsites that previously imported `GithubIcon`/`GitlabIcon` from lucide-react
// can drop-in replace by switching the import path.

type BrandIconProps = SVGProps<SVGSVGElement> & { size?: number | string };

/**
 * A plain function component. Lucide's icons are `ForwardRefExoticComponent`s, so a slot that takes
 * either has to name both types rather than reusing Lucide's.
 */
export interface BrandIcon {
	(props: BrandIconProps): ReactElement;
	displayName?: string;
}

const make = (displayName: string, viewBox: string, path: string): BrandIcon => {
	function Icon({ size, width, height, ...props }: BrandIconProps): ReactElement {
		const label = props["aria-label"] ?? displayName;
		return (
			<svg
				xmlns="http://www.w3.org/2000/svg"
				viewBox={viewBox}
				width={width ?? size ?? "1em"}
				height={height ?? size ?? "1em"}
				fill="currentColor"
				role="img"
				{...props}
			>
				<title>{label}</title>
				<path d={path} />
			</svg>
		);
	}
	Icon.displayName = displayName;
	return Icon;
};

export const GithubIcon: BrandIcon = make(
	"GithubIcon",
	"0 0 16 16",
	"M8 0c4.42 0 8 3.58 8 8a8.013 8.013 0 0 1-5.45 7.59c-.4.08-.55-.17-.55-.38 0-.27.01-1.13.01-2.2 0-.75-.25-1.23-.54-1.48 1.78-.2 3.65-.88 3.65-3.95 0-.88-.31-1.59-.82-2.15.08-.2.36-1.02-.08-2.12 0 0-.67-.22-2.2.82-.64-.18-1.32-.27-2-.27-.68 0-1.36.09-2 .27-1.53-1.03-2.2-.82-2.2-.82-.44 1.1-.16 1.92-.08 2.12-.51.56-.82 1.28-.82 2.15 0 3.06 1.86 3.75 3.64 3.95-.23.2-.44.55-.51 1.07-.46.21-1.61.55-2.33-.66-.15-.24-.6-.83-1.23-.82-.67.01-.27.38.01.53.34.19.73.9.82 1.13.16.45.68 1.31 2.69.94 0 .67.01 1.3.01 1.49 0 .21-.15.45-.55.38A7.995 7.995 0 0 1 0 8c0-4.42 3.58-8 8-8Z",
);

export const GitlabIcon: BrandIcon = make(
	"GitlabIcon",
	"0 0 24 24",
	"m23.6 9.593-.033-.087L20.3.197a.847.847 0 0 0-1.626.084l-2.198 6.726H7.522L5.324.281A.847.847 0 0 0 3.698.197L.434 9.506.4 9.593a5.882 5.882 0 0 0 1.95 6.798l.011.008.029.022 4.82 3.61 2.388 1.808 1.453 1.099a1.002 1.002 0 0 0 1.214 0l1.453-1.099 2.388-1.808 4.85-3.629.011-.009A5.881 5.881 0 0 0 23.6 9.593Z",
);

export const SlackIcon: BrandIcon = make(
	"SlackIcon",
	"0 0 24 24",
	"M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523A2.528 2.528 0 0 1 0 15.165a2.527 2.527 0 0 1 2.522-2.52h2.52v2.52zm1.271 0a2.527 2.527 0 0 1 2.522-2.52 2.527 2.527 0 0 1 2.52 2.52v6.313A2.528 2.528 0 0 1 8.835 24a2.528 2.528 0 0 1-2.522-2.522v-6.313zM8.835 5.042a2.528 2.528 0 0 1-2.522-2.52A2.528 2.528 0 0 1 8.835 0a2.528 2.528 0 0 1 2.52 2.522v2.52h-2.52zm0 1.271a2.528 2.528 0 0 1 2.52 2.522 2.528 2.528 0 0 1-2.52 2.52H2.522A2.528 2.528 0 0 1 0 8.835a2.528 2.528 0 0 1 2.522-2.522h6.313zm10.123 2.522a2.528 2.528 0 0 1 2.52-2.522A2.528 2.528 0 0 1 24 8.835a2.528 2.528 0 0 1-2.522 2.52h-2.52v-2.52zm-1.271 0a2.528 2.528 0 0 1-2.522 2.52 2.528 2.528 0 0 1-2.52-2.52V2.522A2.528 2.528 0 0 1 15.165 0a2.528 2.528 0 0 1 2.522 2.522v6.313zm-2.522 10.123a2.528 2.528 0 0 1 2.522 2.52A2.528 2.528 0 0 1 15.165 24a2.528 2.528 0 0 1-2.52-2.522v-2.52h2.52zm0-1.271a2.528 2.528 0 0 1-2.52-2.522 2.528 2.528 0 0 1 2.52-2.52h6.313A2.528 2.528 0 0 1 24 15.165a2.528 2.528 0 0 1-2.522 2.522h-6.313z",
);

export const OutlineIcon: BrandIcon = make(
	"OutlineIcon",
	"0 0 24 24",
	"M14.6667 20.2155V20.7163C14.6667 21.4253 14.0697 22 13.3333 22C13.1044 22 12.8792 21.9432 12.6797 21.8351L4.67965 17.5028C4.25982 17.2754 4 16.8478 4 16.384V7.61623C4 7.15248 4.25982 6.72478 4.67965 6.49742L12.6797 2.16508C13.3215 1.81751 14.1344 2.03666 14.4954 2.65456C14.6077 2.8467 14.6667 3.06343 14.6667 3.28388V3.78471L15.6169 3.51027C16.3222 3.30655 17.0655 3.69189 17.2771 4.37093C17.3144 4.49059 17.3333 4.61486 17.3333 4.73979V5.26091L18.5013 5.12036C19.232 5.03242 19.8984 5.53141 19.9897 6.23488C19.9966 6.2877 20 6.34088 20 6.3941V17.6061C20 18.3151 19.403 18.8898 18.6667 18.8898C18.6114 18.8898 18.5561 18.8865 18.5013 18.8799L17.3333 18.7393V19.2604C17.3333 19.9694 16.7364 20.5441 16 20.5441C15.8702 20.5441 15.7412 20.5259 15.6169 20.49L14.6667 20.2155ZM14.6667 18.8753L16 19.2604V4.73979L14.6667 5.12488V18.8753ZM17.3333 6.55456V17.4457L18.6667 17.6061V6.3941L17.3333 6.55456ZM5.33333 7.61623V16.384L13.3333 20.7163V3.28388L5.33333 7.61623ZM6.66667 8.47006L8 7.82823V16.172L6.66667 15.5302V8.47006Z",
);

// `Github` is the old (pre-v1) lucide alias. Re-export so legacy imports keep working.
export { GithubIcon as Github };
