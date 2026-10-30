import { createContext, useContext, useEffect, useState } from "react";

import environment from "@/environment";

import { readThemeCookie, type Theme, themeCookies } from "./theme-cookie";

function writeThemeCookie(theme: Theme) {
	const scope = {
		hostname: window.location.hostname,
		secure: window.location.protocol === "https:",
		workspaceSubdomains: environment.workspaceSubdomains,
	};
	for (const cookie of themeCookies(theme, scope)) {
		// oxlint-disable-next-line unicorn/no-document-cookie -- `theme-init.js` must read it synchronously before paint; the async Cookie Store cannot serve that read.
		document.cookie = cookie;
	}
}

interface ThemeProviderProps {
	children?: React.ReactNode;
	defaultTheme?: Theme;
	/** Off where the theme is chosen around the app, as in Storybook: no cookie is read or written. */
	persistent?: boolean;
}

interface ThemeProviderState {
	theme: Theme;
	setTheme: (theme: Theme) => void;
}

/**
 * A working default rather than a sentinel `useTheme` would throw on: a theme-aware component has to
 * render on its own in a test or a story, with no shell around it.
 */
const NO_PROVIDER: ThemeProviderState = {
	theme: "system",
	setTheme: () => {
		/* nothing above to update; the theme stays "system" */
	},
};

const ThemeProviderContext = createContext<ThemeProviderState>(NO_PROVIDER);

export function ThemeProvider({
	children,
	defaultTheme = "system",
	persistent = true,
	...props
}: ThemeProviderProps) {
	const [theme, setTheme] = useState<Theme>(() => {
		if (!persistent) {
			return defaultTheme;
		}
		return readThemeCookie(document.cookie) ?? defaultTheme;
	});

	useEffect(() => {
		const root = window.document.documentElement;

		root.classList.remove("light", "dark");

		let appliedTheme: "light" | "dark";
		if (theme === "system") {
			appliedTheme = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
		} else {
			appliedTheme = theme;
		}

		root.classList.add(appliedTheme);
		root.dataset.colorMode = appliedTheme;

		const metaThemeColor = document.querySelector('meta[name="theme-color"]');
		if (metaThemeColor) {
			const color = appliedTheme === "dark" ? "hsl(0 0% 10%)" : "hsl(0 0% 100%)";
			metaThemeColor.setAttribute("content", color);
		}
	}, [theme]);

	const contextValue: ThemeProviderState = {
		theme,
		setTheme: (next: Theme) => {
			if (persistent) {
				writeThemeCookie(next);
			}
			setTheme(next);
		},
	};

	return (
		<ThemeProviderContext.Provider {...props} value={contextValue}>
			{children}
		</ThemeProviderContext.Provider>
	);
}

export const useTheme = () => useContext(ThemeProviderContext);
