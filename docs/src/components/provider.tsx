import { navigate } from "astro:transitions/client";
import { Banner } from "fumadocs-ui/components/banner";
import { RootProvider } from "fumadocs-ui/provider/astro";
import { lazy, type ReactNode } from "react";

import { useThemeCookie } from "@/components/theme-cookie";
import { BASE, SITE_URL } from "@/lib/site";
import { channel } from "@/lib/version";

const base = BASE;
// The dialog and its Pagefind client load when a reader opens search.
const SearchDialog = lazy(async () => import("@/components/search"));

export interface ProviderProps {
	/** `Astro.url.pathname` */
	pathname: string;
	/** `Astro.params` */
	params: Record<string, string | undefined>;
	children?: ReactNode;
}

// The banner text wraps on a phone, so its height follows the text, and the side padding keeps it clear of the close button.
const BANNER = "min-h-12 px-10 py-2";

function Announcement() {
	return channel === "next" ? (
		<Banner changeLayout={false} height="auto" className={BANNER}>
			<span>
				<strong>Unreleased:</strong> these docs describe the next release.{" "}
				<a className="underline" href={`${SITE_URL}/`}>
					Read the docs of the latest release
				</a>
				.
			</span>
		</Banner>
	) : (
		<Banner id="pre-1-0" changeLayout={false} height="auto" className={BANNER}>
			<span>
				Hephaestus is <strong>pre-1.0</strong>: only the latest release is supported, and a minor
				release can require action.{" "}
				<a className="underline" href={`${base}admin/compatibility-policy`}>
					Read the compatibility policy
				</a>
				.
			</span>
		</Banner>
	);
}

function ThemeCookie(): ReactNode {
	useThemeCookie();
	return null;
}

export function Provider({ pathname, params, children }: ProviderProps) {
	return (
		<RootProvider pathname={pathname} params={params} navigate={navigate} search={{ SearchDialog }}>
			<ThemeCookie />
			<Announcement />
			{children}
		</RootProvider>
	);
}
