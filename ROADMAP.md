# Roadmap — Agentic AI Evolution

This document lays out the plan for evolving the Fitness Microservices project from its current state (one-shot Gemini recommendations) into an agentic AI fitness platform built on Agentic RAG, MCP, LangGraph, multi-agent orchestration, and persistent memory.

It is structured as **(1) problems first, (2) technology mapping, (3) phased build plan**. Tech is chosen because it earns its place against a real problem — not the other way round.

---

## Current state

| Capability                       | Today                                                                   |
|----------------------------------|-------------------------------------------------------------------------|
| Activity logging                 | Frontend POSTs to activity-service, MongoDB persistence                 |
| AI analysis                      | ai-service consumes from Kafka, calls Gemini once, stores JSON response |
| Memory / context across sessions | None — every Gemini call is one-shot                                    |
| Grounding in real science        | None — recommendations are model priors                                 |
| External data sources            | None — only what the user types in                                      |
| Plan adherence                   | No training plan exists yet                                             |

The AI layer is a thin wrapper around a single Gemini prompt. It works as a demo. It does not work as a product.

---

## Part 1 — Real problems worth solving

Each of the following is a documented pain point in mainstream fitness apps (Strava, MyFitnessPal, Whoop, Fitbod, Future). Each is a place where agentic AI is genuinely differentiated, not gimmicky.

### Problem 1 — Static plans that ignore real life

A 12-week marathon plan assumes nothing changes. Users get sick, travel, work late. Manual adjustment doesn't happen — they just quit. Plan adherence is the metric for fitness apps; roughly 80% of users abandon by week four.

### Problem 2 — Recommendations without grounding

A one-shot LLM call produces plausible-sounding but unverified advice. Trust collapses the moment a user catches the model contradicting sports medicine. RAG over peer-reviewed exercise science (ACSM, NSCA, published research) is the fix.

### Problem 3 — Data silos

Strava has runs. Apple Health has sleep. Whoop has HRV. Google Calendar has the user's schedule. Weather APIs have tomorrow's forecast. Most apps integrate one or two of these. Genuine coaching reasoning requires all of them at once.

### Problem 4 — No injury-prevention reasoning

The endurance world has Training Stress Score (TSS), Acute Training Load (ATL), and Chronic Training Load (CTL) — quantitative metrics that predict overtraining and injury risk. Almost no consumer app exposes them, and none reason about them in plain English. The math is well-established. There's a clear gap.

### Problem 5 — No memory, no continuity

Today's AI sees a single workout in isolation. It doesn't know the user wants to run a half-marathon in November, or mentioned Achilles pain three sessions ago. Every human coach's value comes from accumulated context; the app gets none of it.

---

## Part 2 — Technology mapping

Honest assessment, not a buzzword list.

| Technology                   | Role in this project                                                                                                                                     | Verdict             |
|------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------|
| LLM with tool use            | Replace one-shot Gemini with a tool-using LLM (Claude 4.7, GPT-5, or Gemini Pro function calling) that decides what to fetch and reason over             | Core                |
| Agentic RAG                  | The LLM decides *when* and *what* to retrieve, refines queries iteratively, compares sources, self-critiques. Not a fixed retrieve-then-generate pipeline. | Core differentiator |
| Vector RAG                   | Semantic retrieval over user history + science corpus via pgvector (or Qdrant). Handles "find me something similar."                                       | Required baseline   |
| Graph RAG                    | Knowledge-graph retrieval over fitness concepts (injuries, exercises, training principles, recovery). Handles multi-hop causal reasoning that vector RAG can't. | Strong differentiator |
| Multilingual RAG             | Cross-lingual embeddings (BGE-M3, Cohere multilingual-v3) so the corpus can include non-English sources (German strength science, Italian cycling, Japanese running methodology) and users can query in their native language | Phase 2 onwards     |
| Vector DB                    | pgvector if Postgres is already deployed; Qdrant if a standalone vector store is preferred                                                               | Required for RAG    |
| Graph DB                     | Neo4j or ArangoDB for the fitness knowledge graph. Alternative: store graph in Postgres with adjacency tables — simpler if scale is small.                | Required for Graph RAG |
| LangGraph                    | State-machine orchestration: observe → retrieve → diagnose → plan → act → critique → respond. Stateful agent loops with conditional routing.              | Use as orchestrator |
| LangChain                    | Useful only for its retriever / splitter / loader abstractions during RAG ingestion. Not the orchestrator.                                                | Limited / optional  |
| MCP (Model Context Protocol) | Wrap external data sources as MCP servers: Strava, Apple Health / Google Health Connect, Google Calendar, Weather, Spotify. LLM uses them via tool calls. | Strong fit          |
| A2A (Agent-to-Agent)         | Standard message protocol between specialized agents (Coach / Recovery / Nutrition / Scheduler). Only worth adopting once multiple agents exist.          | Phase 4 — wait      |
| Memory layer                 | Long-term user memory: goals, injuries, preferences, conversation summaries. Mem0, Letta, or pgvector + structured tables.                                | Required            |
| Eval & observability         | Langfuse or LangSmith — trace every tool call, prompt, latency, success / failure                                                                         | Required from day 1 |
| Streaming / voice            | OpenAI Realtime or Gemini Live for "talk to your coach mid-run"                                                                                          | Phase 5 — stretch   |

Rules of thumb:

- Pick LangGraph over LangChain as the orchestrator. It models agent state machines explicitly.
- Ship one agent before talking about multi-agent. Premature decomposition is the #1 cause of agentic project failure.
- Don't ship anything to users without an eval / tracing setup. Hallucinations are silent until they aren't.

---

## RAG architecture (Vector + Graph + Multilingual)

Three different retrieval techniques, each solving a different problem. They cooperate; they don't replace each other.

### Vector RAG — "find me something similar"

The baseline. Embed every document chunk and every user activity into a high-dimensional vector; retrieve by cosine similarity.

**What it handles well:**
- "Find papers about Achilles tendinopathy in runners"
- "What did this user do the last time they had a similar workout?"

**What it cannot handle:**
- Multi-hop causal chains ("tight hip flexors → anterior pelvic tilt → lower back pain → altered running form")
- Structured relationships (which exercises target which muscles, which injuries cascade into which)

### Graph RAG — "trace the causal chain"

Build a knowledge graph of fitness concepts. Nodes are concepts (muscles, exercises, injuries, training principles, nutrients, recovery modalities). Edges are typed relationships (`targets`, `causes`, `prevents`, `recovers_from`, `is_prerequisite_for`).

**Fitness is unusually graph-shaped:**

```
(running with high mileage) --causes--> (cumulative fatigue)
        |                                      |
        v                                      v
(glycogen depletion)                  (reduced HRV)
        |                                      |
        v                                      v
(carb timing matters) <--informs-- (recovery agent)
```

**What it unlocks:**
- "Why does the agent recommend X?" → traversable, explainable chain
- "User reports knee pain on downhills" → hop from `knee` → `quad eccentric load` → `downhill running` → recommend glute strengthening + reduce downhill volume. A vector search cannot string those hops together.
- Personalized cause-tracing: when an injury appears, the graph lets the agent reason backward through the training log to find the likely chain of contributors.

**Stack:**
- Storage: Neo4j (mature, well-tooled) or Postgres with adjacency tables if the graph stays small
- Population: hybrid — manual curation for the spine (ACSM-style canonical relationships), LLM-extracted edges from the science corpus to fill in the long tail
- Retrieval: the agent issues Cypher (or SQL) queries via a tool; results are converted to natural-language context for the synthesis step
- Microsoft's GraphRAG paper / repo is a good reference for the indexing pipeline

### Multilingual RAG — "the best research isn't all in English"

A non-trivial fraction of sports science is published in German (strength training, biomechanics), Italian (cycling), Japanese (running methodology, ultra-distance), Norwegian (cross-country skiing, threshold training research). Restricting the corpus to English leaves real value on the table.

**Two reasons it matters for this project:**

1. **Corpus quality**: include non-English sources and translate at query time, or use cross-lingual embeddings to retrieve directly.
2. **User reach**: a Spanish-speaking user can ask in Spanish, retrieve from an English/German corpus, and get a Spanish answer. Major accessibility win, almost no fitness app does this.

**Stack:**
- Embedding model: BGE-M3 or Cohere `embed-multilingual-v3.0` — both project all languages into a shared semantic space, so similarity search works across languages without translating first
- Generation: use the LLM's native multilingual capability (Claude, GPT-5, Gemini all handle 30+ languages well) for the final answer in the user's language
- Optional: translate ingested non-English chunks at corpus-build time *as well* (store both versions), so retrieval is robust either way

### How they combine in one query

A single user turn typically uses all three:

```
User: "I've been getting Achilles pain on long runs."

1. Memory layer:    load user goals, injury history, recent training
2. Vector RAG:      retrieve similar past sessions where pain was reported
3. Graph RAG:       traverse Achilles -> calf eccentric load -> downhill /
                    high mileage / poor footwear -> recovery modalities
4. Multilingual:    pull a relevant German biomechanics study + an English
                    ACSM guideline; embeddings made them comparable
5. Synthesis:       LLM combines all of the above into one grounded answer
                    in the user's preferred language
```

The agent decides which steps are needed each turn — that's the "agentic" part.

---

## LangGraph orchestration topology

The agent isn't a single prompt — it's a state machine with conditional routing. Here's the graph this project will run, expressed at the level of nodes and edges:

```
                 +-------------+
                 |    START    |
                 +------+------+
                        |
                        v
                +---------------+
                |    PLANNER    |   decides which subset of tools / RAG
                +-------+-------+   layers are needed for this turn
                        |
        +---------------+---------------+
        |               |               |
        v               v               v
+-------------+  +-------------+  +-------------+
|  MEMORY     |  | USER_DATA   |  |  SCIENCE    |
|  RECALL     |  | TOOL CALL   |  |  RAG (vec   |
|  (Mem0/     |  | (activity,  |  |  + graph    |
|  pgvector)  |  | MCP, etc.)  |  |  + multi)   |
+------+------+  +------+------+  +------+------+
       |                |                |
       +--------+-------+----------------+
                |
                v
        +---------------+
        |  SYNTHESIZER  |  combines retrieved context + tool outputs
        +-------+-------+  drafts an answer
                |
                v
        +---------------+
        |    CRITIC     |  self-check: is the advice grounded?
        +-------+-------+  contradictions with science corpus?
                |
        cond: needs revision?
        +----------+----------+
        |                     |
       yes                    no
        |                     |
        +--> back to PLANNER  +--> +---------------+
                                   |   MEM_WRITE   |  persist new facts
                                   +-------+-------+  back to memory
                                           |
                                           v
                                   +---------------+
                                   |    STREAM     |  stream to client
                                   +-------+-------+
                                           |
                                           v
                                         END
```

**Why LangGraph specifically:**

- Conditional edges (CRITIC → PLANNER vs. CRITIC → MEM_WRITE) — natural to express
- Each node has typed state; failure of one node is isolated
- Built-in checkpointing means long-running multi-turn conversations resume cleanly
- Trace integration with LangSmith / Langfuse out of the box

For the **adaptive training plan** in Phase 4, the topology grows a second loop:

```
WEEKLY_EVAL --> RECOVERY_AGENT --> RISK_ASSESSMENT
                                          |
                                          v
                                   PLAN_REVISER --> CALENDAR_AGENT --> NOTIFY_USER
```

That's the multi-agent system. Each box is its own LangGraph subgraph, and they exchange messages via A2A (or a supervisor pattern if A2A is still immature when you get there).

---

## Part 3 — Phased build plan

Each phase is a shippable product on its own. Don't try to do all of it before launching anything.

### Phase 1 — Replace one-shot AI with an agent (4–6 weeks)

**Goal:** User submits an activity → an LLM agent with tools produces a grounded, contextual analysis using the user's own history.

**Deliverables:**

1. New service: `agent-service` (Python, FastAPI). Sits alongside the existing `ai-service`, which can be deprecated once feature parity is reached.
2. Switch to a tool-using LLM (Claude 4.7 recommended; alternatives: GPT-5, Gemini Pro with function calling). Run a latency / cost A/B before locking in.
3. Tools exposed to the agent:
   - `get_user_history(userId, days=30)` — calls the existing activity-service
   - `get_user_profile(userId)` — name, stated goals, known injuries
   - `vector_search_corpus(query)` — semantic retrieval over the science corpus
   - `compute_training_load(userId)` — ATL, CTL, TSS calculation
4. Build the science corpus (Vector RAG baseline):
   - Ingest ACSM and NSCA position statements, openly licensed sports-science papers, well-known methodology books
   - Chunk, embed (`text-embedding-3-large` or `gemini-embedding-001`), store in pgvector
5. LangGraph orchestration — implement the topology shown above with PLANNER, retrieval branches, SYNTHESIZER, CRITIC, MEM_WRITE
6. Wire Langfuse for full tracing

**User-visible outcome:** Recommendations cite real sources, factor in the last 30 days of training, and don't repeat advice you've already seen.

### Phase 2 — Memory + conversational coach + Multilingual RAG (4–5 weeks)

**Goal:** User can converse with the coach. The coach remembers everything across sessions. The coach can read the international research corpus and reply in the user's language.

**Deliverables:**

1. New service: `chat-service`. Websocket endpoint, streaming responses.
2. Long-term memory (Mem0 or pgvector + structured user-facts table):
   - "I want to run a half-marathon in November"
   - "I have a recurring Achilles issue"
   - "I prefer morning runs"
3. At conversation start: agent loads memory. After each turn: agent writes new facts back.
4. **Multilingual RAG layer:**
   - Swap the embedding model from a monolingual one to **BGE-M3** or **Cohere `embed-multilingual-v3.0`** — both project 100+ languages into a shared semantic space
   - Ingest non-English sources: German strength/biomechanics research, Italian cycling literature, Japanese ultra-running methodology, Norwegian threshold-training papers
   - Detect the user's input language; pass it to the LLM as part of the system prompt so the final answer comes back in that language
   - Optionally store English machine translations of non-English chunks alongside the originals for retrieval robustness
5. Frontend: chat panel in the existing React app, MUI-driven. Suggested prompts as chips ("How was my last week?", "What should I do today?").

**User-visible outcome:** A coach that remembers you, draws on global research, and answers in your language.

### Phase 3 — Graph RAG (4–6 weeks)

**Goal:** Add multi-hop causal reasoning over a fitness knowledge graph. This is where the agent stops sounding like a generic chatbot and starts sounding like a coach who knows the domain.

**Deliverables:**

1. **Knowledge graph schema** — concept types and relationship types. Concept types: `Muscle`, `Exercise`, `Injury`, `TrainingPrinciple`, `Nutrient`, `RecoveryModality`, `Symptom`, `ActivityType`. Relationship types: `targets`, `causes`, `prevents`, `aggravates`, `recovers_from`, `is_prerequisite_for`, `progresses_to`, `contradicts`.
2. **Graph store** — Neo4j (mature, Cypher, well-tooled) for the production deployment. For an MVP, adjacency tables in the existing Postgres work fine.
3. **Graph population pipeline** — hybrid manual + LLM-extracted:
   - Curate the spine manually: ~200 nodes covering the major exercise / injury / muscle / training-principle entities, with ~500 hand-validated edges. This is the trustworthy core.
   - Extend via LLM extraction over the science corpus: prompt Claude / GPT to emit `(subject, relation, object)` triples with citations, then human-review before insertion.
   - Reference Microsoft's GraphRAG repository for the indexing approach if going large.
4. **Graph retrieval tool** for the agent — `graph_traverse(start_node, max_hops=3, relations=[...])`. The agent uses it for symptom-tracing and explanation chains.
5. **Integration with the existing Vector RAG** — both run in parallel inside the LangGraph PLANNER node. The agent decides per turn whether vector, graph, or both are needed.

**Concrete unlock — symptom-to-cause tracing:**

```
User:  "I've been getting knee pain on downhills lately."

Graph query:  start at (Symptom: knee pain) -> hop to (causes^-1) ->
              filter to (Exercise: downhill running) -> hop to
              (related_muscle: quadriceps eccentric load) -> hop to
              (recovers_from / prevents) -> (eccentric strengthening,
              reduced downhill volume)

Agent response (drafted by SYNTHESIZER using graph chain as context):
  "Downhill running heavily loads the quads eccentrically, which can
  irritate the patellar tendon. Two things worth trying: reduce the
  downhill segment of your long run by ~30% this week, and add 2 sets
  of eccentric step-downs three times a week. [cites the graph nodes
  and the underlying paper in the corpus]"
```

A vector-only RAG would, at best, find a paper about knee pain. The graph lets the agent reason about *why* and recommend *specifically*.

**User-visible outcome:** Explainable, causally-grounded reasoning. Every recommendation has a traversable "why" chain.

### Phase 4 — MCP-powered data unification (4–6 weeks)

**Goal:** The agent sees the user's whole life relevant to training, not just what they typed in.

**Deliverables:**

1. Consume or build MCP servers:
   - Strava MCP — historical activities
   - Apple Health / Google Health Connect MCP — sleep, heart rate, HRV
   - Google Calendar MCP — schedule awareness
   - Weather MCP — upcoming-week forecasts
2. A "Connections" page in the frontend for users to authorize each source (each MCP server has its own OAuth flow).
3. The agent now produces reasoning like: *"Tomorrow is a long run, but HRV dropped 15% and you have a 7am meeting. Want me to swap it with Thursday's easy day?"*

**User-visible outcome:** Cross-domain coaching reasoning competitors don't offer.

### Phase 5 — Multi-agent system + adaptive planning (6–8 weeks)

**Goal:** Specialized agents collaborate. Training plans revise themselves weekly.

**Deliverables:**

1. Agent roles:
   - **Coach Agent** — owns the training plan, revises it weekly
   - **Recovery Agent** — monitors training load, sleep, HRV; raises flags
   - **Nutrition Agent** — pairs with food log (optional)
   - **Scheduler Agent** — books workouts into the user's calendar
2. Inter-agent communication: A2A protocol if mature enough at the time; otherwise a LangGraph supervisor pattern.
3. The headline feature — **adaptive training plan**:
   - End of each week the Coach Agent runs an evaluation step (with Recovery Agent input)
   - Rewrites next week's plan based on what actually happened, not what was originally scheduled
   - User receives a "Here's what's changing and why" summary

**User-visible outcome:** A plan that genuinely adapts, the way Future ($199 / month with human coaches) does — but automated.

### Phase 6 — Voice + ambient (4–6 weeks, optional)

**Goal:** Talk to your coach during a workout.

**Deliverables:**

1. Realtime API (OpenAI Realtime or Gemini Live) integration
2. Phone audio in → agent reasoning with full user context → spoken response
3. Use case examples:
   - *"You're 3 seconds per km slower than threshold. HR looks fine. Push the next km."*
   - *"Skip the last interval — HRV says you're cooked."*

**User-visible outcome:** A demo-grade differentiator. Almost no fitness product has shipped this.

---

## Differentiation

If only one feature ships from this roadmap, it should be **adaptive multi-week plans that revise themselves based on grounded science and the user's real-life data**. Everything else supports that headline.

### Competitive positioning

| Competitor   | Gap                                                              |
|--------------|------------------------------------------------------------------|
| Strava       | Descriptive only — no planning, no replanning                    |
| Future       | Real adaptive coaching, but uses human coaches; $199 / month     |
| Fitbod       | Adaptive but limited to gym workouts; no endurance               |
| TrainerRoad  | Adaptive cycling plans only; no chat, no science citations       |
| MyFitnessPal | Calorie focus, weak on training reasoning                        |

**Positioning statement:** "The AI fitness coach that plans your life around your goals, cites the science, and learns you."

---

## Risks & honest caveats

1. **This is a 6–12 month roadmap, not a weekend project.** Phase 1 alone is real work.
2. **The science corpus is the moat and it's manual.** Curating 100+ high-quality sources is what makes the RAG useful vs. a chatbot wrapper. Plan for the time.
3. **Don't decompose into multiple agents before a single agent works.** Premature complexity is the most common agentic-project failure mode.
4. **LangChain is optional.** Prefer LangGraph for orchestration. Use LangChain only for ingestion utilities if you reach for them.
5. **Inference costs will rise.** Tool-using LLM calls with RAG context can run $0.10–$0.50 per turn at scale. Mitigations:
   - Anthropic prompt caching for system prompt + corpus chunks
   - Smaller / cheaper model for triage routing
   - Local re-ranking before sending to the large model
6. **Eval and tracing from day one.** Don't ship Phase 1 without Langfuse / LangSmith — agent regressions are silent and damaging.

---

## Suggested order of operations (next 30 days)

1. Stand up `agent-service` skeleton (Python, FastAPI). Replace one Gemini call end-to-end with a Claude tool-use call that has access to `get_user_history`.
2. Add pgvector to the existing Postgres instance.
3. Build the smallest possible science corpus (10–20 documents) — enough to prove Vector RAG retrieval works.
4. Implement the LangGraph topology (PLANNER → retrieval branches → SYNTHESIZER → CRITIC → MEM_WRITE) — even with one tool, the structure is what matters.
5. Wire Langfuse — every LLM call must produce a trace.
6. Sketch the Graph RAG schema on paper: 20–30 core nodes, 50–100 edges. Don't build it yet, just prove the design. This makes Phase 3 cheap.
7. Ship internally. Use it on your own training for two weeks. Note where it's wrong, vague, or repetitive. Fix those, then plan Phase 2.

---

## Phase summary

| Phase | Focus                                      | Tech added                                              | Duration |
|-------|--------------------------------------------|---------------------------------------------------------|----------|
| 1     | Agent with tools + Vector RAG              | LangGraph, pgvector, Claude tool use, Langfuse          | 4–6 wks  |
| 2     | Memory + chat + Multilingual RAG           | Mem0, BGE-M3 / Cohere multilingual, websockets          | 4–5 wks  |
| 3     | Graph RAG                                  | Neo4j (or Postgres adjacency), graph extraction pipeline | 4–6 wks  |
| 4     | MCP-powered data unification               | MCP servers (Strava, Health, Calendar, Weather)         | 4–6 wks  |
| 5     | Multi-agent + adaptive planning            | A2A / supervisor pattern, weekly plan revision loop     | 6–8 wks  |
| 6     | Voice + ambient (optional)                 | OpenAI Realtime / Gemini Live                            | 4–6 wks  |
