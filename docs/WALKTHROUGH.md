# Orrery, explained in plain words

This is the guide to read before you present. Part 1 is the idea, part 2 follows one question through the system, part 3 tours the code, part 4 explains the big decisions, and part 5 lists the questions people usually ask, with answers.

---

## 1. The idea in one minute

Most chat assistants either know nothing current (they were trained months ago) or search the web but forget everything you said before. Orrery does both: it searches the web for anything that can change, and it remembers each conversation **with its date**.

That combination answers a kind of question others cannot:

> "Last Tuesday I asked you about Postgres hosting. Has any pricing changed since then?"

To answer, Orrery has to (1) work out which day "last Tuesday" was, (2) find what it said that day, (3) check the web for anything new since that day, and (4) compare. That is the whole product.

The name: an orrery is a mechanical model of planets moving around the sun. Time and position, kept in order. The 3D hologram on the home screen is one, and each brass planet on its outer ring is a day Orrery remembers.

---

## 2. What happens when you press Enter

Take the question above, asked on Saturday 14 November in India.

**Step 1: understand the words (no AI, about 1 millisecond).**
`TemporalResolver.java` reads "Last Tuesday" and, using your timezone (sent by the browser), produces a calendar day: Tuesday 10 November. If a phrase could mean two days, it keeps both, best guess first. `IntentAnalyzer.java` notices this is a recall question ("I asked you") and a change question ("since then"). `TopicTerms.java` keeps the words that matter: postgres, hosting, pricing.

**Step 2: remember (database search, a few milliseconds).**
`MemoryRecall.java` searches the conversations from 10 November for those words, using SQLite's full-text search. It finds the question you asked at 18:40 and the answer given then. It also loads the **facts** stored from that answer, for example "Neon, Launch plan price: $19/month, true on 10 Nov, source neon.com". If nothing matched on that day, it would look at all days and say "nothing on Tuesday, but you asked about this on Monday".

**Step 3: brief the model.**
`PromptBuilder.java` writes the instructions: fixed rules first, then today's date and time, then a Memory section with the exact past question, answer and facts, and the instruction to search for changes `since="2026-11-10"`.

**Step 4: search and answer (the agent loop).**
`TurnOrchestrator.java` sends that to the model with one tool, `web_search`. Because this question is about change, the first step **must** search. The model asks for several searches at once (Neon pricing, Supabase pricing, ...) and they run in parallel. Results come back numbered [1], [2], [3]. The model then writes the answer, citing those numbers. At most 5 rounds and 8 searches, so it can never loop forever.

**Step 5: stream it to the screen.**
While this happens, the backend sends small events to the browser (Server-Sent Events): "found Tuesday 10 Nov", "searching Neon pricing since 10 Nov", "6 results", then the answer word by word. That is why you watch it work instead of staring at a spinner.

**Step 6: file the new facts (after you already have the answer).**
A background job asks a smaller, faster model to pull the facts out of the new answer and store them with today's date. Next week, "has anything changed since Saturday?" can be answered the same way.

---

## 3. Tour of the code

### Backend: `backend/src/main/java/app/orrery/`

The backend has three layers. `core` is the agent and has **no Spring code at all**. `infra` stores data. `web` is the thin Spring Boot layer that turns HTTP requests into calls on `core`.

**`core/understand/`: reading the question**
- `TemporalResolver.java`: turns date phrases into days. Handles "yesterday", "last Tuesday", "on 3 November", "3/11" (day first or month first depending on your locale), "2 weeks ago", "in October", "since Monday", "the other day".
- `IntentAnalyzer.java`: spots recall questions, re-checks, "since then", freshness words ("latest", "now"), and small talk ("thanks!") so small talk skips everything.
- `TopicTerms.java`: the important words for searching memory.

**`core/memory/`: remembering**
- `MemoryRecall.java`: finds the right past conversations. Tries the most likely day first; reports "found", "mismatch" (found on a different day) or "none".
- `MemoryRenderer.java`: writes the Memory section the model reads.

**`core/agent/`: the agent**
- `TurnOrchestrator.java`: one turn from start to finish: recall, prompt, model steps, searches, streaming, saving.
- `PromptBuilder.java`: the instructions. Rules first (they never change, so providers can cache them), date and memory last.
- `WebSearchTool.java`: the single tool. Takes a query and optional `since`, `recency`, `depth`.
- `SourceRegistry.java`: numbers sources [1], [2]... across all searches in a turn.
- `ConversationWindow.java`: chooses how much of the current conversation to send, within a size budget.
- `MemoryExtractor.java`: the background fact filing.
- `TitleWriter.java`: names new conversations.
- `DemoSeeder.java`: "Load sample history" for demos.
- `ConversationLocks.java`: one answer at a time per conversation.

**`core/llm/`: talking to models**
- `OpenAiCompatibleClient.java`: OpenAI, Gemini, Groq, OpenRouter and local servers share one streaming protocol. Handles each provider's quirks (for example Gemini's "thought signatures" that must be sent back).
- `AnthropicClient.java`: Claude's own protocol.
- `ModelRouter.java`: picks the model, retries on temporary errors (only before any text was sent, so text never repeats), and falls back to another model if one has been retired.
- `SseReader.java`: reads streamed responses and stops instantly when you press Stop.

**`core/search/`: reading the web**
- `TavilyProvider.java`, `BraveProvider.java`, `SerperProvider.java`: three search APIs, same shape.
- `SearchService.java`: tries them in order (failover), caches results, merges duplicate requests, removes duplicate pages, and reads full pages for "deep" searches.
- `PageReader.java` + `UrlGuard.java`: fetch a web page safely. The guard blocks addresses inside your network (a classic attack is tricking a server into fetching `http://192.168.1.1/admin`).
- `HtmlText.java`: turns HTML into readable text.

**`core/store/`**: `Store.java` is the list of everything the agent needs from a database, as an interface. `core` only knows this interface, never SQLite.

**`core/jobs/JobRunner.java`**: background jobs stored in the database, so they survive a restart and are retried with growing delays.

**Small helpers**: `json/` (a small JSON reader and writer, so `core` needs no libraries), `util/` (IDs, text, cache, rate limiter, cancellation, citations), `time/` (a clock that the demo can move), `config/` (reads `.env` and picks providers), `events/` (the events streamed to the browser).

**`infra/`: the database**
- `Migrations.java`: creates the tables, including the full-text index.
- `SqliteStore.java`: every query, in plain SQL.

**`web/`: the Spring Boot layer**
- `ChatController.java`: `POST /api/chat`, streams the turn.
- `ConversationController.java`, `MemoryController.java`, `SystemController.java`: the other endpoints.
- `UserIdentityFilter.java`: gives each browser an ID in a cookie, so each browser has its own memory without accounts.
- `SseEventSink.java`: writes events to the stream, with a keep-alive ping every 15 seconds.
- `AppWiring.java`: the one place where Spring creates the agent.
- `ApiErrors.java`: every error has the same JSON shape.

**Tests: `backend/src/test/java/`**: 86 tests. They start small fake servers that speak the real model and search protocols, so the actual network code is tested, and use a real SQLite file.

### Frontend: `web/src/`

- `App.tsx`: the layout and which screen to show.
- `state/store.ts`: all app state. Keeps each conversation's answers separately, so an answer keeps arriving if you switch away.
- `lib/api.ts` and `lib/sse.ts`: talking to the backend, and reading the live event stream.
- `lib/dates.ts`: "Today", "Yesterday", "Tuesday, November 10".
- `lib/citations.ts` and `lib/statusColumns.ts`: turning [1] into source chips, and "Unchanged / Changed" into coloured labels.
- `components/`: the screens. `ChatView`, `TurnView` (one question and answer), `Steps` (what it did: memory, searches), `Markdown` (the answer), `Sources`, `Composer` (the message box), `Sidebar`, `MemoryView` (timeline and ledger), `SetupScreen` (missing keys), `DemoTools` (demo clock, sample history).
- `hologram/`: the 3D orrery in three.js. It loads after the page, so it never slows the first view, and shows a still drawing if the computer cannot do 3D or prefers reduced motion.

### Scripts: `scripts/`

- `dev.mjs`: `npm run dev`. Creates `.env`, finds or downloads Java and Maven, builds the backend when its code changed, starts it, starts the website, and restarts the backend when `.env` is saved.
- `build.mjs`, `start.mjs`: production build into one jar, and running it.
- `test.mjs`: all tests. `eval.mjs`: the real-key scorecard.
- `lib/toolchain.mjs`: the Java and Maven finding and downloading, with checksum checks.

---

## 4. The big decisions, simply

**Why is memory not a tool the model calls?**
Two reasons: speed and accuracy. If the model had to call a "search memory" tool, every recall would cost an extra round trip to the model (a second or more). And the model would decide which day "last Tuesday" was, which models get wrong surprisingly often. Here, code works out the date and looks it up before the model even starts. The model gets the answer handed to it, and its only tool is web search, as the brief says.

**Why store "facts" separately from the conversation?**
"Has the price changed?" needs the old price. Re-reading a long old answer and guessing is unreliable. A small table of facts (Neon, Launch plan, $19/month, true on 10 Nov, from neon.com) makes the comparison exact. The Then / Now / Status table comes from it.

**Why SQLite and not Postgres or MongoDB?**
Nothing to install: it is one file in `data/`. It has full-text search built in (FTS5) with relevance ranking, which is what recall needs. For one user's history it is more than fast enough. Moving to Postgres later only means writing a second implementation of `Store.java`.

**Why Java and Spring Boot when the brief mentions Node?**
The agent is Java 21 + Spring Boot by choice: strong typing for a system with many moving parts, and virtual threads, which let every request and every parallel search run on its own lightweight thread. The website and the launcher are Node and TypeScript, so the required `npm install` then `npm run dev` still starts everything. The launcher even downloads Java if the computer lacks it.

**Why keep Spring out of `core`?**
So the agent can be read, tested and reused without the framework. Spring Boot does what it is best at (HTTP, streaming, connection pooling), in one thin layer.

**Why Server-Sent Events and not WebSockets?**
The answer flows one way, server to browser. SSE is plain HTTP, works through proxies, and needs no extra protocol. Stop is a separate small request.

**Why not LangChain4j or Spring AI?**
They hide exactly the parts that needed control: streaming tool calls from six providers, each with quirks, retries that never duplicate text, instant cancellation. The clients here are a few hundred lines each and fully tested.

**What makes it fast?**
Small talk skips memory and tools. Memory lookup takes milliseconds and needs no model call. Several searches run at the same time. Connections to the model and search APIs stay open between requests (HTTP/2). Recent searches are cached. Filing facts happens after you already have your answer. The time to the first word is measured and shown under each answer.

**What stops it from making things up?**
It cites a numbered source for every fact from the web, and can only cite sources from this turn. If memory has nothing for a date, the prompt says so explicitly and the rules forbid guessing. Web pages are marked as data, so text on a page cannot give it orders.

---

## 5. Questions you will probably be asked

**Q: How does it know what "last Tuesday" means?**
It does not ask the AI. Code converts the phrase to a date using today's date in your timezone. When it is ambiguous (on a Wednesday, "Tuesday" could mean yesterday or a week ago), it checks both and picks the one where you actually discussed that topic.

**Q: What if I ask about a day when we did not talk?**
It says there is no conversation that day, and if the topic came up on another day, it names that day. It never invents a past conversation. `npm run eval` checks this.

**Q: How does it decide when to search the web?**
The model decides, guided by rules: search for anything that changes (prices, versions, news, "latest"), not for timeless facts. For questions about change or about the past, the first step is forced to search.

**Q: How do you know an answer is current?**
Every web fact carries a citation to a page fetched in this turn, and pages show their publish date. Change questions use a date filter so only news after the given day comes back.

**Q: What happens if the model provider is down or a model is retired?**
Temporary errors are retried (only before any text is sent). A retired model falls back to the next one on a list, and Orrery remembers which one worked. If a search API fails, the next one takes over.

**Q: Where is the data stored? Is it private?**
In `data/orrery.db` on the machine running it. Each browser has its own memory, identified by a random ID in a cookie. "Forget everything" deletes it. Keys stay in `.env`, which is never uploaded.

**Q: How did you test it?**
86 backend tests, including whole conversations run against fake model and search servers over real network connections, and recall tests for each of the brief's three questions. Frontend unit tests cover the stream reader, citations and dates. `npm run eval` runs the real thing with real keys and scores it.

**Q: Why does the first `npm run dev` take a few minutes?**
It downloads Maven and Spring Boot's libraries and compiles the backend once. After that, it starts in about 20 seconds, and only rebuilds when Java code changes.

**Q: Can it work with a different AI model?**
Yes. Set a different key in `.env`, or `LLM_MODEL` to choose a model, or `LLM_PROVIDER=openai-compatible` with `LLM_BASE_URL` to use a model running on your own computer (for example Ollama).

**Q: What would you build next?**
See the last section of DESIGN.md: a bigger automatic test set graded by a model, semantic (embedding) search alongside full-text search, scheduled re-checks of facts you care about, and user accounts.

**Q: What was the hardest part?**
Good candidates: making dates right in every timezone and phrasing; supporting six model providers whose streaming formats differ in small ways; and making Stop instant, which means cancelling a model stream and several searches at once without leaving anything half-saved.

---

## 6. Numbers worth knowing

| What | Value | Where |
|---|---|---|
| Model rounds per answer | at most 5 | `TurnOrchestrator.MAX_STEPS` |
| Searches per answer | 8 (`SEARCH_MAX_PER_TURN`) | `.env` |
| Results kept per search | 6 (`SEARCH_MAX_RESULTS`) | `.env` |
| Full pages read in a deep search | 3 | `SearchService.DEEP_READS` |
| Search cache | 10 min for time-bound queries, 60 min otherwise | `SearchService` |
| Page fetch limits | 2 MB, 8 s, 4 redirects | `PageReader` |
| Past turns recalled at once | 6 | `MemoryRecall.MAX_TURNS` |
| Facts filed per answer | up to 25 | `MemoryExtractor.MAX_CLAIMS` |
| Retries before the first word | 2 | `ModelRouter.MAX_RETRIES` |
| Message length limit | 8,000 characters | `ChatController` |
