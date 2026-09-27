import type { IconName } from "@/ui/Icon";

/**
 * A practice group's glyph and colour, from the catalog's own keys: `icon` names a lucide icon and
 * `color` a palette key, both chosen on the web (`webapp/src/components/practice-vocabulary/
 * group-visuals.ts`). Each maps to the native symbol and system colour nearest in meaning; a key this
 * build does not know falls back to a folder in grey, as the web falls back to a folder in slate.
 * The glyph is decoration beside the group's name, so it only ever tints a symbol, never a tile.
 */
export interface GroupVisual {
	icon: IconName;
	/** An iOS system colour name, and a Material tone that reads on both light and dark surfaces. */
	tone: { ios: string; android: string };
}

export const ICONS: Record<string, IconName> = {
	BookOpen: { ios: "book", android: "menu_book" },
	BookText: { ios: "text.book.closed", android: "menu_book" },
	Bug: { ios: "ladybug", android: "bug_report" },
	CheckCheck: { ios: "checkmark.circle", android: "done_all" },
	ClipboardCheck: { ios: "checklist", android: "assignment_turned_in" },
	Code: { ios: "chevron.left.forwardslash.chevron.right", android: "code" },
	Code2: { ios: "chevron.left.forwardslash.chevron.right", android: "code" },
	Eye: { ios: "eye", android: "visibility" },
	FileCode: { ios: "doc.text", android: "code_blocks" },
	FileText: { ios: "doc.text", android: "description" },
	FlaskConical: { ios: "testtube.2", android: "science" },
	Folder: { ios: "folder", android: "folder" },
	GitBranch: { ios: "arrow.triangle.branch", android: "call_split" },
	GitMerge: { ios: "arrow.triangle.merge", android: "merge" },
	GitPullRequest: { ios: "arrow.triangle.pull", android: "merge_type" },
	Key: { ios: "key", android: "key" },
	Lightbulb: { ios: "lightbulb", android: "lightbulb" },
	ListChecks: { ios: "checklist", android: "checklist" },
	Lock: { ios: "lock", android: "lock" },
	Mail: { ios: "envelope", android: "mail" },
	MessageCircle: { ios: "bubble.left", android: "chat_bubble" },
	MessageSquare: { ios: "text.bubble", android: "chat" },
	MessageSquareReply: { ios: "arrowshape.turn.up.left", android: "reply" },
	Monitor: { ios: "desktopcomputer", android: "desktop_windows" },
	Package: { ios: "shippingbox", android: "inventory_2" },
	Search: { ios: "magnifyingglass", android: "search" },
	Shield: { ios: "shield", android: "shield" },
	ShieldAlert: { ios: "exclamationmark.shield", android: "gpp_maybe" },
	ShieldCheck: { ios: "checkmark.shield", android: "verified_user" },
	Target: { ios: "target", android: "track_changes" },
	TestTube: { ios: "testtube.2", android: "science" },
	TestTubeDiagonal: { ios: "testtube.2", android: "science" },
	Users: { ios: "person.2", android: "group" },
	Wrench: { ios: "wrench.and.screwdriver", android: "build" },
};

/** The web's palette keys, as iOS system colours (which follow dark mode) and Material tones. */
export const COLORS: Record<string, { ios: string; android: string }> = {
	red: { ios: "systemRed", android: "#D93025" },
	orange: { ios: "systemOrange", android: "#E8710A" },
	amber: { ios: "systemOrange", android: "#E37400" },
	yellow: { ios: "systemYellow", android: "#C69000" },
	lime: { ios: "systemGreen", android: "#5F9E1F" },
	green: { ios: "systemGreen", android: "#1E8E3E" },
	emerald: { ios: "systemMint", android: "#0F9D78" },
	teal: { ios: "systemTeal", android: "#12A4AF" },
	cyan: { ios: "systemCyan", android: "#0A9DC7" },
	sky: { ios: "systemCyan", android: "#1A8FE3" },
	blue: { ios: "systemBlue", android: "#1A73E8" },
	indigo: { ios: "systemIndigo", android: "#5B5FD6" },
	violet: { ios: "systemPurple", android: "#8A4FE0" },
	purple: { ios: "systemPurple", android: "#9334E6" },
	fuchsia: { ios: "systemPink", android: "#C026D3" },
	pink: { ios: "systemPink", android: "#D63384" },
	rose: { ios: "systemPink", android: "#E11D48" },
	slate: { ios: "systemGray", android: "#64748B" },
	gray: { ios: "systemGray", android: "#6B7280" },
	zinc: { ios: "systemGray", android: "#71717A" },
	stone: { ios: "systemBrown", android: "#78716C" },
};

const FALLBACK_ICON = "Folder";
const FALLBACK_COLOR = "slate";

export function groupVisual(icon?: string, color?: string): GroupVisual {
	const glyph = (icon === undefined ? undefined : ICONS[icon]) ?? ICONS[FALLBACK_ICON];
	const tone = (color === undefined ? undefined : COLORS[color]) ?? COLORS[FALLBACK_COLOR];
	if (glyph === undefined || tone === undefined) {
		throw new Error("The fallback group visual must be mapped");
	}
	return { icon: glyph, tone };
}
