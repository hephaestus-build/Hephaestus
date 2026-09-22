---
"hephaestus": patch
---

A new AI provider connection now uses the Responses API unless you clear the checkbox. Practice
reviews and the mentor call tools over many turns, and on the Responses API a reasoning model keeps its
reasoning from one turn to the next instead of starting over after each tool result. OpenAI, the Vercel
AI Gateway, OpenRouter and vLLM serve it. Existing connections keep the API they were created with; to
move one, add a connection with the Responses API and move its models to it.
