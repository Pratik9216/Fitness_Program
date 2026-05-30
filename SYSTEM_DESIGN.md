# System Design — Multi-Agent Fitness Platform

This document specifies the architecture of the agentic version of the fitness platform. It assumes the [ROADMAP](ROADMAP.md) for *what* and the [IMPLEMENTATION](IMPLEMENTATION.md) guide for *how to start Phase 1*. This document specifies *how the finished system fits together* — the agent topology, A2A messaging, RAG layers, and the four core features (training, recovery, nutrition with food vision, race prep).

Scope is intentionally narrow: **fitness and immediately adjacent domains only**. Journaling, finance, mood tracking, and other general "personal growth" features are deferred to a possible v2.

---

## 1. High-level architecture

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
                            |                    |   routes to specialist
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

Existing Spring Boot services are kept as the **system of record** for users and activities. The `agent-service` is a separate Python service (FastAPI + LangGraph) that contains the agent layer and orchestrates them. It calls existing services via HTTP, just like the browser does.

---

## 2. Architectural decisions

| Decision                                  | Choice                                           | Rationale                                                                                                |
|-------------------------------------------|--------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| Agent topology                            | Supervisor + specialists                         | One Supervisor routes user requests; specialists talk back to Supervisor, not to each other. Easier to debug, fewer infinite loops. |
| Inter-agent protocol                      | A2A (Google's spec) framed by Supervisor         | Standardized message envelopes between agents. Supervisor enforces the topology.                         |
| Orchestration                             | LangGraph per agent + a top-level Supervisor graph | Each specialist is its own subgraph; Supervisor is the parent graph routing between them.              |
| LLM                                       | Gemini 2.5 Flash for routing & tool use; Gemini 2.5 Pro for hard reasoning | Free tier covers development; Flash is fast and good enough for ~90% of calls.                 |
| Vision                                    | Gemini 2.5 Flash native multimodal               | No separate vision model, no extra plumbing. Trades some accuracy vs. a specialized food model.          |
| Long-term memory                          | Postgres `user_facts` table + Mem0 once outgrown | Start simple; upgrade only when flat strings become limiting.                                            |
| Vector store                              | pgvector on the existing Postgres                | No new infrastructure for Phase 1; Qdrant if scale demands it later.                                     |
| Knowledge graph                           | Neo4j                                            | Mature, Cypher is well-documented, Docker image exists. Postgres adjacency tables are a fallback if you want zero-new-infra. |
| Service language                          | Python for agent layer                           | LangGraph, LangChain, Gemini SDK, Mem0, MediaPipe are all Python-first. Existing Java services stay Java. |
| Frontend                                  | React + Vite (existing)                          | Add a chat panel and an image upload component; everything else stays.                                   |
| Image storage                             | MinIO (local) / S3 (prod) with signed URLs       | Don't store images in Postgres or MongoDB. Use object storage from day one.                              |
| Authentication                            | Keycloak JWT (existing) propagated to agent-service | Single auth model across all services.                                                              |

---

## 3. Agent specifications

Each agent is a LangGraph subgraph with:
- A focused **system prompt** describing role and limits
- A **toolset** (Python functions + MCP tools)
- A **memory scope** (what facts it reads/writes)
- A **data ownership** boundary (which collections / tables it can write to)

### 3.1 Supervisor Agent

**Purpose:** Front door for every user request. Decides which specialist(s) handle it and stitches the final response.

**System prompt sketch:**
```
You are the routing supervisor for a fitness coaching platform.
You do not answer fitness questions yourself; you route to the right
specialist. Available specialists:
  - Coach        for training plans, workout analysis, performance Qs
  - Recovery     for sleep, HRV, soreness, training-load concerns
  - Nutrition    for diet, food logging (incl. photos), macros, fueling
  - Vision       for workout form analysis from video
  - Scheduler    for booking workouts to calendar
Output: one or more A2A messages targeting specialists, then a final
synthesized reply to the user once they respond.
```

**Tools:** `route_to(agent, message)`, `synthesize_final_response(responses)`. That's it. No domain knowledge.

**LangGraph topology (parent graph):**
```
START -> CLASSIFY -> ROUTE -> [Coach | Recovery | Nutrition | Vision | Scheduler]
                                       |
                                       v
                                  AGGREGATE -> CRITIC -> END
```

### 3.2 Coach Agent

**Purpose:** Training plans, workout analysis, performance questions. Owns the user's *active training plan*.

**Tools:**
- `get_user_history(user_id, days)` — calls activity-service
- `vector_search_corpus(query)` — sports-science RAG
- `graph_traverse(start_concept, relations, max_hops)` — knowledge-graph queries
- `compute_training_load(user_id)` — ATL/CTL/TSS calculation
- `get_user_facts(user_id)` — pulls long-term memory (goals, injuries, preferences)
- `update_training_plan(user_id, plan)` — writes to Postgres `training_plans`
- `request_recovery_check(user_id)` — sends an A2A message to Recovery Agent for an opinion before committing a hard workout

**Data ownership:** writes `training_plans`, `training_plan_revisions` in Postgres. Reads everything else.

### 3.3 Recovery Agent

**Purpose:** Aggregates sleep, HRV, soreness, training load. Raises flags. Has veto power on hard sessions when load is too high.

**Tools:**
- `get_hrv(user_id, days)` — Apple Health / Whoop MCP
- `get_sleep(user_id, days)` — same MCP
- `get_subjective_soreness(user_id)` — reads `recovery_log` in Postgres
- `compute_training_load(user_id)` — shared with Coach
- `compute_recovery_score(user_id)` — composite of HRV ratio, sleep debt, soreness
- `vector_search_corpus(query)`
- `graph_traverse(...)`

**Data ownership:** writes `recovery_log` (manual subjective entries), `recovery_scores` (computed daily).

### 3.4 Nutrition Agent

**Purpose:** Food logging (manual and via photo), macro tracking, fueling around workouts.

**Tools:**
- `log_meal(user_id, items, calories, protein, carbs, fat, source)` — writes to Postgres
- `get_recent_meals(user_id, days)` — reads same
- `compute_daily_intake(user_id, date)` — aggregates
- `vector_search_corpus(query)` — for sports-nutrition research
- `graph_traverse(...)` — for nutrient-relationship reasoning
- `request_vision_analysis(image_id)` — A2A call to Vision Agent for food-photo analysis

**Data ownership:** writes `meals`, `meal_items`.

### 3.5 Vision Agent

**Purpose:** Image and video analysis. Two distinct subtasks:

1. **Food image → estimated nutrition** (called by Nutrition Agent)
2. **Workout video → form critique** (called directly via supervisor when user requests)

**Tools:**
- `analyze_food_image(image_url)` — Gemini Vision call, structured output
- `analyze_workout_video(video_url, exercise_type)` — MediaPipe pose extraction + Gemini Vision
- `lookup_nutrition_db(food_name)` — fall back to USDA FoodData Central for canonical values when the LLM is unsure

**Data ownership:** writes nothing directly. Returns structured results; the calling agent persists them.

See [Section 7](#7-feature--food-photo--calorie-estimation) for the food pipeline in detail.

### 3.6 Scheduler Agent

**Purpose:** Translates Coach Agent's plan into actual calendar entries. Watches for conflicts (meetings, travel) and proposes shifts.

**Tools:**
- `read_calendar(user_id, range)` — Google Calendar MCP
- `create_event(user_id, event)` — Calendar MCP
- `find_free_slot(user_id, duration, preferences)` — local logic over calendar data
- `request_plan_revision(user_id, conflict)` — A2A to Coach Agent when conflicts can't be resolved by shifting

**Data ownership:** No DB ownership. Calendar is the source of truth.

---

## 4. Inter-agent communication (A2A)

A2A is Google's open protocol for agent-to-agent messages. We use it inside `agent-service` between Supervisor and specialists. From outside, the system looks like one HTTP API; A2A is an internal detail.

### Message envelope

Every inter-agent message follows the A2A spec shape:

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

1. **Supervisor is always the entry point.** External requests go to Supervisor.
2. **Specialists reply to Supervisor by default.** Reduces N-to-N messaging.
3. **Specialist-to-specialist messages are allowed** but only for tightly-scoped purposes (Nutrition asks Vision to analyze a photo; Coach asks Recovery for a load opinion). These count as "subroutine calls," not free conversation.
4. **No agent may call itself.** Loops are guarded by Supervisor.
5. **Every A2A message is traced** in Langfuse with cause-and-effect edges.

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

---

## 5. LangGraph orchestration

Each agent is its own compiled LangGraph; Supervisor is a parent graph that invokes specialist subgraphs via the standard LangGraph subgraph API.

### Supervisor graph

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

### Specialist subgraph (uniform shape)

Every specialist follows the same skeleton from the [ROADMAP's LangGraph topology](ROADMAP.md):

```
PLANNER -> [MEM_RECALL | TOOL_CALL | VECTOR_RAG | GRAPH_RAG]
            (parallel where possible)
        -> SYNTHESIZER -> CRITIC -> MEM_WRITE -> RETURN
```

### Conditional routing rules (Supervisor's CLASSIFY)

| User input contains                                         | Route to             |
|-------------------------------------------------------------|----------------------|
| "How was my run", "Compare to last week", training questions | Coach                |
| "I feel tired", "sleep", "HRV"                              | Recovery             |
| Image attachment + meal context                             | Nutrition (-> Vision)|
| "Macro", "protein", "carbs", "before / after workout food"  | Nutrition            |
| Video attachment + exercise mention                         | Vision               |
| "Schedule", "book", "calendar"                              | Scheduler            |
| Multi-domain (e.g. "Can I run hard tomorrow?")              | Coach + Recovery (parallel) |

CLASSIFY itself is one LLM call with the user message + a list of route options + a structured-output schema (Gemini JSON mode).

---

## 6. RAG architecture across agents

Three retrieval layers, all available to all specialists, but each agent uses different combinations.

### Vector RAG (semantic similarity)
- Stored in `science_chunks` (pgvector, dim 768)
- All agents query it via the shared `vector_search_corpus(query)` tool

### Graph RAG (causal traversal)
- Stored in Neo4j
- Used heavily by Coach and Recovery (injury reasoning, training-principle hierarchies)
- Used moderately by Nutrition (nutrient interactions)
- Rarely used by Vision or Scheduler

### Multilingual layer
- Embedding model is BGE-M3 once Phase 2 ships
- All ingested chunks (English, German, Italian, Japanese, Norwegian) share one vector space
- Output language follows user's input language detected by `langdetect`

### Memory layer
- `user_facts` table: structured facts about the user (goals, injuries, preferences)
- Conversation summaries: short-form summaries written after each session
- Shared across all agents — Coach's discovery about a user is available to Nutrition

---

## 7. Feature — food photo → calorie estimation

The headline new feature. Substantial detail because it's where accuracy expectations need calibration.

### User flow

```
1. User taps "Log meal" in app
2. Camera opens; user takes photo (or selects from library)
3. Optional: user adds a brief caption ("lunch") or a portion-size hint
4. Image uploaded to MinIO/S3; signed URL returned
5. Frontend POSTs {image_url, caption, meal_time} to /api/agent/log-meal
6. Supervisor -> Nutrition -> Vision (A2A)
7. Vision Agent calls Gemini 2.5 Flash with the image + a structured prompt
8. Response (structured JSON) returns to Nutrition Agent
9. Nutrition Agent persists to `meals` and `meal_items`
10. Frontend displays the parsed result; user can correct any item
11. Corrections feed back as fine-tuning signal (Phase 4+)
```

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

### Accuracy honesty

A claim of "exact calories" is **wrong** and we shouldn't make it. Realistic accuracy from a single Gemini Vision call on a typical phone photo:

- Food identification: 85–95% on common Western foods, drops below 70% on unusual cuisines, mixed dishes, and anything covered in sauce
- Portion estimation: typically ±30% error without a reference object, ±15% with one (utensil, hand)
- Hidden ingredients (oil, sugar, butter): the model can only see what's visible — adds another 10–20% uncertainty in mixed dishes
- Net result: expect **±25–35% error on total calories** for typical use

This is still useful for trend tracking but **must not be marketed as "exact."** Honest UX language:

> "Estimated 720 kcal. Tap to refine."

### Improvements over time

Phase 1 (Vision Agent shipping): single-shot Gemini Vision
Phase 2: add USDA FoodData Central lookups for items the LLM identifies confidently, replacing LLM-guessed macros with database values (much more accurate)
Phase 3: user corrections feed a personalized adjustment factor — if you consistently over-estimate by 15%, the system learns
Phase 4: optional barcode scanning (cheap accuracy boost for packaged foods)

### Image lifecycle (privacy)

- Uploaded image goes to MinIO/S3 with a signed URL valid 10 minutes
- After Vision Agent processes, image is **either deleted** (default) **or retained 30 days** (user opt-in for "improve my history")
- Original image is never sent to Gemini if user has opted out of model-training data sharing (use Gemini's paid tier or Vertex AI in that case)
- No biometric inference on faces; if a face appears, the prompt instructs the model to ignore it

### Cost (approximate, free tier vs. paid)

Each food-photo analysis:
- Free tier: $0, counts as one request against the daily quota (1500/day)
- Paid `gemini-2.5-flash`: ~$0.0005 per image
- Paid `gemini-2.5-pro` if you need it: ~$0.005 per image

At 50 active users logging 3 meals/day = 150 images/day. **Free tier covers it.** Paid would be ~$2.25/month.

---

## 8. Feature — workout form analysis from video

Companion vision feature. Same Vision Agent, different pipeline.

### User flow

1. User records a 5–15 second clip of an exercise (squat, deadlift, push-up, etc.)
2. Frontend uploads to MinIO/S3
3. Frontend hits `/api/agent/analyze-form` with `{video_url, exercise_type}`
4. Vision Agent processes:
   - Step 1: **MediaPipe Pose** locally extracts joint keypoints frame-by-frame (free, runs in Python with a single `pip install mediapipe`)
   - Step 2: Compute angles (knee, hip, back) at key moments (bottom of squat, lockout, etc.)
   - Step 3: Send a representative still frame + computed angles to Gemini Vision with an exercise-specific prompt
5. Response is a structured critique: what's good, what to fix, drills to practice
6. Coach Agent is optionally informed (so future workout suggestions account for form work needed)

### Why MediaPipe + Gemini (not Gemini alone)

Gemini Vision alone can describe a video but struggles to give specific joint-angle feedback. MediaPipe handles the geometry; Gemini handles the qualitative reasoning. Together they produce specific, actionable critique like *"knees collapse inward at the bottom of the squat — add banded squats 2x/week."*

### Privacy

Workout videos are more sensitive than food photos (body recognizable). Defaults:
- Stored only if user opts in
- Face blurring optional (MediaPipe can detect and blur faces before sending to LLM)
- No upload to Gemini at all if user disables the cloud-vision option (MediaPipe-only mode still gives geometric feedback, just not natural-language critique)

---

## 9. Feature — race / event preparation

A bounded "project mode" for an upcoming race or event. Activates 8–16 weeks out.

### User flow

1. User declares a race goal: *"Half-marathon, 2026-11-15, target 1:45"*
2. Coach Agent creates a `race_goal` record and a corresponding `training_plan` covering the weeks until race day
3. Plan automatically partitions into **base / build / peak / taper / race week** mesocycles
4. Every week, the adaptive-planning loop (see Phase 5 of the roadmap) revises next week based on actual training and recovery
5. Race week: agent triggers a **race-day briefing** — pacing strategy, fueling plan, hydration, weather check, sleep targets
6. Post-race: agent compares predicted vs. actual, asks for a debrief, persists lessons learned to memory

### Taper logic

Standard endurance-coaching taper rule, codified:
- 3 weeks out: 80% of peak weekly volume, intensity preserved
- 2 weeks out: 60% volume, one quality session
- Race week: 40% volume, very light shakeout 2 days before

Implemented as a simple function the Coach Agent calls; the LLM doesn't compute it from scratch each time. This is the right boundary: deterministic math for known patterns, LLM for judgment calls.

### Race-day briefing format

```
Race day prep — Half-marathon, 2026-11-15

Weather (forecast 24h out): 12°C, light wind, no rain. Good conditions.

Pacing target: 4:58/km for 1:45 finish.
- First 5K: 5:02-5:05/km (intentionally controlled — most blow-ups happen here)
- 5-15K:    4:55-5:00/km (race pace, settle in)
- Last 6K:  open up if energy allows; OK to drift to 4:50 last 3K

Fueling: gel at 45 min, 75 min, optional at 100 min.
Hydration: sip every aid station.

Sleep target: 7+ hours both Thu and Fri. Friday night sleep matters
slightly more than the night before, per the literature.

Two things to remember:
1. You consistently negative-split your long runs — trust that pattern.
2. Your HRV was high all week. Your body is ready.
```

---

## 10. Feature — recovery monitoring

Recovery Agent's day-to-day responsibility, but visible to the user as a single number plus a why.

### Inputs

- Sleep (via Apple Health / Whoop / Garmin MCP)
- HRV (same)
- Resting heart rate (same)
- Subjective soreness (1–10 scale, user enters daily in 5 seconds)
- Computed training load (ATL/CTL/TSS from activity-service data)

### Recovery score

Composite 0–100, computed daily:

```
score = w1 * (HRV today / HRV 30-day baseline)
      + w2 * (sleep hours today / target)
      + w3 * (1 - normalized soreness)
      + w4 * (1 - acute:chronic ratio relative to 1.5 threshold)
```

Weights are tunable; defaults from peer-reviewed recommendations cited in the corpus.

### When agents intervene

- Score < 40: Coach Agent receives an A2A nudge and softens any hard sessions planned for next 24h
- Score < 25 for 3 consecutive days: triggers a "deload week" suggestion to Coach
- Subjective soreness spike + matching joint mentioned: surfaces to the user with a "want me to log this as an injury watch?" prompt

---

## 11. Data model

### PostgreSQL (existing + new)

| Table                  | Owned by         | Purpose                                            |
|------------------------|------------------|----------------------------------------------------|
| `users`                | user-service     | Account, keycloak_id, email, name                  |
| `user_facts`           | agent-service    | Long-term memory (goals, injuries, preferences)    |
| `training_plans`       | Coach Agent      | Active plan rows per user                          |
| `training_plan_revisions` | Coach Agent   | Audit log of plan changes                          |
| `race_goals`           | Coach Agent      | User-declared races / events                       |
| `recovery_log`         | Recovery Agent   | Daily subjective entries                           |
| `recovery_scores`      | Recovery Agent   | Computed daily score history                       |
| `meals`                | Nutrition Agent  | Per-meal aggregate (date, total kcal, source)      |
| `meal_items`           | Nutrition Agent  | Individual food items in a meal                    |
| `science_chunks`       | shared           | RAG corpus (pgvector)                              |

### MongoDB (existing)

| Collection         | Database                  | Purpose                                |
|--------------------|---------------------------|----------------------------------------|
| `activity`         | aiactivityfitness         | User-logged workouts                   |
| `recommendation`   | airecommendationfitness   | Legacy AI recommendations (deprecate)  |
| `agent_traces`     | new                       | Per-turn agent execution traces (optional, Langfuse handles primary) |

### Neo4j (new in Phase 3)

| Node types   | Edge types                                              |
|--------------|---------------------------------------------------------|
| `Muscle`     | `targets`, `is_synergist_of`                           |
| `Exercise`   | `targets`, `progresses_to`, `is_prerequisite_for`       |
| `Injury`     | `causes`, `aggravates`, `recovers_from`                |
| `Symptom`    | `indicates`, `often_caused_by`                          |
| `Nutrient`   | `aids_recovery_from`, `is_required_for`                |
| `Principle`  | `is_part_of`, `contradicts`                             |
| `Modality`   | `treats`, `complements`                                 |

### Object store (MinIO local, S3 prod)

| Bucket              | Contents                          | Lifecycle                          |
|---------------------|-----------------------------------|------------------------------------|
| `food-photos`       | Meal images                       | Default delete after processing; 30d if user opts in |
| `workout-videos`    | Form-check videos                 | Opt-in only; deletion on user request |

---

## 12. Security, PII, and image handling

### Authentication chain

```
Browser -> Keycloak (PKCE login) -> JWT
Browser + JWT -> Gateway (validates JWT against JWK set)
Gateway -> agent-service (forwards JWT + X-User-ID header)
agent-service -> Gemini API (uses platform key, never user key)
agent-service -> other Spring services (forwards JWT + X-User-ID)
```

agent-service validates the JWT locally too (defense in depth) — uses the same JWK set as the gateway.

### Image upload flow

1. Frontend requests `POST /api/uploads/sign` — gateway forwards to agent-service
2. agent-service generates a signed PUT URL valid 5 minutes, returns it
3. Browser uploads directly to MinIO/S3 (does not transit agent-service)
4. Agent-service receives only the image *ID*, never the bytes (except when it needs to pass to Gemini)
5. After processing, image is deleted (default) or moved to a `retained/` prefix (opt-in)

### PII handling

- Image bytes only leave the platform under two conditions: (a) sent to Gemini for analysis, (b) user opts in to retained storage
- No biometric / face recognition is performed
- All logs (Langfuse, app logs) redact email, location, full name; only `user_id` (UUID) is logged

### Rate limits per user

- 30 requests / minute to agent-service (covers chat, food photo, etc.)
- 10 image uploads / hour (vision is the expensive path)
- 1 workout-video analysis / hour (video processing is heaviest)

---

## 13. Cost model

Numbers are approximate. Assumes paid tier; free tier is $0 within quotas.

### Per active user per day, typical usage

| Action                     | Volume / day | Cost              | Subtotal |
|----------------------------|--------------|-------------------|----------|
| 1 activity logged + analysis | 1            | $0.002 (text)     | $0.002   |
| 5 chat turns               | 5            | $0.0015 each      | $0.0075  |
| 3 food photos              | 3            | $0.0005 each      | $0.0015  |
| 1 workout-video analysis (occasional) | 0.1   | $0.005 each       | $0.0005  |
| Recovery score recompute   | 1            | $0.001 (text)     | $0.001   |
| Embeddings (corpus growth) | shared       | negligible        | ~$0      |
| **Total**                  |              |                   | **~$0.013** |

At 1000 daily-active users: ~$13/day, ~$400/month in LLM costs. Cache aggressively (Gemini context caching, response caching on repeated queries) to halve this.

For development and small private use: free tier suffices for everything.

---

## 14. Deployment topology

```
Component              Port     Process type        Dependencies
-----------------------------------------------------------------
React frontend         5173     vite dev / static   gateway
gateway                8080     Spring Boot         eureka, config-server
config-server          8888     Spring Boot         (none)
eureka                 8761     Spring Boot         (none)
user-service           8081     Spring Boot         postgres, eureka, config
activity-service       8082     Spring Boot         mongodb, kafka, eureka, config
ai-service (legacy)    8083     Spring Boot         mongodb, kafka, eureka, config
agent-service          8084     Python / FastAPI    postgres, neo4j, minio, gemini
chat-service           8085     Python / FastAPI    agent-service, websocket
postgres               5432     Docker              -
mongodb                27017    Docker              -
neo4j                  7474/7687 Docker             -
kafka                  9092     Docker              zookeeper
keycloak               8181     Docker              postgres (separate DB)
minio                  9000/9001 Docker             -
langfuse               via cloud or self-host
```

Recommended for local dev: a single `docker-compose.yml` at the repo root that brings up postgres, mongodb, neo4j, kafka, keycloak, and minio. The Spring services and agent-service still run on the host machine for fast iteration.

---

## 15. Mapping to roadmap phases

| Phase       | What ships from this design                                   |
|-------------|---------------------------------------------------------------|
| Phase 1     | agent-service skeleton, Coach Agent only, Vector RAG, Langfuse |
| Phase 2     | Memory layer, chat-service, Multilingual RAG, Nutrition Agent (text-only food logging) |
| Phase 3     | Graph RAG (Neo4j), Vision Agent, **food photo feature**, form-analysis feature |
| Phase 4     | MCP integrations (Strava, Health, Calendar, Weather), Recovery Agent |
| Phase 5     | Scheduler Agent, A2A topology made explicit, Supervisor pattern, adaptive plan revision loop, **race-prep feature** |
| Phase 6     | Voice / ambient — out of scope for this design                 |

The vision-based features (food photo, form analysis) intentionally arrive in Phase 3 — they need Vision Agent infrastructure, image storage, and at least Vector RAG already in place. Trying to ship them in Phase 1 would skip too much foundation.

---

## 16. Open questions

These are deliberately unresolved. They should be answered by Phase 3, not Phase 1.

1. **Neo4j vs. Postgres adjacency tables for the knowledge graph.** Neo4j is the "real" answer; adjacency in Postgres is the "no new infra" answer. Decide when the graph exceeds ~5000 edges.
2. **USDA FoodData Central licensing for offline use.** Publicly available, but importing the full set is ~2GB. Worth it for nutrition accuracy.
3. **MediaPipe vs. server-side pose estimation.** MediaPipe can run client-side (browser WebAssembly) for privacy. Worth it? Probably yes for video; the LLM call is still server-side.
4. **A2A protocol maturity.** The spec is moving. If it's still unstable when Phase 5 lands, fall back to LangGraph supervisor messages (already wire-compatible) without changing public contracts.
5. **Multilingual coverage breadth.** Worth supporting the top 10 languages? Top 5? Decide based on user analytics in Phase 2.
