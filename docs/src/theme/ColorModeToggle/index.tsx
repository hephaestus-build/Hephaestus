import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import ColorModeToggle from "@theme-original/ColorModeToggle";
import type { Props } from "@theme/ColorModeToggle";
import type { ReactNode } from "react";

const ONE_YEAR_SECONDS = 365 * 24 * 60 * 60;

/*
 * Writes the reader's choice to the `hephaestus-theme` cookie that the web app reads too (ADR 0053,
 * amendment of 2026-10-26), so a toggle here carries over to every Hephaestus host. The published
 * site shares it with its parent domain; a preview or a local build keeps it to its own host.
 */
function writeChoice(choice: "light" | "dark" | "system", siteUrl: string) {
	const { hostname, protocol } = window.location;
	const shared =
		hostname === new URL(siteUrl).hostname ? hostname.split(".").slice(1).join(".") : "";
	// oxlint-disable-next-line unicorn/no-document-cookie -- the head script must read it synchronously before paint; the async Cookie Store cannot serve that read.
	document.cookie = [
		`hephaestus-theme=${choice}`,
		"Path=/",
		`Max-Age=${ONE_YEAR_SECONDS}`,
		"SameSite=Lax",
		...(shared === "" ? [] : [`Domain=${shared}`]),
		...(protocol === "https:" ? ["Secure"] : []),
	].join("; ");
}

export default function ColorModeToggleWrapper(props: Props): ReactNode {
	const { siteConfig } = useDocusaurusContext();
	return (
		<ColorModeToggle
			{...props}
			onChange={(colorMode: Props["value"]) => {
				writeChoice(colorMode ?? "system", siteConfig.url);
				props.onChange(colorMode);
			}}
		/>
	);
}
