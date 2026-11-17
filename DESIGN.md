# Orrery: design

Orrery is a chat agent with one tool, web search, and a memory of *when* things were said. The agent is Java 21 + Spring Boot; the UI (React, TypeScript) and the launcher run on Node, so `npm install` then `npm run dev` starts everything.

## How it works

A turn, in order:

1. **Understand** (code, about 1 ms). `TemporalResolver` turns "last Tuesday", "on 3 November", "since Monday" into calendar days in the user's timezone, as ranked candidates ("Tuesday" may mean this week's or last week's). `IntentAnalyzer` flags recall, re-check, since-then, freshness and small talk. `TopicTerms` keeps the content words.
2. **Recall** (SQLite FTS5, a few ms). For each date, search that day's turns by topic; the first candidate day with a topical match wins, which is how "last Tuesday" picks the right Tuesday. No match on that day: search all days and report the closest date ("mismatch"), or nothing ("none", with the neighbouring days that do have conversations). Load the exact past question and answer, plus the **claim ledger**: facts that answer stated (entity, attribute, value, source) with the date they were true.
3. **Prompt**. Fixed rules first (a stable prefix providers can cache), then today's date and time, then a Memory section with the recalled turns, their facts and a change window (`since="2026-11-10"`).
4. **Agent loop** (at most 5 model steps). One tool: `web_search(query, since, recency, depth)`. When a question is about something that changes, or re-checks the past, the first step must search; parallel tool calls run at once; 8 searches per turn; the last step must answer. Results are numbered per turn and cited as `[n]`; page text is fenced as untrusted data.
5. **Stream**. Server-Sent Events show the recalled date, each search with its date filter, and the answer as it is written. A "let me check" preamble written before a tool call is withdrawn.
6. **Remember** (background job, retried, survives restarts). A fast model files entities, keywords and claims from the answer into the ledger.

## Why

- **Memory is context, not a tool.** Dates are resolved in code, so the model cannot misremember which day "last Tuesday" was, and recall costs no extra model round trip. The model's only tool is web search, as the brief asks.
- **A ledger of dated claims** turns "has anything changed since?" into a comparison of specific values (then, now, status) instead of a vague re-summary.
- **Honesty is designed in.** If there was no conversation that day, Orrery says so and names the closest date; it never invents a past answer.
- **Fast by construction.** Small talk skips memory and tools (one model call). Searches run in parallel on virtual threads over pooled HTTP/2 connections, with a short cache (10 minutes for time-bound queries) and duplicate-request merging. Fact filing happens after the answer. Time to first word is measured and shown.
- **Survives the real world.** Six model providers (OpenAI, Anthropic, Gemini, Groq, OpenRouter, any OpenAI-compatible server) with a fallback chain when a model is retired; three search APIs (Tavily, Brave, Serper) with failover; retries only before the first token, so text is never duplicated; Stop cancels the model and search calls at once.
- **SQLite with FTS5**: one file and nothing to install, ranked full-text search, WAL for concurrent reads.
- **Hexagonal core.** `app.orrery.core` is plain Java with no Spring imports; Spring Boot is a thin adapter (HTTP, SSE, DataSource). 86 JUnit tests drive real HTTP against fake model and search servers and a real SQLite file; `npm run eval` runs the brief's three questions end to end with real keys.

## Considered and rejected

- **Memory as a `search_memory` tool**: an extra round trip on every recall, and the model would be choosing dates.
- **Embeddings and a vector database**: another key and another store; FTS5 plus model-written keywords and synonyms covers the brief's questions.
- **Provider-native web search** (OpenAI, Gemini grounding): ties the agent to one vendor and gives no control over date filters or citations.
- **LangChain4j / Spring AI**: they abstract exactly what needed control: streamed tool calls, Gemini thought signatures, retry rules.
- **Summarising old conversations**: lossy. Orrery keeps exact turns and extracts facts.
- **A separate planning call**: more latency; "search first when it matters" covers it.
- **Postgres in Docker**: heavier setup for whoever runs it.

## With another week

- A larger eval set with a model as judge, run on every change.
- Hybrid recall: embeddings alongside FTS5, for paraphrased questions.
- Watchlists: re-check tracked facts on a schedule and send a "what changed" digest.
- Source quality scoring (primary sources over SEO pages).
- Accounts and Postgres for several devices; MockMvc contract tests for the Spring layer.
- Speculative search: start the obvious search while the model is still planning.
