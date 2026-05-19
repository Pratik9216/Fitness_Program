# Implementation Guide — Beginner's Walkthrough

This document is the *how* for the [ROADMAP](ROADMAP.md). It assumes you know nothing about agents, RAG, LangGraph, embeddings, or vector databases. Every term is explained the first time it appears, every command is concrete, and there are small checkpoints along the way so you know you haven't broken anything.

The guide focuses on **Phase 1 in deep detail** — building a working AI agent that replaces your current one-shot Gemini call. Phases 2 through 6 are summarized at the end. By the time you reach them, you'll have enough context that beginner-level instructions stop being helpful.

---

## Part A — Glossary (read this first)

You'll see these words constantly. Skim now, refer back as needed.

| Term | Plain-language meaning |
|------|------------------------|
| **LLM** (Large Language Model) | The AI that generates text. We're using Gemini (Google) because its free tier is generous; Claude and GPT cost from the first call. |
| **Prompt** | Whatever you send to the LLM. Includes the user's question + any context you provide. |
| **System prompt** | A persistent instruction telling the LLM how to behave. e.g. "You are a fitness coach." |
| **Tool use / function calling** | The LLM can call your Python functions when it needs information. You describe what the function does; the LLM decides when to call it. |
| **Agent** | An LLM in a loop. It can call tools, see results, decide what to do next, and keep going until it's done. |
| **RAG** (Retrieval-Augmented Generation) | Instead of trusting the LLM's memory, you fetch relevant documents at runtime and include them in the prompt. Reduces hallucinations. |
| **Embedding** | Turning text into a list of numbers (a vector). Two texts that mean similar things produce similar vectors. |
| **Vector database** | A database optimized for "find me the most similar vector." |
| **pgvector** | A Postgres extension that turns your existing Postgres into a vector database. No new infrastructure needed. |
| **LangGraph** | A Python library for building agents as state machines (nodes + edges, like a flowchart). |
| **LangChain** | An older Python library. We mostly use it for utilities (loading PDFs, splitting text). |
| **Langfuse** | An observability tool. Records every LLM call so you can see what happened. |
| **MCP** (Model Context Protocol) | A standard way for LLMs to talk to external tools (Strava, Calendar, etc.). Phase 4 concern. |

---

## Part B — Prerequisites

You should already have, from this project:

- Python 3.11+ installed (verify: `python --version`)
- PostgreSQL running on `localhost:5432` (your user-service uses it)
- The full fitness-microservices project running locally
- An IDE: VS Code is recommended

You'll add during this guide:

- A **Gemini API key** (Google AI Studio). Free tier covers everything in Phase 1 — generous quotas on `gemini-2.5-flash` and `text-embedding-004`.
- The `pgvector` extension installed into Postgres.
- A **Langfuse** account (free tier).

### Getting the API keys

**Gemini (Google AI Studio):**
1. Go to https://aistudio.google.com/app/apikey
2. Sign in with a Google account
3. Click "Create API key"
4. Copy it somewhere safe — this is the same `${GEMINI_KEY}` your existing `ai-service` already uses, so you can reuse it
5. Free tier limits at the time of writing: 15 requests / minute and 1500 requests / day for `gemini-2.5-flash`, more than enough for development

**Langfuse:**
1. Go to https://cloud.langfuse.com
2. Sign up
3. New Project → name it "fitness-agent"
4. Settings → API Keys → "Create new API keys"
5. Copy the **public key** and **secret key**

---

## Part C — Phase 1 step by step

What you'll build in this section: a new microservice called `agent-service`, written in Python with FastAPI, that:

1. Takes an activity ID from the gateway
2. Asks Gemini to analyze it
3. Lets Gemini call tools to fetch the user's history and search a sports-science corpus
4. Returns a grounded, personalized response

Estimated time: 4–6 weeks of evening work, give or take.

---

### Step 1 — Create the service folder

Open a terminal at the repo root (`d:/Dev/Microservices/fitness-microservices`).

```bash
mkdir agent-service
cd agent-service
```

You'll work entirely in this folder for Phase 1.

---

### Step 2 — Set up a Python virtual environment

A virtual environment is a sandbox where Python packages install without polluting your global Python. Always use one.

```bash
python -m venv .venv
```

Activate it:

```powershell
# PowerShell
.\.venv\Scripts\Activate.ps1
```

```bash
# Git Bash / WSL
source .venv/Scripts/activate
```

When activated, you'll see `(.venv)` at the start of your prompt. Always run subsequent commands inside an activated venv.

**Checkpoint:** Run `python -c "import sys; print(sys.executable)"`. It should point to a path inside `agent-service/.venv/`.

---

### Step 3 — Install the first packages

Create a file `requirements.txt`:

```text
google-genai==0.3.0
fastapi==0.115.0
uvicorn==0.32.0
python-dotenv==1.0.1
httpx==0.27.0
```

Install them:

```bash
pip install -r requirements.txt
```

Brief explanation of each:
- `google-genai` — Gemini's Python SDK (the new unified one, replaces the older `google-generativeai`)
- `fastapi` — the web framework for our service
- `uvicorn` — the server that actually runs FastAPI
- `python-dotenv` — loads secrets from a `.env` file
- `httpx` — HTTP client for calling your other microservices

---

### Step 4 — Set up environment variables

Create `.env` in the `agent-service` folder:

```text
GEMINI_API_KEY=your-gemini-key-here
LANGFUSE_PUBLIC_KEY=pk-lf-your-key
LANGFUSE_SECRET_KEY=sk-lf-your-key
LANGFUSE_HOST=https://cloud.langfuse.com

ACTIVITY_SERVICE_URL=http://localhost:8082
USER_SERVICE_URL=http://localhost:8081
```

> Same key your existing `ai-service` uses for `${GEMINI_KEY}`. You can reuse it.

**Important — add `.env` to `.gitignore` immediately.** Create `.gitignore` in `agent-service/`:

```text
.venv/
.env
__pycache__/
*.pyc
```

The root `.gitignore` already covers `.env`, but having a local one is good defense.

---

### Step 5 — Hello, Gemini

Create `hello.py` to verify the API key works:

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

Run it:

```bash
python hello.py
```

You should see one sentence about interval training.

**Checkpoint reached:** you can talk to Gemini. This is the foundation.

> **Model choice**: `gemini-2.5-flash` is the sweet spot for this guide — supports function calling, fast, and free-tier friendly. Once you ship, you can experiment with `gemini-2.5-pro` (better reasoning, lower free quota) or `gemini-2.5-flash-lite` (cheaper, faster, slightly less smart).

---

### Step 6 — The simplest possible web service

Create `main.py`:

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

Run the server:

```bash
uvicorn main:app --reload --port 8084
```

Open another terminal and test:

```bash
curl -X POST http://localhost:8084/api/agent/analyze \
  -H "Content-Type: application/json" \
  -d '{"activity_type":"running","duration_minutes":45,"calories_burned":520}'
```

You should get a JSON response with an `analysis` field.

**Checkpoint reached:** you have a working microservice that talks to Gemini. Functionally equivalent to your current ai-service, just structured for what's coming next.

---

### Step 7 — Understand "tool use"

Up to now, Gemini only knows what's in the prompt. We want it to be able to *look up* information. That's what tool use is for.

You define a tool by writing a Python function with a docstring and type hints. Gemini reads that description and decides if/when to call it. When it does, you run the function in Python and pass the result back. Gemini continues with the new information.

> Gemini's SDK can auto-extract the schema from a Python function (its docstring + type hints). Anthropic and OpenAI make you write the JSON Schema by hand. This makes the Gemini version *less* code than the Claude version would be.

The conversation loop becomes:

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

That's an agent. It's not magic — it's a loop where you give Gemini functions and let it decide when to use them.

---

### Step 8 — Define your first tool

Add a fake tool to `main.py`. We'll return hardcoded data first to verify the wiring before connecting to the real activity-service.

```python
def get_user_history(user_id: str, days: int = 30) -> list:
    """Get the user's recent fitness activities.

    Returns a list of activities, each with type, duration in minutes, and date.

    Args:
        user_id: The user's ID.
        days: How many days back to look. Defaults to 30.
    """
    # Fake data for now — we'll connect the real service in Step 9
    return [
        {"type": "RUNNING", "duration": 45, "date": "2026-05-10"},
        {"type": "CYCLING", "duration": 60, "date": "2026-05-08"},
        {"type": "RUNNING", "duration": 30, "date": "2026-05-06"},
    ]
```

The **docstring is the tool description** that Gemini reads. Write it as if you're explaining to a teammate — the LLM uses these words to decide when to call the function.

Now rewrite the endpoint to run an **agent loop**:

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

        # Append both the tool call and the tool result to the conversation
        contents.append(response.candidates[0].content)
        contents.append(types.Content(
            role="user",
            parts=[types.Part(function_response=types.FunctionResponse(
                name=function_call.name,
                response={"result": result},
            ))],
        ))
```

You'll also need to add `user_id` to the request model:

```python
class AnalyzeRequest(BaseModel):
    user_id: str
    activity_type: str
    duration_minutes: int
    calories_burned: int
```

> We pass `automatic_function_calling.disable=True` because Gemini's SDK can run your Python functions for you automatically. That's convenient *later* but hides the loop — and the loop is the thing you want to understand. We'll re-enable it once you've seen the manual version once.

Restart uvicorn and test again with a `user_id` field included. Now Gemini should call `get_user_history`, see the fake data, and reference it in the response.

**Checkpoint reached:** you have your first agent — an LLM that uses a tool. Everything else from here is adding more tools and orchestration.

---

### Step 9 — Connect the tool to your real backend

Replace the hardcoded data inside `get_user_history` with a real HTTP call to your activity-service. Keep the function signature and docstring exactly the same — Gemini doesn't need to know anything changed.

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

Restart uvicorn. Make sure your activity-service is running. Test again — the agent should now see your real activity data.

---

### Step 10 — Install pgvector

`pgvector` is an extension that makes Postgres act as a vector database. Connect to Postgres and run:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Verify:

```sql
SELECT extname FROM pg_extension WHERE extname = 'vector';
```

If it returns a row, you're set.

If `CREATE EXTENSION` fails saying it doesn't exist, you need to install the extension binary. On Windows with Postgres installed via the official installer, you may need to download pgvector from https://github.com/pgvector/pgvector/releases and copy the files into your Postgres `lib/` and `share/extension/` folders. (This is the most annoying step in this whole guide. Once done, you don't have to touch it again.)

Create a table for the science corpus:

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

The index makes similarity search fast. Don't worry about what `ivfflat` and `lists` mean yet — they're tuning knobs.

> If you later upgrade to `gemini-embedding-001` (the newer, higher-quality embedding model), it produces 3072-dim vectors — you'd recreate the table with `vector(3072)` and re-embed everything.

---

### Step 11 — Build the embedding pipeline

This is what takes your science PDFs / text files and gets them into the database as searchable vectors.

Install more packages:

```text
# add to requirements.txt
psycopg2-binary==2.9.10
pypdf==5.0.0
```

```bash
pip install -r requirements.txt
```

No new API key needed — Gemini's embedding model (`text-embedding-004`) is part of the same Gemini API. Same `GEMINI_API_KEY` you already have. Free tier covers thousands of embeddings.

Create `ingest.py`:

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
    """Split text into overlapping chunks of ~800 chars."""
    chunks = []
    start = 0
    while start < len(text):
        chunks.append(text[start : start + size])
        start += size - overlap
    return chunks


def embed(text: str, task_type: str = "RETRIEVAL_DOCUMENT") -> list[float]:
    """Embed a single string. task_type tells Gemini whether this is a stored
    document or a search query — the model produces slightly different vectors
    for each, which improves retrieval quality."""
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

> **About `task_type`**: Gemini's embedding model is "asymmetric" — vectors meant for storage use `RETRIEVAL_DOCUMENT`, vectors meant for searching use `RETRIEVAL_QUERY`. Using the same task type for both works but using the right one each side improves accuracy. Set this correctly now and forget about it.

Create a `corpus/` folder inside `agent-service/`. Drop 5–10 PDFs in there — start with ACSM position statements (free download), open-access papers from PubMed, public PDFs of training guides. **Do not commit copyrighted material.**

Run:

```bash
python ingest.py
```

Verify in Postgres:

```sql
SELECT source, count(*) FROM science_chunks GROUP BY source;
```

You should see your files and chunk counts.

**Checkpoint reached:** you have a searchable knowledge base.

---

### Step 12 — Add a vector search tool

Move the `embed()` helper out of `ingest.py` so both files can use it. Create `embeddings.py`:

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

Now in `main.py`, add the search tool:

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
    vector = embed(query, task_type="RETRIEVAL_QUERY")  # asymmetric: query side
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

The `<=>` operator is pgvector's cosine distance. `1 - distance` gives similarity (1.0 = identical, 0.0 = unrelated).

Add it to the tool list in your config:

```python
config = types.GenerateContentConfig(
    system_instruction=(
        "You are a fitness coach. Use tools to ground your advice in the user's data "
        "and the science corpus. When you have enough information, give brief, practical advice."
    ),
    tools=[get_user_history, search_corpus],   # <- two tools now
    automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
    max_output_tokens=1000,
)
```

And handle the new tool in the loop:

```python
if function_call.name == "get_user_history":
    result = get_user_history(**dict(function_call.args))
elif function_call.name == "search_corpus":
    result = search_corpus(**dict(function_call.args))
else:
    result = {"error": f"Unknown tool: {function_call.name}"}
```

Restart, test. Now Gemini has two tools — user history + science search. When you give it an activity, it can decide whether to look at your past, look at the literature, or both. Watch the agent loop log to see which it picks.

**Checkpoint reached: you have working Agentic RAG.** This is the heart of Phase 1.

---

### Step 13 — Add Langfuse tracing

Right now if something goes wrong in the agent loop you have no idea why. Langfuse fixes that.

```text
# requirements.txt
langfuse==2.55.0
```

```bash
pip install -r requirements.txt
```

Langfuse doesn't yet have a one-line auto-wrapper for the Gemini SDK the way it does for OpenAI. The cleanest beginner-friendly approach is the `@observe` decorator plus manually logging the LLM input/output. Wrap the analyze endpoint:

```python
from langfuse.decorators import observe, langfuse_context


@app.post("/api/agent/analyze")
@observe()
def analyze(req: AnalyzeRequest):
    contents = [...]  # same as before

    while True:
        # Tell Langfuse this is an LLM generation, not just a function call
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

Restart, do an end-to-end test. Go to https://cloud.langfuse.com → your project → Traces. You should see every call and every tool execution recorded as a tree.

> Once you move to LangGraph in Step 14, switch to the `CallbackHandler` (`from langfuse.callback import CallbackHandler`) which auto-captures everything LangChain does with one line of setup. That's a much nicer integration — this manual approach is a temporary measure.

**Checkpoint reached:** you have observability. Don't ship anything without this.

---

### Step 14 — Move from a manual loop to LangGraph

Your `while True` agent loop works but it'll get hard to extend. LangGraph is the same idea expressed as a graph of nodes — each node does one thing, and edges define what runs next.

```text
# requirements.txt
langgraph==0.2.40
langchain-google-genai==2.0.0
langfuse==2.55.0   # already added, just confirming
```

```bash
pip install -r requirements.txt
```

Move your tools into `tools.py` (so the agent code stays clean):

```python
# tools.py
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

Create `agent.py`:

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
    """Ask the LLM what to do next."""
    response = llm_with_tools.invoke(state["messages"])
    return {"messages": [response]}


def tool_executor(state: AgentState):
    """Run any tool calls in the last message."""
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

In `main.py`, replace the manual loop. We also swap the manual Langfuse calls for the one-line LangChain callback:

```python
from agent import agent
from langchain_core.messages import HumanMessage, SystemMessage
from langfuse.callback import CallbackHandler

langfuse_handler = CallbackHandler()  # picks up keys from env


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

Same behaviour as the manual loop, cleaner structure, and Langfuse now captures *everything* automatically. Why bother? Because the next step (adding a CRITIC node, a MEM_WRITE node, etc.) becomes a one-line graph edit instead of restructuring an imperative loop.

> The `langchain-google-genai` package adapts Gemini to LangChain's interfaces. The agent code is now LLM-agnostic — to try Claude or GPT later, swap one import line.

---

### Step 15 — Plug into the gateway

In `configserver/.../gateway-service.yml`, add a route:

```yaml
- id: agent-service
  uri: lb://AGENT-SERVICE
  predicates:
    - Path=/api/agent/**
```

To register with Eureka, add the Eureka client config. Easiest: hit the Eureka REST API directly from your service on startup (no Spring needed). Or simpler — for Phase 1, bypass Eureka and let the gateway hit `agent-service` directly:

```yaml
- id: agent-service
  uri: http://localhost:8084
  predicates:
    - Path=/api/agent/**
```

Restart the gateway. Now the frontend can call `http://localhost:8080/api/agent/analyze` and the gateway proxies it.

---

### Step 16 — Switch the frontend over

In `fitness-frontend/src/services/api.js`, add:

```js
export const analyzeActivity = (activity) => api.post('/agent/analyze', activity);
```

In `ActivityForm.jsx` or wherever you trigger AI analysis, call `analyzeActivity` and display the response. Done — you're now using your agent.

You can keep the old `ai-service` running in parallel for comparison until you trust the new one.

---

### What Phase 1 leaves you with

A working `agent-service` that:

- Takes a user activity
- Lets Gemini decide whether to fetch user history, search the science corpus, or both
- Returns a grounded answer
- Records every step in Langfuse for debugging
- Is structured as a LangGraph state machine, ready to extend in Phase 2
- Costs $0 in API fees on the free tier for development-level usage

Use it on your own training for two weeks. You'll learn more about what to build next from real usage than from reading.

---

## Part D — Phases 2 through 6 (higher level)

Once Phase 1 is shipped, you'll know enough about the stack that detailed step-by-step stops being needed. These are the build outlines.

### Phase 2 — Memory + chat + Multilingual RAG (4–5 weeks)

**New components:**
- A `chat-service` (or extend `agent-service`) with a WebSocket endpoint for streaming chat
- **Memory:** start with a `user_facts` table in Postgres holding short structured strings ("goal: half-marathon Nov 2026", "injury: Achilles, June 2025"). Have the agent extract facts at the end of each turn and call a `store_fact()` tool. Once you outgrow flat strings, swap in Mem0 (`pip install mem0ai`) for the more elaborate memory features.
- **Multilingual RAG:** swap `text-embedding-3-small` for **BGE-M3** (`pip install FlagEmbedding`) or Cohere's `embed-multilingual-v3.0`. The retrieval interface is identical; only the embedding model changes. Now you can ingest German, Italian, Japanese sources and search across all of them with one query.
- Detect input language with `langdetect` and pass it to the LLM system prompt so it replies in that language.

**Frontend:**
- New chat panel in React. Use MUI's `<Stack>` + `<TextField>` + `<IconButton>`. Stream responses via Server-Sent Events or WebSocket.

### Phase 3 — Graph RAG (4–6 weeks)

**New components:**
- A graph database. Two options:
  - **Neo4j** (mature, has a docker image, query language is Cypher) — pick this if you want the resume bullet
  - **Postgres adjacency tables** — simpler if your graph stays small
- A schema with node types (`Muscle`, `Exercise`, `Injury`, `TrainingPrinciple`, `Symptom`, `RecoveryModality`) and edge types (`targets`, `causes`, `prevents`, `aggravates`, `recovers_from`)
- Manual seeding of ~200 core nodes and ~500 edges (ACSM-style canonical relationships)
- An LLM-driven extraction pipeline that reads each science corpus chunk, asks Gemini to produce `(subject, relation, object)` triples, and proposes them for insertion (human-review before commit)
- A new tool `graph_traverse(start_concept, max_hops, relations)` exposed to the agent
- Add a routing decision in the LangGraph PLANNER: vector RAG / graph RAG / both, depending on whether the question is "similarity" shaped or "causal-chain" shaped

**Reference:** read Microsoft's GraphRAG paper and look at their open-source repo for the indexing pipeline shape.

### Phase 4 — MCP-powered data unification (4–6 weeks)

**New components:**
- MCP servers (use the [official SDK](https://modelcontextprotocol.io/)) for:
  - **Strava** — historical activities, segments
  - **Apple Health / Google Health Connect** — sleep, HRV, resting HR
  - **Google Calendar** — schedule
  - **Weather** (use existing public MCP servers)
- Each server runs as a separate process. Your agent talks to them via MCP's standardized JSON-RPC.
- A "Connections" page in the frontend handles OAuth flows for each data source.

**Why MCP and not direct API calls?** Standardization. Once a service speaks MCP, *any* MCP-aware LLM can use it. You're future-proofing.

### Phase 5 — Multi-agent + adaptive planning (6–8 weeks)

**New components:**
- Decompose the single agent into specialists:
  - **Coach Agent** — owns the active training plan; revises it weekly
  - **Recovery Agent** — analyzes HRV, sleep, training load; raises warnings
  - **Scheduler Agent** — books workouts into the user's calendar (uses Calendar MCP)
  - **Nutrition Agent** (optional)
- A supervisor pattern in LangGraph: a top-level "Supervisor" node decides which subagent to invoke for a given user request.
- A weekly cron job that runs the **adaptive planning loop**:
  1. Coach Agent reads last week's actual training
  2. Recovery Agent computes load and raises any concerns
  3. Coach Agent revises next week's plan
  4. Scheduler Agent updates the calendar
  5. User gets a "Here's what's changing and why" summary email/notification
- Either keep agents in-process (LangGraph subgraphs) or, if you want the resume bullet, expose them via the A2A protocol so they communicate as standardized agents.

### Phase 6 — Voice + ambient (4–6 weeks, optional)

**New components:**
- **OpenAI Realtime API** or **Gemini Live** integration. These accept audio in and produce audio out in real time.
- A phone-side experience (PWA + getUserMedia, or a small React Native shell)
- The voice layer is just a thin shell over your existing agent — same tools, same memory, same RAG. Audio in / audio out is the only new piece.

---

## Part E — Common beginner mistakes and how to avoid them

| Mistake | Fix |
|---------|-----|
| Hardcoding API keys | Always use `.env` + `python-dotenv`. Never commit them. |
| Shipping without tracing | Wire Langfuse from day one. Untraced agents are unmaintainable. |
| Building everything before testing | Stop at every checkpoint and verify. Each one is independent. |
| Adding too many tools at once | Start with 1 tool. Get it working. Then add #2. The agent gets confused when you give it 8 tools day one. |
| Skipping the manual loop and jumping to LangGraph | Don't. Build the manual loop first (Steps 7–9). LangGraph makes sense once you understand what it's replacing. |
| Trying to use OpenAI/Anthropic/Gemini all at once | Pick one for Phase 1. The rest is detail. |
| Ingesting hundreds of PDFs before the pipeline works | Get 5 documents flowing end to end before scaling. |
| Indexing without a `vector` index | Cosine search without an index is slow on >10k rows. The `ivfflat` index in Step 10 is required. |
| Letting cost surprise you | Free tier is enough for development, but watch the quota dashboard in Google AI Studio. If you upgrade to a paid model later, set billing alerts in Google Cloud. |
| No system prompt | An agent without a system prompt drifts. Be explicit: "You are a fitness coach. Always use tools before making claims. Be concise." |

---

## Part F — Learning resources

If you have time before starting, in priority order:

1. **Gemini function calling docs** — https://ai.google.dev/gemini-api/docs/function-calling — the official walkthrough; mirrors what we do in Steps 7–9
2. **DeepLearning.AI's "AI Agents in LangGraph"** — free, ~1 hour
3. **LangGraph docs, "Quickstart" + "Multi-agent"** — https://langchain-ai.github.io/langgraph/
4. **pgvector README** — https://github.com/pgvector/pgvector
5. **Gemini embeddings docs** — https://ai.google.dev/gemini-api/docs/embeddings — explains task types and dimensions
6. **Anthropic's "Building effective agents"** blog post — provider-agnostic; still the best high-level explanation of agent patterns. https://www.anthropic.com/research/building-effective-agents
7. **Microsoft's GraphRAG repo** — read the README, look at the indexing pipeline. Don't try to use it yet, just see what's possible.

---

## Part G — When you're stuck

In order:

1. **Read the Langfuse trace.** It tells you exactly what was sent and received.
2. **Run the failing step in a Python REPL.** Isolate it from FastAPI.
3. **Add `print()` everywhere.** Don't be clever about debugging.
4. **Ask the LLM itself.** Paste your code into Gemini (or any chat model) and ask "why isn't this calling the tool?" — surprisingly effective.
5. **Check the bottom of the LangGraph trace** for the exact prompt that got sent. 90% of agent bugs are bad prompts.

Don't try to do this guide in one weekend. Phase 1 is meant to take weeks of evening work. The point is to learn the foundations deeply enough that Phases 2–6 are extensions, not new battles.
