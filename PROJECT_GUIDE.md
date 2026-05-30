# Project Guide — Fitness Microservices, Agentic Evolution

This is the single document for the entire project. It merges what was previously split across ROADMAP, IMPLEMENTATION, and SYSTEM_DESIGN. Read it top to bottom on first pass; refer back to specific parts later.

The document is organized as a journey:

1. **Why** — the real problems we're solving
2. **What** — the system architecture (agents, RAG, data, features)
3. **When** — the six-phase build plan
4. **Do It** — the step-by-step Phase 1 walkthrough
5. **Reference** — glossary, common mistakes, learning resources, open questions

If you're starting from zero, jump straight to **Part 4** for the hands-on guide once you've skimmed Parts 1–3.

---

# Table of contents

- [Part 1 — Why we're building this](#part-1--why-were-building-this)
  - 1.1 Current state of the project
  - 1.2 Real problems worth solving
- [Part 2 — What we're building](#part-2--what-were-building)
  - 2.1 High-level architecture
  - 2.2 Key architectural decisions
  - 2.3 Multi-agent system: Supervisor + specialists
  - 2.4 Inter-agent communication (A2A)
  - 2.5 LangGraph orchestration
  - 2.6 RAG architecture (Vector + Graph + Multilingual + Memory)
  - 2.7 Feature: food photo → calorie estimation
  - 2.8 Feature: workout form analysis from video
  - 2.9 Feature: race / event preparation
  - 2.10 Feature: recovery monitoring
  - 2.11 Data model
  - 2.12 Security, PII, and image handling
  - 2.13 Cost model
  - 2.14 Deployment topology
- [Part 3 — When to build what (six-phase plan)](#part-3--when-to-build-what-six-phase-plan)
- [Part 4 — How to start: Phase 1 step by step](#part-4--how-to-start-phase-1-step-by-step)
- [Part 5 — Reference](#part-5--reference)
  - 5.1 Glossary
  - 5.2 Technology mapping
  - 5.3 Common beginner mistakes
  - 5.4 Learning resources
  - 5.5 When you're stuck
  - 5.6 Open questions deferred to later phases

---

# Part 1 — Why we're building this

## 1.1 Current state of the project

| Capability                       | Today                                                                   |
|----------------------------------|-------------------------------------------------------------------------|
| Activity logging                 | Frontend POSTs to activity-service, MongoDB persistence                 |
| AI analysis                      | ai-service consumes from Kafka, calls Gemini once, stores JSON response |
| Memory / context across sessions | None — every Gemini call is one-shot                                    |
| Grounding in real science        | None — recommendations are model priors                                 |
| External data sources            | None — only what the user types in                                      |
| Plan adherence                   | No training plan exists yet                                             |

The AI layer is a thin wrapper around a single Gemini prompt. It works as a demo. It does not work as a product.

## 1.2 Real problems worth solving

Each of the following is a documented pain point in mainstream fitness apps (Strava, MyFitnessPal, Whoop, Fitbod, Future). Each is a place where agentic AI is genuinely differentiated, not gimmicky.

### Problem 1 — Static plans that ignore real life

A 12-week marathon plan assumes nothing changes. Users get sick, travel, work late. Manual adjustment doesn't happen — they just quit. Plan adherence is the metric for fitness apps; roughly 80% of users abandon by week four.

### Problem 2 — Recommendations without grounding

A one-shot LLM call produces plausible-sounding but unverified advice. Trust collapses the moment a user catches the model contradicting sports medicine. RAG over peer-reviewed exercise science is the fix.

### Problem 3 — Data silos

Strava has runs. Apple Health has sleep. Whoop has HRV. Google Calendar has the user's schedule. Weather APIs have tomorrow's forecast. Most apps integrate one or two of these. Genuine coaching reasoning requires all of them at once.

### Problem 4 — No injury-prevention reasoning

The endurance world has Training Stress Score (TSS), Acute Training Load (ATL), and Chronic Training Load (CTL) — quantitative metrics that predict overtraining and injury risk. Almost no consumer app exposes them, and none reason about them in plain English. The math is well-established. There's a clear gap.

### Problem 5 — No memory, no continuity

Today's AI sees a single workout in isolation. It doesn't know the user wants to run a half-marathon in November, or mentioned Achilles pain three sessions ago. Every human coach's value comes from accumulated context.

### Problem 6 — No nutrition awareness

Training without nutrition context is like driving without a fuel gauge. Most apps split these into separate products. A unified coach that understands both — including from photos of food the user actually ate — is rare.

### Competitive positioning

| Competitor   | Gap                                                              |
|--------------|------------------------------------------------------------------|
| Strava       | Descriptive only — no planning, no replanning                    |
| Future       | Real adaptive coaching, but uses human coaches; $199 / month     |
| Fitbod       | Adaptive but limited to gym workouts; no endurance               |
| TrainerRoad  | Adaptive cycling plans only; no chat, no science citations       |
| MyFitnessPal | Calorie focus, weak on training reasoning                        |

**Positioning statement:** "The AI fitness coach that plans your life around your goals, cites the science, and learns you."

If only one feature ships from this project, it should be **adaptive multi-week plans that revise themselves based on grounded science and the user's real-life data**. Everything else supports that headline.

---

# Part 2 — What we're building

## 2.1 High-level architecture

```
                                    Browser (React, Vite)
                                            |
                                            |  HTTPS, JWT
                                            v
                                    API Gateway (Spring Cloud Gateway)
                                            |
                            +---------------+---------------+
                            |                               |
                            v                               v
                +-----------------------+        +----------------------+
                |   Existing services   |        |  agent-service       |
                |  (user, activity,     |        |  (Python, FastAPI)   |
                |   ai legacy)          |        |                      |
                +-----------+-----------+        |   +---------------+  |
                            |                    |   |  SUPERVISOR   |  |
                            |                    |   |  AGENT        |  |
                            |                    |   +-------+-------+  |
                            |                    |           |          |
                            |                    |           v          |
                            |                    |   +-------+-------+  |
                            |                    |   | Coach Agent   |  |
                            |                    |   | Recovery      |  |
                            |                    |   | Nutrition     |  |
                            |                    |   | Vision        |  |
                            |                    |   | Scheduler     |  |
                            |                    |   +-------+-------+  |
                            |                    +-----------+----------+
                            |                                |
                            v                                v
        +---------------------------+      +----------------------------+
        | Data layer                |      | External                   |
        |  PostgreSQL  (users,      |      |  Gemini API  (LLM + vision |
        |    facts, recovery log)   |      |     + embeddings)          |
        |  MongoDB     (activities, |      |  MCP servers (Strava,      |
        |    recommendations)       |      |     Health, Calendar,      |
        |  Neo4j       (knowledge   |      |     Weather)               |
        |    graph)                 |      |  Object store (image       |
        |  pgvector    (RAG chunks) |      |     uploads, S3 / MinIO)   |
        +---------------------------+      +----------------------------+
```

Existing Spring Boot services remain the **system of record** for users and activities. The `agent-service` is a new Python service (FastAPI + LangGraph) that contains the agent layer and orchestrates them. It calls existing services via HTTP, just like the browser does.

## 2.2 Key architectural decisions

| Decision                                  | Choice                                           | Rationale                                                                                                |
|-------------------------------------------|--------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| Agent topology                            | Supervisor + specialists                         | One Supervisor routes user requests; specialists talk back to Supervisor, not to each other. Easier to debug, fewer infinite loops. |
| Inter-agent protocol                      | A2A (Google's spec), framed by Supervisor        | Standardized message envelopes; Supervisor enforces the topology.                                        |
| Orchestration                             | LangGraph per agent + a top-level Supervisor graph | Each specialist is its own subgraph; Supervisor is the parent graph routing between them.              |
| LLM                                       | Gemini 2.5 Flash for routing & tool use; 2.5 Pro for hard reasoning | Free tier covers development; Flash is fast and good enough for ~90% of calls.         |
| Vision                                    | Gemini 2.5 Flash native multimodal               | No separate vision model. Trades some accuracy vs. a specialized food model.                             |
| Long-term memory                          | Postgres `user_facts` table; Mem0 once outgrown  | Start simple; upgrade only when flat strings become limiting.                                            |
| Vector store                              | pgvector on the existing Postgres                | No new infrastructure for Phase 1; Qdrant if scale demands it later.                                     |
| Knowledge graph                           | Neo4j                                            | Mature, Cypher is well-documented, Docker image exists.                                                  |
| Service language                          | Python for agent layer                           | LangGraph, LangChain, Gemini SDK, Mem0, MediaPipe are all Python-first.                                  |
| Frontend                                  | React + Vite (existing)                          | Add a chat panel and image upload; everything else stays.                                                |
| Image storage                             | MinIO (local) / S3 (prod) with signed URLs       | Don't store images in Postgres or MongoDB.                                                               |
| Authentication                            | Keycloak JWT (existing) propagated to agent-service | Single auth model across all services.                                                              |

## 2.3 Multi-agent system: Supervisor + specialists

Each agent is a LangGraph subgraph with a focused system prompt, a toolset, and a data ownership boundary.

### 2.3.1 Supervisor Agent

**Purpose:** Front door for every user request. Decides which specialist(s) handle it and stitches the final response. Does not answer fitness questions itself.

**Tools:** `route_to(agent, message)`, `synthesize_final_response(responses)`.

### 2.3.2 Coach Agent

**Purpose:** Training plans, workout analysis, performance questions. Owns the user's active training plan.

**Tools:**
- `get_user_history(user_id, days)` — calls activity-service
- `vector_search_corpus(query)` — sports-science RAG
- `graph_traverse(start_concept, relations, max_hops)` — knowledge graph
- `compute_training_load(user_id)` — ATL/CTL/TSS calculation
- `get_user_facts(user_id)` — long-term memory
- `update_training_plan(user_id, plan)` — writes to Postgres
- `request_recovery_check(user_id)` — A2A to Recovery Agent

**Data ownership:** `training_plans`, `training_plan_revisions`.

### 2.3.3 Recovery Agent

**Purpose:** Aggregates sleep, HRV, soreness, training load. Has veto power on hard sessions when load is too high.

**Tools:**
- `get_hrv(user_id, days)` / `get_sleep(user_id, days)` — Apple Health / Whoop MCP
- `get_subjective_soreness(user_id)` — Postgres
- `compute_training_load(user_id)` (shared with Coach)
- `compute_recovery_score(user_id)` — composite of HRV ratio, sleep debt, soreness, load
- `vector_search_corpus(query)`, `graph_traverse(...)`

**Data ownership:** `recovery_log`, `recovery_scores`.

### 2.3.4 Nutrition Agent

**Purpose:** Food logging (manual and via photo), macro tracking, fueling around workouts.

**Tools:**
- `log_meal(user_id, items, calories, ...)`
- `get_recent_meals(user_id, days)`
- `compute_daily_intake(user_id, date)`
- `vector_search_corpus(query)`, `graph_traverse(...)`
- `request_vision_analysis(image_id)` — A2A to Vision Agent

**Data ownership:** `meals`, `meal_items`.

### 2.3.5 Vision Agent

**Purpose:** Image and video analysis. Two subtasks:
1. Food image → estimated nutrition (called by Nutrition Agent)
2. Workout video → form critique (called via Supervisor)

**Tools:**
- `analyze_food_image(image_url)` — Gemini Vision call
- `analyze_workout_video(video_url, exercise_type)` — MediaPipe pose + Gemini Vision
- `lookup_nutrition_db(food_name)` — USDA FoodData Central fallback

**Data ownership:** Writes nothing directly. Calling agent persists.

### 2.3.6 Scheduler Agent

**Purpose:** Translates Coach's plan into calendar entries. Detects conflicts; proposes shifts.

**Tools:**
- `read_calendar(user_id, range)` — Google Calendar MCP
- `create_event(user_id, event)` — Calendar MCP
- `find_free_slot(user_id, duration, preferences)`
- `request_plan_revision(user_id, conflict)` — A2A to Coach

**Data ownership:** None — calendar is the source of truth.

## 2.4 Inter-agent communication (A2A)

### Message envelope

```json
{
  "id": "msg-uuid",
  "from": "supervisor",
  "to": "nutrition",
  "type": "request",
  "in_reply_to": null,
  "content": {
    "task": "analyze_food_photo",
    "user_id": "...",
    "image_id": "img-uuid",
    "context": "User ate this around 7pm on 2026-05-22"
  },
  "metadata": {
    "trace_id": "lf-trace-uuid",
    "user_jwt": "..."
  }
}
```

`trace_id` is the Langfuse trace ID so every cross-agent hop is recorded as part of the same trace tree.

### Communication rules

1. **Supervisor is always the entry point.**
2. **Specialists reply to Supervisor by default.**
3. **Specialist-to-specialist messages are allowed** but only for tightly-scoped purposes (e.g. Nutrition asks Vision to analyze a photo).
4. **No agent may call itself.** Loops are guarded by Supervisor.
5. **Every A2A message is traced** in Langfuse.

### Sequence example — user posts food photo

```
1. user        -> supervisor   : "Just ate this for lunch" + image URL
2. supervisor  -> nutrition    : route(task=log_meal_from_photo, image_id=...)
3. nutrition   -> vision       : request(task=analyze_food_image, image_id=...)
4. vision      -> nutrition    : reply(items=[{name, calories, ...}, ...])
5. nutrition                   : log_meal(...) -> Postgres
6. nutrition   -> supervisor   : reply(summary="Logged 720 kcal, ...")
7. supervisor  -> user         : "Logged your lunch. 720 kcal, 38g protein..."
```

## 2.5 LangGraph orchestration

### Supervisor parent graph

```
            +-------------+
            |    START    |
            +------+------+
                   |
                   v
            +-------------+
            |   CLASSIFY  |  LLM call: which specialist?
            +------+------+
                   |
        +----------+----------+----------+----------+
        |          |          |          |          |
        v          v          v          v          v
     [COACH]  [RECOVERY] [NUTRITION] [VISION]  [SCHEDULER]
        |          |          |          |          |
        +----------+----------+----------+----------+
                   |
                   v
            +-------------+
            |  AGGREGATE  |  merges specialist outputs
            +------+------+
                   |
                   v
            +-------------+
            |    CRITIC   |  fact-check & grounding pass
            +------+------+
                   |
            cond: needs more info?
            +----+--------------+
            |                   |
           yes                  no
            |                   |
       back to CLASSIFY      to END
```

### Specialist subgraph (uniform shape across all specialists)

```
PLANNER -> [MEM_RECALL | TOOL_CALL | VECTOR_RAG | GRAPH_RAG]  (parallel where possible)
        -> SYNTHESIZER -> CRITIC -> MEM_WRITE -> RETURN
```

### Classifier routing rules

| User input contains                                          | Route to                       |
|--------------------------------------------------------------|--------------------------------|
| "How was my run", "Compare to last week", training questions | Coach                          |
| "I feel tired", "sleep", "HRV"                               | Recovery                       |
| Image attachment + meal context                              | Nutrition (-> Vision)          |
| "Macro", "protein", "carbs", workout fueling                 | Nutrition                      |
| Video attachment + exercise mention                          | Vision                         |
| "Schedule", "book", "calendar"                               | Scheduler                      |
| Multi-domain ("Can I run hard tomorrow?")                    | Coach + Recovery (parallel)    |

## 2.6 RAG architecture

Three retrieval layers plus memory. All available to every specialist; agents pick combinations per turn.

### Vector RAG — "find me something similar"

Baseline. Embed every document chunk and every user activity into a vector; retrieve by cosine similarity.

- Handles well: "find papers about Achilles tendinopathy in runners"; "what did this user do last time"
- Cannot handle: multi-hop causal chains; structured relationships

### Graph RAG — "trace the causal chain"

Knowledge graph of fitness concepts. Nodes: Muscle, Exercise, Injury, Symptom, Nutrient, TrainingPrinciple, RecoveryModality. Edges: `targets`, `causes`, `prevents`, `aggravates`, `recovers_from`, `is_prerequisite_for`, `progresses_to`.

Fitness is unusually graph-shaped — symptom-to-cause tracing requires hops a vector search can't string together. Example:

```
User: "I've been getting knee pain on downhills."

Graph traversal:
  (knee pain) -> (caused_by) -> (downhill running)
              -> (related_muscle: quadriceps eccentric load)
              -> (recovers_from / prevents) -> (eccentric strengthening,
                                                reduced downhill volume)
```

Stack: Neo4j for production; manually seeded core nodes (~200) + LLM-extracted long tail.

### Multilingual RAG — "the best research isn't all in English"

Sports science published in German (strength training), Italian (cycling), Japanese (running methodology), Norwegian (threshold training research). Restricting to English leaves real value on the table.

- Embedding model: BGE-M3 or Cohere `embed-multilingual-v3.0` — both project all languages into one shared semantic space
- Generation: Gemini's native multilingual capability
- Replies in user's input language (detected with `langdetect`)

### Memory layer

- `user_facts` table: structured facts ("goal: half-marathon Nov 2026", "injury: Achilles, recurring")
- Conversation summaries written after each session
- Shared across all agents

### How they combine in one query

```
User: "I've been getting Achilles pain on long runs."

1. Memory layer:    load goals, injury history, recent training
2. Vector RAG:      retrieve similar past sessions where pain was reported
3. Graph RAG:       traverse Achilles -> calf eccentric load -> downhill /
                    high mileage / poor footwear -> recovery modalities
4. Multilingual:    pull a relevant German biomechanics study + an English
                    ACSM guideline; shared embeddings made them comparable
5. Synthesis:       LLM combines all of the above into one grounded answer
                    in the user's preferred language
```

The agent decides which steps are needed each turn — that's the "agentic" part of agentic RAG.

## 2.7 Feature: food photo → calorie estimation

### User flow

1. User taps "Log meal"; camera opens
2. User takes photo; optionally adds a caption ("lunch") or portion hint
3. Image uploads to MinIO/S3; signed URL returned
4. Frontend POSTs `{image_url, caption, meal_time}` to `/api/agent/log-meal`
5. Supervisor → Nutrition → Vision (A2A)
6. Vision Agent calls Gemini 2.5 Flash with image + structured prompt
7. Response (structured JSON) → Nutrition Agent
8. Nutrition Agent persists to `meals` / `meal_items`
9. Frontend displays parsed result; user can correct
10. Corrections feed back as a personal adjustment factor (Phase 4+)

### Vision pipeline

```python
def analyze_food_image(image_url: str, caption: str | None = None) -> dict:
    """Call Gemini Vision with a structured-output schema."""
    image_bytes = httpx.get(image_url).content

    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            types.Part.from_bytes(data=image_bytes, mime_type="image/jpeg"),
            f"User caption: {caption or '(none)'}",
            "Identify each distinct food item visible. For each, estimate "
            "the portion in grams and the macros. Use the reference object "
            "in the photo (utensil, hand, plate) to calibrate portion size. "
            "If you cannot identify an item, return it with confidence=low."
        ],
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=FOOD_ANALYSIS_SCHEMA,
            system_instruction=(
                "You are a nutrition analyst. Be conservative with estimates. "
                "Prefer to report low confidence than confidently wrong values."
            ),
        ),
    )
    return json.loads(response.text)
```

### Output schema

```json
{
  "items": [
    {
      "name": "grilled chicken breast",
      "portion_grams": 150,
      "calories_kcal": 247,
      "protein_g": 46,
      "carbs_g": 0,
      "fat_g": 6,
      "confidence": "high",
      "notes": "no visible sauce or breading"
    }
  ],
  "total_calories_kcal": 720,
  "total_protein_g": 53,
  "total_carbs_g": 62,
  "total_fat_g": 28,
  "image_quality": "clear",
  "warnings": ["dressing on salad — assumed vinaigrette, may be higher fat"]
}
```

### Accuracy — be honest

Marketing "exact calories" is wrong and will break user trust. Realistic accuracy from a single Gemini Vision call:

- Food identification: 85–95% on common Western foods; below 70% on unusual cuisines / mixed dishes
- Portion estimation: ±30% error without a reference object, ±15% with one
- Hidden ingredients (oil, sugar, butter): another 10–20% uncertainty
- **Net: expect ±25–35% error on total calories**

Useful for trend tracking but **must not be marketed as "exact."** Honest UX:

> "Estimated 720 kcal. Tap to refine."

### Accuracy roadmap

| Phase | What ships                                                              |
|-------|-------------------------------------------------------------------------|
| Phase 3 | Single-shot Gemini Vision (the baseline above)                        |
| Phase 4 | + USDA FoodData Central lookups for confidently-identified items      |
| Phase 5 | + per-user adjustment factor learned from corrections                 |
| Phase 6 | + optional barcode scanning for packaged foods                        |

### Image lifecycle (privacy)

- Uploaded image: MinIO/S3 with 10-min signed URL
- After processing: deleted by default, retained 30 days only on user opt-in
- No biometric inference on faces
- Free tier sends data to Gemini for training; paid tier doesn't. Use Vertex AI for production if privacy-sensitive.

### Cost

Free tier: $0 within daily quota. Paid `gemini-2.5-flash`: ~$0.0005 per image. At 50 active users × 3 meals/day = 150 images/day → ~$2.25/month paid (or $0 free).

## 2.8 Feature: workout form analysis from video

### User flow

1. User records 5–15 second clip of a movement (squat, deadlift, push-up)
2. Frontend uploads to MinIO/S3
3. `POST /api/agent/analyze-form` with `{video_url, exercise_type}`
4. Vision Agent:
   - **MediaPipe Pose** extracts joint keypoints frame-by-frame (free, local)
   - Compute joint angles (knee, hip, back) at key moments
   - Send representative still + computed angles to Gemini Vision with exercise-specific prompt
5. Structured critique returned: what's good, what to fix, drills
6. Coach Agent informed so future workouts factor in form work needed

### Why MediaPipe + Gemini (not Gemini alone)

Gemini Vision alone can describe a video but struggles to give specific joint-angle feedback. MediaPipe handles the geometry; Gemini handles qualitative reasoning. Together they produce specific, actionable critique:

> "Knees collapse inward at the bottom of the squat — add banded squats 2×/week."

### Privacy

Workout videos are more sensitive than food photos (body recognizable). Defaults:
- Stored only if user opts in
- Face blurring optional (MediaPipe detects, blurs before LLM upload)
- MediaPipe-only mode (no cloud upload) for users who disable cloud vision

## 2.9 Feature: race / event preparation

A bounded "project mode" for an upcoming race. Activates 8–16 weeks out.

1. User declares race: *"Half-marathon, 2026-11-15, target 1:45"*
2. Coach Agent creates `race_goal` + `training_plan` for weeks until race day
3. Plan partitions into **base / build / peak / taper / race week** mesocycles
4. Weekly adaptive-planning loop revises next week based on actual training + recovery
5. Race week: agent triggers a **race-day briefing** — pacing strategy, fueling, hydration, weather, sleep targets
6. Post-race: agent compares predicted vs. actual, asks for debrief, persists lessons to memory

### Taper logic (codified, not LLM-computed)

- 3 weeks out: 80% of peak weekly volume, intensity preserved
- 2 weeks out: 60% volume, one quality session
- Race week: 40% volume, light shakeout 2 days before

Deterministic math for known patterns; LLM for judgment calls. Don't ask the LLM to recompute a standard taper from scratch.

### Race-day briefing example

```
Race day prep — Half-marathon, 2026-11-15

Weather (24h forecast): 12°C, light wind, no rain. Good conditions.

Pacing target: 4:58/km for 1:45 finish.
- First 5K: 5:02-5:05/km (intentionally controlled)
- 5-15K:    4:55-5:00/km (race pace)
- Last 6K:  open up if energy allows; OK to drift to 4:50 last 3K

Fueling: gel at 45 min, 75 min, optional at 100 min.
Hydration: sip every aid station.

Sleep target: 7+ hours both Thu and Fri.

Two things to remember:
1. You consistently negative-split your long runs — trust that pattern.
2. Your HRV was high all week. Your body is ready.
```

## 2.10 Feature: recovery monitoring

### Inputs

- Sleep, HRV, resting HR (via Apple Health / Whoop / Garmin MCP)
- Subjective soreness (1–10, daily, 5-second entry)
- Computed training load (ATL/CTL/TSS from activity-service)

### Recovery score (0–100, daily)

```
score = w1 * (HRV today / HRV 30-day baseline)
      + w2 * (sleep hours today / target)
      + w3 * (1 - normalized soreness)
      + w4 * (1 - acute:chronic ratio relative to 1.5 threshold)
```

### Intervention thresholds

| Condition                                           | Action                                            |
|-----------------------------------------------------|---------------------------------------------------|
| Score < 40                                          | Coach softens any hard sessions next 24h (A2A)    |
| Score < 25 for 3 consecutive days                   | Suggest a deload week                             |
| Sudden soreness spike + matching joint              | Prompt user: "log this as injury watch?"          |

## 2.11 Data model

### PostgreSQL

| Table                       | Owner            | Purpose                                      |
|-----------------------------|------------------|----------------------------------------------|
| `users`                     | user-service     | Account, keycloak_id, email, name            |
| `user_facts`                | agent-service    | Long-term memory                             |
| `training_plans`            | Coach Agent      | Active plan per user                         |
| `training_plan_revisions`   | Coach Agent      | Audit log of plan changes                    |
| `race_goals`                | Coach Agent      | User-declared races                          |
| `recovery_log`              | Recovery Agent   | Daily subjective entries                     |
| `recovery_scores`           | Recovery Agent   | Computed daily scores                        |
| `meals`                     | Nutrition Agent  | Per-meal aggregates                          |
| `meal_items`                | Nutrition Agent  | Items in a meal                              |
| `science_chunks`            | shared (pgvector)| RAG corpus chunks                            |

### MongoDB (existing)

| Collection         | Database                  | Purpose                                |
|--------------------|---------------------------|----------------------------------------|
| `activity`         | aiactivityfitness         | User-logged workouts                   |
| `recommendation`   | airecommendationfitness   | Legacy AI recommendations (deprecate)  |

### Neo4j (Phase 3 onwards)

| Node type    | Example edges                                   |
|--------------|-------------------------------------------------|
| `Muscle`     | `targets`, `is_synergist_of`                   |
| `Exercise`   | `targets`, `progresses_to`, `is_prerequisite_for` |
| `Injury`     | `causes`, `aggravates`, `recovers_from`        |
| `Symptom`    | `indicates`, `often_caused_by`                  |
| `Nutrient`   | `aids_recovery_from`, `is_required_for`        |
| `Principle`  | `is_part_of`, `contradicts`                     |
| `Modality`   | `treats`, `complements`                         |

### Object store (MinIO local, S3 prod)

| Bucket              | Contents                          | Lifecycle                                            |
|---------------------|-----------------------------------|------------------------------------------------------|
| `food-photos`       | Meal images                       | Default delete after processing; 30d if opted in     |
| `workout-videos`    | Form-check videos                 | Opt-in only; deletion on user request                |

## 2.12 Security, PII, and image handling

### Authentication chain

```
Browser -> Keycloak (PKCE login) -> JWT
Browser + JWT -> Gateway (validates JWT against JWK set)
Gateway -> agent-service (forwards JWT + X-User-ID header)
agent-service -> Gemini API (uses platform key, never user key)
agent-service -> other Spring services (forwards JWT + X-User-ID)
```

agent-service validates the JWT locally too (defense in depth).

### Image upload flow

1. Frontend: `POST /api/uploads/sign` → gateway → agent-service
2. agent-service generates a signed PUT URL valid 5 minutes
3. Browser uploads directly to MinIO/S3 — image bytes do not transit agent-service
4. agent-service receives only the image *ID*
5. After processing: delete by default, or move to `retained/` prefix (opt-in)

### PII handling

- Image bytes leave the platform only when sent to Gemini for analysis
- No biometric / face recognition
- Logs (Langfuse, app) redact email and full name; only `user_id` (UUID) is logged

### Rate limits per user

- 30 requests/min to agent-service (chat, food photo, etc.)
- 10 image uploads/hour (vision is expensive)
- 1 workout-video analysis/hour (video processing heaviest)

## 2.13 Cost model

Per active user per day (paid tier — free tier is $0 within quotas):

| Action                                | Volume/day | Cost            | Subtotal     |
|---------------------------------------|------------|-----------------|--------------|
| Activity logged + analysis            | 1          | $0.002          | $0.002       |
| 5 chat turns                          | 5          | $0.0015 each    | $0.0075      |
| 3 food photos                         | 3          | $0.0005 each    | $0.0015      |
| 1 workout-video analysis (occasional) | 0.1        | $0.005 each     | $0.0005      |
| Recovery score recompute              | 1          | $0.001          | $0.001       |
| **Total**                             |            |                 | **~$0.013**  |

At 1000 daily-active users: ~$13/day = ~$400/month. Cache aggressively (Gemini context caching + response caching on repeated queries) to halve this.

For development and small private use: free tier covers everything.

## 2.14 Deployment topology

```
Component              Port       Process               Dependencies
----------------------------------------------------------------------
React frontend         5173       vite dev / static     gateway
gateway                8080       Spring Boot           eureka, config-server
config-server          8888       Spring Boot           (none)
eureka                 8761       Spring Boot           (none)
user-service           8081       Spring Boot           postgres, eureka, config
activity-service       8082       Spring Boot           mongodb, kafka, eureka, config
ai-service (legacy)    8083       Spring Boot           mongodb, kafka, eureka, config
agent-service          8084       Python / FastAPI      postgres, neo4j, minio, gemini
chat-service           8085       Python / FastAPI      agent-service, websocket
postgres               5432       Docker                -
mongodb                27017      Docker                -
neo4j                  7474/7687  Docker                -
kafka                  9092       Docker                zookeeper
keycloak               8181       Docker                postgres (separate DB)
minio                  9000/9001  Docker                -
langfuse               cloud or self-host
```

For local dev: a single `docker-compose.yml` brings up postgres, mongodb, neo4j, kafka, keycloak, minio. Spring services and agent-service run on the host machine for fast iteration.

---

# Part 3 — When to build what (six-phase plan)

Each phase ships a working product on its own. Don't try to do all of it before launching anything.

| Phase | Focus                                | Tech added                                                  | Duration | Headline outcome                                          |
|-------|--------------------------------------|-------------------------------------------------------------|----------|-----------------------------------------------------------|
| 1     | Agent + Vector RAG                   | LangGraph, pgvector, Gemini tool use, Langfuse              | 4–6 wks  | Grounded analysis using user history + science citations  |
| 2     | Memory + chat + Multilingual RAG     | Mem0, BGE-M3 / Cohere multilingual, websockets, Nutrition Agent (text-only) | 4–5 wks | Coach that remembers and answers in user's language |
| 3     | Graph RAG + Vision features          | Neo4j, MediaPipe, Vision Agent, food photo, form analysis   | 4–6 wks  | Multi-hop causal reasoning + photo-based meal logging     |
| 4     | MCP data unification + Recovery      | MCP servers (Strava, Health, Calendar, Weather), Recovery Agent | 4–6 wks | Cross-domain coaching ("HRV down, swap workouts")    |
| 5     | Multi-agent + adaptive planning      | A2A topology, Supervisor pattern, Scheduler Agent, race prep, weekly revision loop | 6–8 wks | Plans that revise themselves like Future ($199/mo coach app) |
| 6     | Voice + ambient (optional)           | OpenAI Realtime or Gemini Live                              | 4–6 wks  | Talk to your coach mid-workout                            |

Total: **6–12 months of evening work**, not a weekend project.

### Phase-by-phase deliverables

#### Phase 1 — Agent + Vector RAG (4–6 weeks)

Detailed walkthrough in **Part 4** below. Headline: replace one-shot Gemini call with an LLM agent that has access to user history and a science corpus, structured as a LangGraph state machine, all traced in Langfuse.

#### Phase 2 — Memory + chat + Multilingual RAG (4–5 weeks)

- `chat-service` with WebSocket streaming
- `user_facts` table → extract / write facts each turn (Mem0 once outgrown)
- Swap embedding model to BGE-M3 or Cohere multilingual-v3.0
- Ingest non-English science sources
- Frontend: MUI chat panel + chip-prompt suggestions
- First version of Nutrition Agent (text-only food logging)

#### Phase 3 — Graph RAG + Vision (4–6 weeks)

- Neo4j knowledge graph; manually seed ~200 nodes / ~500 edges
- LLM-driven edge extraction over corpus; human-review queue
- `graph_traverse` tool wired to the planner
- Vision Agent: food photo → calories (per Part 2.7); workout video → form (per Part 2.8)
- MinIO/S3 object storage + signed-URL upload flow
- Frontend: camera component, meal log review screen

#### Phase 4 — MCP data unification + Recovery Agent (4–6 weeks)

- MCP servers: Strava, Apple Health / Google Health Connect, Google Calendar, Weather
- Each requires OAuth flow in a "Connections" page in frontend
- Recovery Agent computes daily recovery score; raises flags
- USDA FoodData Central import for nutrition accuracy boost

#### Phase 5 — Multi-agent + adaptive planning (6–8 weeks)

- Decompose into Supervisor + 5 specialists per Part 2.3
- A2A protocol for inter-agent messaging (or LangGraph supervisor messaging if A2A is still unstable)
- Scheduler Agent integrating with Google Calendar
- Weekly cron: Coach Agent + Recovery Agent run the adaptive plan revision loop
- Race prep feature per Part 2.9

#### Phase 6 — Voice + ambient (4–6 weeks, optional)

- Gemini Live or OpenAI Realtime integration
- Phone audio in/out; agent has full context, recent training, route plan, target pace
- Use case: *"You're 3 seconds per km slower than threshold. HR looks fine. Push the next km."*

### Suggested first 30 days

1. Stand up `agent-service` skeleton (Python, FastAPI). Replace existing one-shot Gemini call with a Gemini function-calling agent that has access to `get_user_history`.
2. Add pgvector to existing Postgres.
3. Build smallest possible science corpus (10–20 documents) to prove Vector RAG works.
4. Implement the LangGraph specialist topology (PLANNER → retrieval branches → SYNTHESIZER → CRITIC → MEM_WRITE) — even with one tool, the structure is what matters.
5. Wire Langfuse — every LLM call must produce a trace.
6. Sketch the Graph RAG schema on paper: 20–30 core nodes, 50–100 edges. Don't build it yet, just prove the design. This makes Phase 3 cheap.
7. Ship internally. Use it on your own training for two weeks. Note where it's wrong, vague, or repetitive. Fix those, then plan Phase 2.

---

# Part 4 — How to start: Phase 1 step by step

Phase 1 is the deep-detail section. Phases 2–6 are extensions of these foundations and won't need beginner-level instructions by the time you reach them.

**What you'll build**: a new `agent-service` (Python, FastAPI) that:
1. Takes an activity from the gateway
2. Asks Gemini to analyze it
3. Lets Gemini call tools to fetch user history and search a sports-science corpus
4. Returns a grounded, personalized response

Estimated time: **4–6 weeks of evening work**.

## Prerequisites

You should already have:
- Python 3.11+ installed (verify: `python --version`)
- PostgreSQL running on `localhost:5432` (your user-service uses it)
- The full fitness-microservices project running locally
- An IDE: VS Code recommended

You'll add during this guide:
- A **Gemini API key** (Google AI Studio). Free tier covers everything in Phase 1.
- The `pgvector` extension installed into Postgres.
- A **Langfuse** account (free tier).

### Getting the API keys

**Gemini (Google AI Studio):**
1. Go to https://aistudio.google.com/app/apikey
2. Sign in with a Google account
3. Click "Create API key"
4. Copy it (this is the same `${GEMINI_KEY}` your existing `ai-service` uses — can reuse)
5. Free tier limits: 15 requests/minute and 1500 requests/day for `gemini-2.5-flash`

**Langfuse:**
1. Go to https://cloud.langfuse.com → sign up
2. New Project → name it "fitness-agent"
3. Settings → API Keys → Create new
4. Copy the public and secret keys

## Step 1 — Create the service folder

```bash
mkdir agent-service
cd agent-service
```

## Step 2 — Set up a Python virtual environment

```bash
python -m venv .venv
```

Activate:
```powershell
# PowerShell
.\.venv\Scripts\Activate.ps1
```
```bash
# Git Bash / WSL
source .venv/Scripts/activate
```

When activated you'll see `(.venv)` in your prompt.

**Checkpoint:** `python -c "import sys; print(sys.executable)"` should point to a path inside `agent-service/.venv/`.

## Step 3 — Install first packages

`requirements.txt`:

```text
google-genai==0.3.0
fastapi==0.115.0
uvicorn==0.32.0
python-dotenv==1.0.1
httpx==0.27.0
```

```bash
pip install -r requirements.txt
```

- `google-genai` — Gemini SDK (the new unified one, replaces older `google-generativeai`)
- `fastapi` — web framework
- `uvicorn` — server that runs FastAPI
- `python-dotenv` — loads `.env`
- `httpx` — HTTP client for calling other microservices

## Step 4 — Environment variables

`agent-service/.env`:

```text
GEMINI_API_KEY=your-gemini-key-here
LANGFUSE_PUBLIC_KEY=pk-lf-your-key
LANGFUSE_SECRET_KEY=sk-lf-your-key
LANGFUSE_HOST=https://cloud.langfuse.com

ACTIVITY_SERVICE_URL=http://localhost:8082
USER_SERVICE_URL=http://localhost:8081

POSTGRES_PASSWORD=your-postgres-password
```

`agent-service/.gitignore`:

```text
.venv/
.env
__pycache__/
*.pyc
```

## Step 5 — Hello, Gemini

`hello.py`:

```python
import os
from dotenv import load_dotenv
from google import genai

load_dotenv()

client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"))

response = client.models.generate_content(
    model="gemini-2.5-flash",
    contents="In one sentence: what is interval training?",
)

print(response.text)
```

```bash
python hello.py
```

Should print one sentence.

**Checkpoint reached:** you can talk to Gemini.

> **Model choice**: `gemini-2.5-flash` is the sweet spot — supports function calling, fast, free-tier-friendly. After Phase 1 you can experiment with `gemini-2.5-pro` (better reasoning, smaller free quota) or `gemini-2.5-flash-lite` (cheapest).

## Step 6 — The simplest possible web service

`main.py`:

```python
import os
from fastapi import FastAPI
from pydantic import BaseModel
from dotenv import load_dotenv
from google import genai
from google.genai import types

load_dotenv()

app = FastAPI()
client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"))


class AnalyzeRequest(BaseModel):
    activity_type: str
    duration_minutes: int
    calories_burned: int


@app.post("/api/agent/analyze")
def analyze(req: AnalyzeRequest):
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=(
            f"I just did {req.duration_minutes} minutes of "
            f"{req.activity_type} and burned {req.calories_burned} calories. "
            "How was the session?"
        ),
        config=types.GenerateContentConfig(
            system_instruction="You are a fitness coach. Give brief, practical advice.",
            max_output_tokens=500,
        ),
    )
    return {"analysis": response.text}
```

Run it:
```bash
uvicorn main:app --reload --port 8084
```

Test:
```bash
curl -X POST http://localhost:8084/api/agent/analyze \
  -H "Content-Type: application/json" \
  -d '{"activity_type":"running","duration_minutes":45,"calories_burned":520}'
```

**Checkpoint reached:** you have a working microservice that talks to Gemini. Functionally equivalent to your current ai-service, just structured for what's coming next.

## Step 7 — Understand "tool use"

Until now, Gemini only knows what's in the prompt. We want it to look up information. That's tool use.

You define a tool by writing a Python function with a docstring and type hints. Gemini reads that description and decides if/when to call it. When it does, you run the function in Python and pass the result back. Gemini continues with the new information.

> Gemini's SDK auto-extracts schema from Python function signatures + docstrings. Anthropic and OpenAI make you write JSON Schema by hand. This means the Gemini version is *less code* than the Claude or GPT version would be.

The conversation loop:

```
User: "How was my session compared to last week?"
   |
   v
Gemini: "I need to fetch their history" -> calls get_user_history()
   |
   v
Your code runs get_user_history() and returns the data
   |
   v
Gemini: "Now I have what I need. Here's the answer..."
```

That's an agent. It's not magic — a loop where you give Gemini functions and let it decide when to use them.

## Step 8 — Define your first tool

Start with fake data to verify wiring:

```python
def get_user_history(user_id: str, days: int = 30) -> list:
    """Get the user's recent fitness activities.

    Returns a list of activities, each with type, duration in minutes, and date.

    Args:
        user_id: The user's ID.
        days: How many days back to look. Defaults to 30.
    """
    # Fake data for now — replace with real API call in Step 9
    return [
        {"type": "RUNNING", "duration": 45, "date": "2026-05-10"},
        {"type": "CYCLING", "duration": 60, "date": "2026-05-08"},
        {"type": "RUNNING", "duration": 30, "date": "2026-05-06"},
    ]
```

**The docstring is the tool description** that Gemini reads. Write it as if for a teammate.

Rewrite the endpoint as an **agent loop**:

```python
@app.post("/api/agent/analyze")
def analyze(req: AnalyzeRequest):
    contents = [
        types.Content(
            role="user",
            parts=[types.Part(text=(
                f"User ID: {req.user_id}. I just did {req.duration_minutes} min of "
                f"{req.activity_type}, burned {req.calories_burned} cal. "
                "Compare it to my recent training."
            ))],
        )
    ]

    config = types.GenerateContentConfig(
        system_instruction=(
            "You are a fitness coach. Use tools to ground your advice in the user's data. "
            "When you have enough information, give a brief, practical analysis."
        ),
        tools=[get_user_history],
        automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        max_output_tokens=1000,
    )

    while True:
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=contents,
            config=config,
        )

        parts = response.candidates[0].content.parts
        function_call = next((p.function_call for p in parts if p.function_call), None)

        # No more tool calls — we have the final answer
        if not function_call:
            return {"analysis": response.text}

        # Gemini wants to call a tool — run it
        if function_call.name == "get_user_history":
            result = get_user_history(**dict(function_call.args))
        else:
            result = {"error": f"Unknown tool: {function_call.name}"}

        contents.append(response.candidates[0].content)
        contents.append(types.Content(
            role="user",
            parts=[types.Part(function_response=types.FunctionResponse(
                name=function_call.name,
                response={"result": result},
            ))],
        ))
```

Update the request model:

```python
class AnalyzeRequest(BaseModel):
    user_id: str
    activity_type: str
    duration_minutes: int
    calories_burned: int
```

> Why disable auto function calling? Gemini's SDK can run your Python functions automatically. That's convenient later but hides the loop. The loop is what you want to understand.

**Checkpoint reached:** your first agent — an LLM using a tool.

## Step 9 — Connect to your real backend

Replace fake data with a real HTTP call. Keep signature and docstring identical:

```python
import httpx

ACTIVITY_SERVICE_URL = os.getenv("ACTIVITY_SERVICE_URL")


def get_user_history(user_id: str, days: int = 30) -> list:
    """Get the user's recent fitness activities.

    Returns a list of activities, each with type, duration in minutes, and date.

    Args:
        user_id: The user's ID.
        days: How many days back to look. Defaults to 30.
    """
    response = httpx.get(
        f"{ACTIVITY_SERVICE_URL}/api/activities",
        headers={"X-User-ID": user_id},
        timeout=10.0,
    )
    response.raise_for_status()
    return response.json()
```

Restart uvicorn. Make sure activity-service is running. Test — the agent now sees your real activity data.

## Step 10 — Install pgvector

Connect to Postgres:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Verify:
```sql
SELECT extname FROM pg_extension WHERE extname = 'vector';
```

> If `CREATE EXTENSION` fails saying it doesn't exist, you need the binary. On Windows with official Postgres, download pgvector from https://github.com/pgvector/pgvector/releases and copy files into Postgres `lib/` and `share/extension/`. Most annoying step — once done, never again.

Create the corpus table:

```sql
CREATE TABLE science_chunks (
    id SERIAL PRIMARY KEY,
    source TEXT NOT NULL,
    text TEXT NOT NULL,
    embedding vector(768),  -- 768 = dimension of Gemini text-embedding-004
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX ON science_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

> If you upgrade to `gemini-embedding-001` later (higher quality, 3072 dim), recreate with `vector(3072)` and re-embed.

## Step 11 — Embedding pipeline

Add packages:

```text
# requirements.txt
psycopg2-binary==2.9.10
pypdf==5.0.0
```

```bash
pip install -r requirements.txt
```

No new API key — Gemini's embedding model is part of the same Gemini API. Same `GEMINI_API_KEY`. Free tier covers thousands of embeddings.

`ingest.py`:

```python
import os
from pathlib import Path
from dotenv import load_dotenv
from google import genai
from google.genai import types
from pypdf import PdfReader
import psycopg2

load_dotenv()

client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"))
db = psycopg2.connect(
    host="localhost", port=5432,
    user="postgres", password=os.getenv("POSTGRES_PASSWORD"),
    dbname="fitness-demo-user",
)


def chunk_text(text: str, size: int = 800, overlap: int = 100) -> list[str]:
    chunks = []
    start = 0
    while start < len(text):
        chunks.append(text[start:start + size])
        start += size - overlap
    return chunks


def embed(text: str, task_type: str = "RETRIEVAL_DOCUMENT") -> list[float]:
    """Embed a single string. task_type tells Gemini whether this is a stored
    document or a search query — slightly different vectors for each."""
    response = client.models.embed_content(
        model="text-embedding-004",
        contents=text,
        config=types.EmbedContentConfig(task_type=task_type),
    )
    return response.embeddings[0].values


def ingest_pdf(pdf_path: Path):
    print(f"Ingesting {pdf_path.name}...")
    reader = PdfReader(pdf_path)
    full_text = "\n".join(page.extract_text() or "" for page in reader.pages)
    chunks = chunk_text(full_text)
    cursor = db.cursor()
    for chunk in chunks:
        vector = embed(chunk, task_type="RETRIEVAL_DOCUMENT")
        cursor.execute(
            "INSERT INTO science_chunks (source, text, embedding) VALUES (%s, %s, %s)",
            (pdf_path.name, chunk, vector),
        )
    db.commit()
    print(f"  inserted {len(chunks)} chunks")


if __name__ == "__main__":
    corpus_dir = Path("corpus")
    for pdf in corpus_dir.glob("*.pdf"):
        ingest_pdf(pdf)
```

Create `agent-service/corpus/` and drop 5–10 PDFs in (ACSM position statements, open-access papers, public training guides). **Do not commit copyrighted material.**

```bash
python ingest.py
```

Verify:
```sql
SELECT source, count(*) FROM science_chunks GROUP BY source;
```

> About `task_type`: Gemini's embedding model is asymmetric. Documents use `RETRIEVAL_DOCUMENT`, queries use `RETRIEVAL_QUERY`. Set this correctly now and forget about it.

**Checkpoint reached:** you have a searchable knowledge base.

## Step 12 — Add a vector search tool

Move `embed()` to its own file so both `ingest.py` and `main.py` can use it.

`embeddings.py`:

```python
import os
from dotenv import load_dotenv
from google import genai
from google.genai import types

load_dotenv()
_client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"))


def embed(text: str, task_type: str = "RETRIEVAL_DOCUMENT") -> list[float]:
    response = _client.models.embed_content(
        model="text-embedding-004",
        contents=text,
        config=types.EmbedContentConfig(task_type=task_type),
    )
    return response.embeddings[0].values
```

Update `ingest.py` to `from embeddings import embed`.

In `main.py`:

```python
import psycopg2
from embeddings import embed

db = psycopg2.connect(
    host="localhost", port=5432,
    user="postgres", password=os.getenv("POSTGRES_PASSWORD"),
    dbname="fitness-demo-user",
)


def search_corpus(query: str, top_k: int = 3) -> list:
    """Search the sports science corpus for relevant research.

    Returns the top matches, each with the source document, text passage,
    and similarity score (0 to 1, higher = more relevant).

    Args:
        query: What to search for, in natural language.
        top_k: How many results to return. Defaults to 3.
    """
    vector = embed(query, task_type="RETRIEVAL_QUERY")  # query side — asymmetric
    cursor = db.cursor()
    cursor.execute(
        """
        SELECT source, text, 1 - (embedding <=> %s::vector) AS similarity
        FROM science_chunks
        ORDER BY embedding <=> %s::vector
        LIMIT %s
        """,
        (vector, vector, top_k),
    )
    return [
        {"source": row[0], "text": row[1], "similarity": float(row[2])}
        for row in cursor.fetchall()
    ]
```

`<=>` is pgvector's cosine distance. `1 - distance` = similarity (1.0 identical, 0.0 unrelated).

Add to config:

```python
config = types.GenerateContentConfig(
    system_instruction=(
        "You are a fitness coach. Use tools to ground your advice in the user's data "
        "and the science corpus. When you have enough information, give brief, practical advice."
    ),
    tools=[get_user_history, search_corpus],   # <- two tools
    automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
    max_output_tokens=1000,
)
```

Handle the new tool in the loop:

```python
if function_call.name == "get_user_history":
    result = get_user_history(**dict(function_call.args))
elif function_call.name == "search_corpus":
    result = search_corpus(**dict(function_call.args))
else:
    result = {"error": f"Unknown tool: {function_call.name}"}
```

Restart, test. Gemini now decides per turn whether to fetch history, search literature, or both.

**Checkpoint reached: you have working Agentic RAG.** This is the heart of Phase 1.

## Step 13 — Langfuse tracing

Add Langfuse:

```text
# requirements.txt
langfuse==2.55.0
```

```bash
pip install -r requirements.txt
```

Langfuse doesn't yet have a one-line auto-wrap for Gemini SDK. Use the `@observe` decorator + manual logging:

```python
from langfuse.decorators import observe, langfuse_context


@app.post("/api/agent/analyze")
@observe()
def analyze(req: AnalyzeRequest):
    contents = [...]  # same as before

    while True:
        langfuse_context.update_current_observation(
            input=str(contents),
            model="gemini-2.5-flash",
        )

        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=contents,
            config=config,
        )

        langfuse_context.update_current_observation(
            output=response.text if response.text else "<tool call>",
        )

        # ... rest of loop unchanged
```

Restart, do an end-to-end test. Go to https://cloud.langfuse.com → your project → Traces. You'll see every call and tool execution as a tree.

> Once on LangGraph in Step 14, switch to `CallbackHandler` (`from langfuse.callback import CallbackHandler`) which auto-captures everything LangChain does. The manual approach is temporary.

**Checkpoint reached:** you have observability. Don't ship without this.

## Step 14 — Move to LangGraph

The `while True` loop works but gets hard to extend. LangGraph expresses the same idea as a graph of nodes.

```text
# requirements.txt
langgraph==0.2.40
langchain-google-genai==2.0.0
```

```bash
pip install -r requirements.txt
```

Move tools to their own file. `tools.py`:

```python
import os
import httpx
import psycopg2
from dotenv import load_dotenv
from embeddings import embed

load_dotenv()
ACTIVITY_SERVICE_URL = os.getenv("ACTIVITY_SERVICE_URL")

_db = psycopg2.connect(
    host="localhost", port=5432,
    user="postgres", password=os.getenv("POSTGRES_PASSWORD"),
    dbname="fitness-demo-user",
)


def get_user_history(user_id: str, days: int = 30) -> list:
    """Get the user's recent fitness activities."""
    response = httpx.get(
        f"{ACTIVITY_SERVICE_URL}/api/activities",
        headers={"X-User-ID": user_id},
        timeout=10.0,
    )
    response.raise_for_status()
    return response.json()


def search_corpus(query: str, top_k: int = 3) -> list:
    """Search the sports science corpus for relevant research."""
    vector = embed(query, task_type="RETRIEVAL_QUERY")
    cursor = _db.cursor()
    cursor.execute(
        """
        SELECT source, text, 1 - (embedding <=> %s::vector) AS similarity
        FROM science_chunks
        ORDER BY embedding <=> %s::vector
        LIMIT %s
        """,
        (vector, vector, top_k),
    )
    return [
        {"source": row[0], "text": row[1], "similarity": float(row[2])}
        for row in cursor.fetchall()
    ]
```

`agent.py`:

```python
from typing import TypedDict, Annotated
from langgraph.graph import StateGraph, END
from langgraph.graph.message import add_messages
from langchain_google_genai import ChatGoogleGenerativeAI
from langchain_core.messages import ToolMessage

from tools import get_user_history, search_corpus

llm = ChatGoogleGenerativeAI(model="gemini-2.5-flash", max_output_tokens=1000)
llm_with_tools = llm.bind_tools([get_user_history, search_corpus])


class AgentState(TypedDict):
    messages: Annotated[list, add_messages]


def planner(state: AgentState):
    response = llm_with_tools.invoke(state["messages"])
    return {"messages": [response]}


def tool_executor(state: AgentState):
    last = state["messages"][-1]
    results = []
    for tool_call in last.tool_calls:
        if tool_call["name"] == "get_user_history":
            result = get_user_history(**tool_call["args"])
        elif tool_call["name"] == "search_corpus":
            result = search_corpus(**tool_call["args"])
        results.append(ToolMessage(content=str(result), tool_call_id=tool_call["id"]))
    return {"messages": results}


def should_continue(state: AgentState) -> str:
    last = state["messages"][-1]
    return "tools" if last.tool_calls else END


graph = StateGraph(AgentState)
graph.add_node("planner", planner)
graph.add_node("tools", tool_executor)
graph.set_entry_point("planner")
graph.add_conditional_edges("planner", should_continue, {"tools": "tools", END: END})
graph.add_edge("tools", "planner")

agent = graph.compile()
```

Replace `main.py` analyze endpoint:

```python
from agent import agent
from langchain_core.messages import HumanMessage, SystemMessage
from langfuse.callback import CallbackHandler

langfuse_handler = CallbackHandler()


@app.post("/api/agent/analyze")
def analyze(req: AnalyzeRequest):
    messages = [
        SystemMessage(content=(
            "You are a fitness coach. Use tools to ground your advice. "
            "Be brief and practical."
        )),
        HumanMessage(content=(
            f"User ID: {req.user_id}. I just did {req.duration_minutes} min of "
            f"{req.activity_type}, burned {req.calories_burned} cal. "
            "Compare it to my recent training and cite the literature."
        )),
    ]
    result = agent.invoke(
        {"messages": messages},
        config={"callbacks": [langfuse_handler]},
    )
    return {"analysis": result["messages"][-1].content}
```

Same behaviour, cleaner structure, Langfuse auto-captures everything via one-line callback. Adding a CRITIC node or MEM_WRITE node becomes a one-line graph edit later.

> `langchain-google-genai` makes the agent LLM-agnostic. To try Claude or GPT later, swap one import.

## Step 15 — Plug into the gateway

In `configserver/.../gateway-service.yml`:

```yaml
- id: agent-service
  uri: http://localhost:8084
  predicates:
    - Path=/api/agent/**
```

For Phase 1, bypass Eureka and let the gateway hit `agent-service` directly. Add Eureka registration later when you want it load-balanced.

Restart the gateway. Frontend can now call `http://localhost:8080/api/agent/analyze` and the gateway proxies to your Python service.

## Step 16 — Switch the frontend over

In `fitness-frontend/src/services/api.js`:

```js
export const analyzeActivity = (activity) => api.post('/agent/analyze', activity);
```

In your form / activity submission flow, call `analyzeActivity` and display the response. Done — your frontend uses the agent.

Keep the legacy `ai-service` running in parallel for comparison until you trust the new one.

### What Phase 1 leaves you with

A working `agent-service` that:
- Takes a user activity
- Lets Gemini decide whether to fetch history, search the corpus, or both
- Returns a grounded answer
- Records every step in Langfuse
- Is structured as a LangGraph state machine, ready to extend in Phase 2
- Costs $0 on the free tier for dev usage

Use it on your own training for two weeks. You'll learn more about Phase 2 from real usage than from re-reading this guide.

---

# Part 5 — Reference

## 5.1 Glossary

| Term | Meaning |
|------|---------|
| **LLM** (Large Language Model) | The AI that generates text. We use Gemini (Google) for its free tier. |
| **Prompt** | What you send to the LLM. Includes user question + any context. |
| **System prompt** | Persistent instruction telling the LLM how to behave. |
| **Tool use / function calling** | The LLM can call your Python functions. You describe what each function does; the LLM decides when to call. |
| **Agent** | LLM in a loop. Can call tools, see results, decide next action, keep going. |
| **RAG** | Retrieval-Augmented Generation. Fetch relevant docs at runtime and put them in the prompt. Reduces hallucinations. |
| **Agentic RAG** | RAG where the LLM decides *when* and *what* to retrieve, refines queries iteratively, self-critiques. Not a fixed retrieve-then-generate pipeline. |
| **Embedding** | Turning text into a list of numbers (vector). Similar texts produce similar vectors. |
| **Vector database** | Database optimized for "find most similar vector." |
| **pgvector** | Postgres extension that makes Postgres act as a vector DB. |
| **Graph RAG** | RAG over a knowledge graph instead of vectors. Handles multi-hop causal reasoning. |
| **Multilingual RAG** | RAG with cross-lingual embeddings, so a corpus in many languages is searchable as one. |
| **LangGraph** | Python library for building agents as state machines. |
| **LangChain** | Older Python library. Used for utilities (loaders, splitters). Not the orchestrator. |
| **Langfuse** | Observability. Records every LLM call so you can debug. |
| **MCP** (Model Context Protocol) | Standard way for LLMs to talk to external tools. Phase 4 concern. |
| **A2A** (Agent-to-Agent) | Google's protocol for agent-to-agent messaging. Phase 5 concern. |

## 5.2 Technology mapping

| Technology                   | Role                                                                                                    | Verdict             |
|------------------------------|---------------------------------------------------------------------------------------------------------|---------------------|
| LLM with tool use            | Gemini 2.5 Flash for routing/tools; 2.5 Pro for hard reasoning                                          | Core                |
| Agentic RAG                  | LLM decides when/what to retrieve, refines iteratively, self-critiques                                  | Core differentiator |
| Vector RAG                   | Semantic retrieval via pgvector                                                                          | Required baseline   |
| Graph RAG                    | Knowledge-graph retrieval for multi-hop causal reasoning                                                | Strong differentiator |
| Multilingual RAG             | Cross-lingual embeddings (BGE-M3, Cohere multilingual)                                                  | Phase 2+            |
| Vector DB                    | pgvector (Phase 1+); Qdrant if needed later                                                             | Required            |
| Graph DB                     | Neo4j (Phase 3+); Postgres adjacency as fallback                                                        | Required for Graph RAG |
| LangGraph                    | Stateful agent orchestration                                                                            | Orchestrator        |
| LangChain                    | Retriever / splitter / loader utilities only                                                            | Limited / optional  |
| MCP                          | Wrap external data sources (Strava, Health, Calendar, Weather)                                          | Phase 4             |
| A2A                          | Standardized inter-agent messaging                                                                      | Phase 5             |
| Memory layer                 | Long-term user memory (Mem0 or pgvector + structured tables)                                            | Required            |
| Eval & observability         | Langfuse — every LLM call traced                                                                        | Day 1 requirement   |
| Streaming / voice            | OpenAI Realtime or Gemini Live                                                                          | Phase 6 — stretch   |

## 5.3 Common beginner mistakes

| Mistake                                                | Fix                                                                                                       |
|--------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Hardcoding API keys                                    | Always `.env` + `python-dotenv`. Never commit.                                                            |
| Shipping without tracing                               | Wire Langfuse from day one. Untraced agents are unmaintainable.                                           |
| Building everything before testing                     | Stop at every checkpoint and verify. Each is independent.                                                 |
| Adding too many tools at once                          | Start with 1. Get it working. Then add #2. Agents get confused with 8 tools day one.                       |
| Skipping the manual loop and jumping to LangGraph      | Don't. Build the manual loop first (Steps 7–9). LangGraph makes sense once you understand the structure. |
| Trying to use OpenAI/Anthropic/Gemini all at once      | Pick one for Phase 1.                                                                                     |
| Ingesting hundreds of PDFs before pipeline works       | Get 5 docs flowing end-to-end before scaling.                                                              |
| Indexing without a vector index                        | Cosine search without an index is slow on >10k rows. The `ivfflat` index in Step 10 is required.          |
| Letting cost surprise you                              | Free tier is enough for dev. Set billing alerts in Google Cloud if you upgrade.                            |
| No system prompt                                        | An agent without a system prompt drifts. Be explicit.                                                     |
| Marketing the food feature as "exact calories"         | It's ±25–35%. Say "estimated" everywhere in UX.                                                            |
| Premature multi-agent                                  | Ship a single agent before decomposing. #1 cause of agentic project failure.                              |

## 5.4 Learning resources

In priority order:

1. **Gemini function calling docs** — https://ai.google.dev/gemini-api/docs/function-calling
2. **DeepLearning.AI's "AI Agents in LangGraph"** — free, ~1 hour
3. **LangGraph docs, Quickstart + Multi-agent** — https://langchain-ai.github.io/langgraph/
4. **pgvector README** — https://github.com/pgvector/pgvector
5. **Gemini embeddings docs** — https://ai.google.dev/gemini-api/docs/embeddings — task types and dimensions
6. **Anthropic's "Building effective agents"** essay — provider-agnostic; best high-level explanation of agent patterns. https://www.anthropic.com/research/building-effective-agents
7. **Microsoft's GraphRAG repo** — read the README, look at the indexing pipeline. Don't try to use it yet.

## 5.5 When you're stuck

In order:

1. **Read the Langfuse trace.** Tells you exactly what was sent and received.
2. **Run the failing step in a Python REPL.** Isolate it from FastAPI.
3. **Add `print()` everywhere.** Don't be clever about debugging.
4. **Ask an LLM.** Paste your code into Gemini and ask "why isn't this calling the tool?" — surprisingly effective.
5. **Check the LangGraph trace for the exact prompt sent.** 90% of agent bugs are bad prompts.

## 5.6 Open questions deferred to later phases

These are deliberately unresolved. Decide when the phase that needs them arrives.

1. **Neo4j vs. Postgres adjacency** for the knowledge graph. Decide when the graph exceeds ~5000 edges.
2. **USDA FoodData Central licensing** for offline use. Public but ~2GB import. Worth it for nutrition accuracy.
3. **MediaPipe location.** Client-side WebAssembly for privacy, or server-side? Probably client-side for video.
4. **A2A protocol maturity.** Spec is moving. If unstable when Phase 5 lands, fall back to LangGraph supervisor messages (wire-compatible).
5. **Multilingual coverage breadth.** Top 10 languages? Top 5? Decide from user analytics in Phase 2.
6. **Vertex AI vs. AI Studio API** for production. Vertex doesn't use your data for training; AI Studio free tier does. Switch when privacy matters.

---

## Closing note

Don't try to do this in a weekend or even a month. Phase 1 alone is 4–6 weeks of evening work. The full roadmap is 6–12 months. The point is to learn the foundations deeply enough that Phases 2–6 are extensions, not new battles.

Build small, verify at each checkpoint, use Langfuse to debug, and use the system yourself before opening it to anyone else. Real usage on your own training will teach you more than any reading.

Good luck.
