// Metro turns an imported image into an asset reference React Native's image components accept.
declare module "*.png" {
	import type { ImageSourcePropType } from "react-native";

	const source: ImageSourcePropType;
	export default source;
}
