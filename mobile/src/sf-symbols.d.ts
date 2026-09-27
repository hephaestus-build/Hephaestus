// The native project is generated for iOS 16.4 (Expo SDK 57's deployment target), and a symbol from a
// later SF Symbols release draws nothing there. This narrows every `SFSymbol`-typed name — expo-symbols'
// `SymbolView`, which `Icon` wraps, and expo-router's tab icons and toolbar items — to SF Symbols 4.2,
// the release iOS 16.4 ships, so the type checker rejects a newer one. Raise it with the target.
export type SupportedSFSymbolsVersion = "4.2";

declare module "sf-symbols-typescript" {
	interface Overrides {
		SFSymbolsVersion: SupportedSFSymbolsVersion;
	}
}
