---
"hephaestus": minor
---

Instance and workspace admins can connect Anthropic through its Messages API. Reviews, practice scripts, feedback composition, and Heph use the native protocol. Reviews reuse stable prompt layers with a five-minute cache lifetime. Cache reads and writes appear in usage with Anthropic's separate token counts.

Model tool choice follows a capability test instead of the model name. Untested models choose when to call tools. In each admin console, edit a saved OpenAI-protocol chat model and select **Test tool choice** to test its endpoint. The test sends two short requests that the provider may charge for. An inconclusive test keeps the previous result. Anthropic sessions always let the model choose, so a repair keeps the same cache prefix.

Native feedback composition uses the provider’s thinking and sampling defaults. The review runner uses its SDK’s native model metadata. An unrecognized native model alias also keeps the provider defaults for those options.
