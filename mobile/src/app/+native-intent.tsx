/**
 * The sign-in callback belongs to the authentication sheet that is waiting for it, not to navigation.
 * On Android it also arrives as an ordinary deep link; routing it anywhere but home would open an
 * empty screen over the app.
 */
export function redirectSystemPath({ path }: { path: string; initial: boolean }): string {
	if (path.includes("/auth/callback")) {
		return "/";
	}
	return path;
}
