"""Bounded task planning for Exa research branches.

The planner proposes search work only. It does not return findings, citations,
user facts, or evidence; those must come from downstream providers and inputs.
"""

from __future__ import annotations

import asyncio
import json
import re
from typing import Any, Literal, TypedDict

from google import genai

from config import get_api_key


MAX_SUBTASKS = 6
MAX_REQUIRED_INPUTS = 5
MAX_LIST_ITEMS = 6
MAX_TITLE_LENGTH = 80
MAX_GOAL_LENGTH = 240
MAX_QUERY_LENGTH = 500
MAX_QUESTION_LENGTH = 240
MAX_CRITERION_LENGTH = 160
MAX_PROMPT_LENGTH = 4000
PLANNING_TIMEOUT_SECONDS = 8


class PlannedSubtask(TypedDict):
    id: str
    title: str
    goal: str
    query: str
    depends_on: list[str]
    required_input_ids: list[str]
    comparison_criteria: list[str]


class PlannedInput(TypedDict):
    id: str
    key: str
    question: str
    options: list[str]
    required: bool
    blocks_subtask_ids: list[str]


class Plan(TypedDict):
    subtasks: list[PlannedSubtask]
    required_inputs: list[PlannedInput]
    comparison_criteria: list[str]
    planning_mode: Literal["gemini", "deterministic_fallback"]
    planning_error: str | None


class PlanValidationError(ValueError):
    """Raised for Gemini output that cannot safely drive the DAG."""


def _clean_text(value: Any, limit: int) -> str:
    if not isinstance(value, str):
        return ""
    text = " ".join(value.split())
    text = "".join(char for char in text if char.isprintable())
    return text[:limit].strip()


def _text_list(value: Any, *, limit: int, item_limit: int = MAX_LIST_ITEMS) -> list[str]:
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for raw_item in value:
        item = _clean_text(raw_item, limit)
        if item and item not in result:
            result.append(item)
        if len(result) >= item_limit:
            break
    return result


def _id_list(value: Any, *, maximum: int) -> list[str]:
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for raw_item in value:
        item = _clean_text(raw_item, 48)
        if item and item not in result:
            result.append(item)
        if len(result) >= maximum:
            break
    return result


def _normalized_prompt(prompt: str) -> str:
    if not isinstance(prompt, str) or not prompt.strip():
        raise ValueError("A non-empty prompt is required for planning.")
    return " ".join(prompt.split())[:MAX_PROMPT_LENGTH]


def _criteria_for(prompt: str) -> list[str]:
    lowered = prompt.lower()
    is_travel_options = bool(
        re.search(r"\b(flight|flights|hotel|hotels|trip|travel|airline|accommodation)\b", lowered)
        and re.search(r"\b(cheap|cheapest|price|cost|compare|best|options|alternative|recommend)\b", lowered)
    )
    is_comparison = bool(
        re.search(r"\b(compare|comparison|versus|vs\.?|best|better|options|alternatives|recommend)\b", lowered)
        or is_travel_options
    )
    if not is_comparison:
        return []

    criteria: list[str] = []
    if re.search(r"\b(price|cost|cheap|cheapest|budget|afford)\b|[$£€]\s*\d", lowered):
        criteria.append("Total cost and fit to any budget stated in the prompt.")
    if re.search(r"\b(fast|quick|duration|time|direct|shortest)\b", lowered):
        criteria.append("Time, duration, and delays relevant to the request.")
    if re.search(r"\b(rating|rated|review|reliable|reliability)\b", lowered):
        criteria.append("Reliability and the quality of available review evidence.")
    if re.search(r"\b(feature|specification|specs|capacity|performance)\b", lowered):
        criteria.append("Fit to the requested features and specifications.")
    criteria.append("Match to stated constraints, evidence quality, and material trade-offs.")
    return criteria[:MAX_LIST_ITEMS]


def _query(prompt: str, angle: str) -> str:
    query = f"{prompt} {angle}".strip()
    if len(query) <= MAX_QUERY_LENGTH:
        return query
    suffix = f" {angle}"
    available = MAX_QUERY_LENGTH - len(suffix)
    if available >= 32:
        # Preserve the start and end of long prompts so a late constraint is
        # less likely to disappear from the bounded query.
        start_size = available - 56 - 3
        end_size = 56
        return f"{prompt[:start_size].rstrip()} … {prompt[-end_size:].lstrip()}{suffix}"[:MAX_QUERY_LENGTH]
    return query[:MAX_QUERY_LENGTH]


def _make_subtask(
    identifier: str,
    title: str,
    goal: str,
    query: str,
    *,
    comparison_criteria: list[str] | None = None,
    required_input_ids: list[str] | None = None,
) -> PlannedSubtask:
    return {
        "id": identifier,
        "title": _clean_text(title, MAX_TITLE_LENGTH),
        "goal": _clean_text(goal, MAX_GOAL_LENGTH),
        "query": _clean_text(query, MAX_QUERY_LENGTH),
        "depends_on": [],
        "required_input_ids": required_input_ids or [],
        "comparison_criteria": comparison_criteria or [],
    }


def _add_input(
    inputs: list[PlannedInput],
    *,
    key: str,
    question: str,
    required: bool,
    blocks: list[str],
) -> None:
    if len(inputs) >= MAX_REQUIRED_INPUTS or not blocks:
        return
    inputs.append({
        "id": f"input_{len(inputs) + 1}",
        "key": key,
        "question": _clean_text(question, MAX_QUESTION_LENGTH),
        # Options need evidence or an explicit user-provided choice list.
        "options": [],
        "required": required,
        "blocks_subtask_ids": blocks[:MAX_SUBTASKS],
    })


def _fallback_plan(prompt: str, *, planning_error: str | None = None) -> Plan:
    """Build a useful, evidence-neutral plan without Gemini."""
    lowered = prompt.lower()
    comparison_criteria = _criteria_for(prompt)
    subtask_specs: list[tuple[str, str, str]]

    if re.search(r"\b(flight|flights|hotel|hotels|trip|travel|airline|accommodation)\b", lowered):
        subtask_specs = [
            (
                "Find current travel options",
                "Identify options that match the route or destination and constraints stated by the user.",
                "current options, route or destination details, availability",
            ),
            (
                "Check prices and terms",
                "Find current pricing, restrictions, and material terms for relevant options.",
                "current prices, fees, conditions, cancellation terms",
            ),
            (
                "Verify provider details",
                "Cross-check important details against primary provider sources.",
                "official provider information, current terms",
            ),
        ]
    elif re.search(r"\b(restaurant|restaurants|cafe|cafes|coffee|near me|nearby|local)\b", lowered):
        subtask_specs = [
            (
                "Find relevant local options",
                "Identify places matching the user's stated location and preferences.",
                "local options, location, stated preferences",
            ),
            (
                "Verify current details",
                "Check current hours, services, and source details for candidate places.",
                "current hours, services, official listings",
            ),
        ]
    elif re.search(r"\b(compare|comparison|versus|vs\.?|best|better|options|alternatives|recommend)\b", lowered):
        subtask_specs = [
            (
                "Find relevant options",
                "Identify the options named or requested and gather their current details.",
                "options, current details, specifications",
            ),
            (
                "Compare stated decision factors",
                "Gather evidence for the criteria stated by the user and note trade-offs.",
                "comparison, trade-offs, stated criteria",
            ),
            (
                "Verify primary details",
                "Check important claims against authoritative or primary sources.",
                "official primary sources, current details",
            ),
        ]
    elif re.search(r"\b(law|legal|statute|regulation|tax|court|rights?)\b", lowered):
        subtask_specs = [
            (
                "Find applicable primary authorities",
                "Locate relevant official legal or regulatory material without assuming a jurisdiction.",
                "official law, statute, regulation, jurisdiction",
            ),
            (
                "Cross-check current interpretation",
                "Find reliable current guidance and distinguish it from binding primary text.",
                "current official guidance, applicability, dates",
            ),
        ]
    elif re.search(r"\b(how to|how do i|steps|instructions|guide|process)\b", lowered):
        subtask_specs = [
            (
                "Find the requested process",
                "Locate current instructions that address the requested task.",
                "steps, requirements, current guidance",
            ),
            (
                "Check official requirements",
                "Verify critical steps and prerequisites against primary sources.",
                "official instructions, prerequisites, exceptions",
            ),
        ]
    elif re.search(r"\b(latest|current|today|recent|news|update|announced)\b", lowered):
        subtask_specs = [
            (
                "Find current information",
                "Gather recent information responsive to the user request.",
                "latest current information, dates, recent updates",
            ),
            (
                "Verify primary announcements",
                "Cross-check important updates against the organization or primary source.",
                "official announcements, primary sources, publication dates",
            ),
        ]
    else:
        subtask_specs = [
            (
                "Research the requested topic",
                "Find current information that directly answers the user request.",
                "current information, relevant primary sources",
            ),
            (
                "Cross-check key details",
                "Verify important details and identify uncertainty or missing evidence.",
                "authoritative sources, verification, relevant dates",
            ),
        ]

    subtasks = [
        _make_subtask(
            f"subtask_{index}",
            title,
            goal,
            _query(prompt, angle),
            comparison_criteria=comparison_criteria,
        )
        for index, (title, goal, angle) in enumerate(subtask_specs, start=1)
    ]

    inputs: list[PlannedInput] = []
    all_ids = [subtask["id"] for subtask in subtasks]
    is_travel = bool(re.search(r"\b(flight|flights|hotel|hotels|trip|travel|airline|accommodation)\b", lowered))
    travel_option_ids = all_ids[:2]
    date_present = bool(re.search(
        r"\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|"
        r"sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|today|tomorrow|tonight|this weekend|next week)\b"
        r"|\b20\d{2}\b|\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b",
        lowered,
    ))
    is_travel_search = bool(
        is_travel
        and re.search(r"\b(find|search|compare|plan|book|reserve|price|cost|fare|cheapest|best|recommend|options)\b", lowered)
        and not re.search(r"\b(website|websites|app|apps|how to|guide)\b", lowered)
    )

    if is_travel_search and not date_present and not re.search(r"\bflexib(?:le|ility)\b", lowered):
        _add_input(
            inputs,
            key="dates",
            question="What dates should I use?",
            required=True,
            blocks=travel_option_ids,
        )

    if is_travel_search and re.search(r"\b(flight|flights)\b", lowered) and not re.search(
        r"\bfrom\s+\S+|\bbetween\s+\S+\s+and\s+\S+", lowered
    ):
        has_destination = bool(re.search(
            r"\bto\s+(?!book\b|find\b|compare\b|travel\b|buy\b|reserve\b|go\b)[A-Za-z][\w'-]+",
            prompt,
            re.IGNORECASE,
        ))
        _add_input(
            inputs,
            key="route",
            question="What route should I search (departure and destination)?" if not has_destination else "Where should the flight depart from?",
            required=True,
            blocks=travel_option_ids,
        )

    explicit_location = re.search(
        r"\b(?:in|near|around)\s+(?!my\b|your\b|our\b|the\b|this\b|that\b|a\b|an\b|me\b|nearby\b)([a-z][\w'-]+)",
        prompt,
        re.IGNORECASE,
    )
    if re.search(r"\b(near me|nearby|close to me|in my area)\b", lowered) and not explicit_location:
        _add_input(
            inputs,
            key="location",
            question="What location should I use?",
            required=True,
            blocks=all_ids,
        )

    jurisdiction = re.search(
        r"\b(?:in|under|within)\s+(?:the\s+)?([a-z][\w-]+)", prompt, re.IGNORECASE
    )
    known_jurisdiction = bool(jurisdiction and jurisdiction.group(1).lower() not in {
        "my", "your", "our", "this", "that", "any", "a", "an", "law", "legal", "general",
    })
    if re.search(r"\b(law|legal|statute|regulation|tax|court|rights?)\b", lowered) and not known_jurisdiction:
        _add_input(
            inputs,
            key="jurisdiction",
            question="Which country, state, or jurisdiction should I use?",
            required=True,
            blocks=all_ids,
        )

    for requirement in inputs:
        for subtask in subtasks:
            if subtask["id"] in requirement["blocks_subtask_ids"]:
                subtask["required_input_ids"].append(requirement["id"])

    return {
        "subtasks": subtasks[:MAX_SUBTASKS],
        "required_inputs": inputs,
        "comparison_criteria": comparison_criteria,
        "planning_mode": "deterministic_fallback",
        "planning_error": _clean_text(planning_error, 160) if planning_error else None,
    }


def _normalize_gemini_plan(data: Any) -> Plan:
    if not isinstance(data, dict):
        raise PlanValidationError("Planner response must be a JSON object.")

    raw_subtasks = data.get("subtasks")
    if not isinstance(raw_subtasks, list) or not raw_subtasks:
        raise PlanValidationError("Planner response did not include subtasks.")
    if len(raw_subtasks) > MAX_SUBTASKS:
        raise PlanValidationError(f"Planner response exceeded the {MAX_SUBTASKS}-subtask limit.")

    source_subtask_ids: list[str] = []
    for index, raw in enumerate(raw_subtasks, start=1):
        if not isinstance(raw, dict):
            raise PlanValidationError("Each subtask must be an object.")
        source_id = _clean_text(raw.get("id"), 48) or f"subtask_{index}"
        if source_id in source_subtask_ids:
            raise PlanValidationError("Subtask IDs must be unique.")
        source_subtask_ids.append(source_id)
    subtask_id_map = {source_id: f"subtask_{index}" for index, source_id in enumerate(source_subtask_ids, start=1)}

    raw_inputs = data.get("required_inputs", [])
    if not isinstance(raw_inputs, list):
        raise PlanValidationError("Required inputs must be a list.")
    if len(raw_inputs) > MAX_REQUIRED_INPUTS:
        raise PlanValidationError(f"Planner response exceeded the {MAX_REQUIRED_INPUTS}-input limit.")

    source_input_ids: list[str] = []
    for index, raw in enumerate(raw_inputs, start=1):
        if not isinstance(raw, dict):
            raise PlanValidationError("Each required input must be an object.")
        source_id = _clean_text(raw.get("id"), 48) or f"input_{index}"
        if source_id in source_input_ids:
            raise PlanValidationError("Required input IDs must be unique.")
        source_input_ids.append(source_id)
    input_id_map = {source_id: f"input_{index}" for index, source_id in enumerate(source_input_ids, start=1)}

    subtasks: list[PlannedSubtask] = []
    for index, raw in enumerate(raw_subtasks, start=1):
        source_id = source_subtask_ids[index - 1]
        title = _clean_text(raw.get("title"), MAX_TITLE_LENGTH)
        goal = _clean_text(raw.get("goal"), MAX_GOAL_LENGTH)
        query = _clean_text(raw.get("query"), MAX_QUERY_LENGTH)
        if not title or not goal or not query:
            raise PlanValidationError("Every subtask needs a title, goal, and search query.")

        dependencies: list[str] = []
        for dependency_id in _id_list(raw.get("depends_on", []), maximum=MAX_SUBTASKS):
            if dependency_id not in subtask_id_map:
                raise PlanValidationError("A subtask dependency refers to an unknown subtask.")
            mapped_id = subtask_id_map[dependency_id]
            if mapped_id == f"subtask_{index}":
                raise PlanValidationError("A subtask cannot depend on itself.")
            dependencies.append(mapped_id)

        raw_required_inputs = _id_list(raw.get("required_input_ids", []), maximum=MAX_REQUIRED_INPUTS)
        unknown_inputs = [item for item in raw_required_inputs if item not in input_id_map]
        if unknown_inputs:
            raise PlanValidationError("A subtask refers to an unknown required input.")

        subtasks.append({
            "id": f"subtask_{index}",
            "title": title,
            "goal": goal,
            "query": query,
            "depends_on": dependencies,
            "required_input_ids": [input_id_map[item] for item in raw_required_inputs],
            "comparison_criteria": _text_list(raw.get("comparison_criteria", []), limit=MAX_CRITERION_LENGTH),
        })

    raw_dependency_map = {subtask["id"]: set(subtask["depends_on"]) for subtask in subtasks}
    completed: set[str] = set()
    while len(completed) < len(subtasks):
        ready = {identifier for identifier, dependencies in raw_dependency_map.items()
                 if identifier not in completed and dependencies <= completed}
        if not ready:
            raise PlanValidationError("Subtask dependencies contain a cycle.")
        completed.update(ready)

    required_inputs: list[PlannedInput] = []
    for index, raw in enumerate(raw_inputs, start=1):
        source_id = source_input_ids[index - 1]
        key = _clean_text(raw.get("key"), 64)
        question = _clean_text(raw.get("question"), MAX_QUESTION_LENGTH)
        if not key or not question:
            raise PlanValidationError("Every required input needs a key and question.")
        required = raw.get("required", True)
        if not isinstance(required, bool):
            raise PlanValidationError("Required input `required` must be a boolean.")
        if not required:
            raise PlanValidationError("The planner only returns inputs that block research.")

        blocked_source_ids = _id_list(raw.get("blocks_subtask_ids", []), maximum=MAX_SUBTASKS)
        if any(identifier not in subtask_id_map for identifier in blocked_source_ids):
            raise PlanValidationError("A required input refers to an unknown blocked subtask.")
        blocked = {subtask_id_map[identifier] for identifier in blocked_source_ids}
        mapped_input_id = input_id_map[source_id]
        for subtask in subtasks:
            if mapped_input_id in subtask["required_input_ids"]:
                blocked.add(subtask["id"])
        if not blocked:
            raise PlanValidationError("A required input must block at least one subtask.")

        required_inputs.append({
            "id": mapped_input_id,
            "key": key,
            "question": question,
            # Choices must come from user input or later Exa evidence, never
            # from an ungrounded planning response.
            "options": [],
            "required": required,
            "blocks_subtask_ids": sorted(blocked),
        })

    for subtask in subtasks:
        subtask["required_input_ids"] = [
            requirement["id"]
            for requirement in required_inputs
            if subtask["id"] in requirement["blocks_subtask_ids"]
        ]

    return {
        "subtasks": subtasks,
        "required_inputs": required_inputs,
        "comparison_criteria": _text_list(data.get("comparison_criteria", []), limit=MAX_CRITERION_LENGTH),
        "planning_mode": "gemini",
        "planning_error": None,
    }


def _fallback_with_error(prompt: str, reason: str) -> Plan:
    return _fallback_plan(prompt, planning_error=reason)


async def plan(prompt: str) -> Plan:
    """Return a bounded plan; Gemini failures degrade to deterministic queries."""
    normalized_prompt = _normalized_prompt(prompt)
    key = get_api_key("GEMINI_API_KEY")
    if not key:
        return _fallback_with_error(
            normalized_prompt,
            "GEMINI_API_KEY is not configured; using deterministic fallback.",
        )

    instructions = {
        "task": "Create a bounded plan for web research. Return JSON only.",
        "output_shape": {
            "subtasks": [{
                "id": "s1",
                "title": "Short branch name",
                "goal": "Research goal without claiming a result",
                "query": "Specific Exa search query",
                "depends_on": [],
                "required_input_ids": [],
                "comparison_criteria": [],
            }],
            "required_inputs": [{
                "id": "i1",
                "key": "snake_case_key",
                "question": "Ask only for information that is genuinely blocking",
                "options": [],
                "required": True,
                "blocks_subtask_ids": ["s1"],
            }],
            "comparison_criteria": [],
        },
        "limits": {
            "subtasks": f"1 to {MAX_SUBTASKS}",
            "required_inputs": f"0 to {MAX_REQUIRED_INPUTS}",
            "list_items": f"at most {MAX_LIST_ITEMS}",
            "title_characters": MAX_TITLE_LENGTH,
            "goal_characters": MAX_GOAL_LENGTH,
            "query_characters": MAX_QUERY_LENGTH,
            "question_characters": MAX_QUESTION_LENGTH,
            "criterion_characters": MAX_CRITERION_LENGTH,
        },
        "rules": [
            "Use independent subtasks when branches can research in parallel; add dependencies only when a later subtask needs an earlier subtask's output.",
            "Ask for required user input only when it blocks useful research; attach it only to the subtasks it blocks.",
            "Return empty options: choices must come from the user or later live Exa findings.",
            "Do not invent user facts, search findings, recommendations, citations, or source content.",
            "Queries are suggestions for a downstream live Exa search, never evidence.",
            "Keep comparison criteria tied to this request. Use empty lists when they do not apply.",
            "Do not claim that any query has already been searched.",
        ],
        "user_prompt": normalized_prompt,
    }

    try:
        client = genai.Client(api_key=key)
        result = await asyncio.wait_for(
            asyncio.to_thread(
                client.models.generate_content,
                model="gemini-2.5-flash",
                contents=json.dumps(instructions, ensure_ascii=False),
                config={"response_mime_type": "application/json"},
            ),
            timeout=PLANNING_TIMEOUT_SECONDS,
        )
        data = json.loads(result.text or "{}")
        try:
            return _normalize_gemini_plan(data)
        except PlanValidationError as exc:
            return _fallback_with_error(normalized_prompt, f"Gemini plan rejected ({exc}).")
    except asyncio.TimeoutError:
        return _fallback_with_error(
            normalized_prompt,
            f"Gemini planning timed out after {PLANNING_TIMEOUT_SECONDS} seconds.",
        )
    except json.JSONDecodeError:
        return _fallback_with_error(normalized_prompt, "Gemini planning returned invalid JSON.")
    except Exception as exc:
        # Report only the exception type; provider exceptions can include
        # request details and must not leak credentials into task state/logs.
        return _fallback_with_error(normalized_prompt, f"Gemini planning unavailable ({type(exc).__name__}).")
