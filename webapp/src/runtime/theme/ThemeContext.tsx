import { createContext, useContext, useEffect, useState } from "react";

const THEMES = ["dark", "light", "system"] as const;

type Theme = (typeof THEMES)[number];

/** The stored theme is user-writable, so anything unrecognised falls back to the default. */
function isTheme(value: string | null): value is Theme {
	return THEMES.some((theme) => theme === value);
}

interface ThemeProviderProps {
	children?: React.ReactNode;
	defaultTheme?: Theme;
	storageKey?: string;
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
	setTheme: () => {},
};

const ThemeProviderContext = createContext<ThemeProviderState>(NO_PROVIDER);

export function ThemeProvider({
	children,
	defaultTheme = "system",
	storageKey = "theme",
	...props
}: ThemeProviderProps) {
	const [theme, setTheme] = useState<Theme>(() => {
		const stored = localStorage.getItem(storageKey);
		return isTheme(stored) ? stored : defaultTheme;
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
			localStorage.setItem(storageKey, next);
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
