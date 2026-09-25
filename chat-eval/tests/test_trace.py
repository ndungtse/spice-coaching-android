from chat_eval import trace

P = "09-23 14:19:53.997 I/ChatTrace( 8084): "
Q1 = "গর্ভকালীন চেকআপের গুরুত্ব কী?"
Q2 = "ডায়রিয়ার প্রধান কারণ কী?"

NEW = [
    P + f'──── turn ──── mode=ON_DEVICE_ASSISTED pref=on-device net=false q="{Q2}"',
    P + "FUSED n=2 order=[952fbce0:7 bm25=1/177.4 dense=2/0.56, e660517c:2 bm25=2/150.0 dense=-]",
    P + "RANK pick=952fbce0:7 fused=1 band=[952fbce0:7,e660517c:2] | 952fbce0-25bb:card:7 score=177.4 terms=[ডায়রিয়া]",
    P + "GATE serve 952fbce0:7",
    P + 'LLM raw: len=42 latencyMs=9757 sawEndOfTurn=true temp=0.2 maxTokens=1536 text="The main reason for diarrhea is infection."',
    P + "POST groundedness=0.40 floor=0.25 validator=ok translation=ok shown=rewrite",
    P + 'OUTCOME=served_grounded served len=39 grounded=[952fbce0-25bb:card:7] text="ডায়রিয়া জন্য প্রধান কারণ সংক্রমণ হয়।"',
]


def test_strip_removes_the_logcat_prefix():
    assert trace.strip(P + "GATE serve a:1") == "GATE serve a:1"
    assert trace.strip("09-23 14:19:53.997 D/ChatViewModel( 8084): L0 deny-list: hard") == "L0 deny-list: hard"
    assert trace.strip("09-22 20:52:21.206 18994 18994 I ChatTrace: GATE serve a:1") == "GATE serve a:1"
    assert trace.strip("no prefix here: GATE serve a:1") == "no prefix here: GATE serve a:1"


def test_anchor_takes_only_this_question_block():
    previous = [P + f'──── turn ──── mode=ON_DEVICE_ASSISTED q="{Q1}"', P + "GATE serve 6818eb87:0",
                P + "OUTCOME=served_grounded served len=10"]
    block = trace.anchored_block(previous + NEW, Q2)
    assert block[0].endswith(f'q="{Q2}"') and len(block) == len(NEW)


def test_anchor_matches_a_truncated_preview_and_prefers_the_latest():
    long_q = "ক" * 130
    preview = "ক" * 120 + "…(130 chars)"
    lines = [P + f'──── turn ──── mode=ON_DEVICE_DIRECT q="{preview}"', P + "GATE serve a0000000:1",
             P + f'──── turn ──── mode=ON_DEVICE_DIRECT q="{preview}"', P + "GATE serve b0000000:2"]
    block = trace.anchored_block(lines, long_q)
    assert trace.parse_block(block)["gate"]["key"] == "b0000000:2"


def test_no_anchor_returns_none():
    assert trace.anchored_block(NEW, "অন্য প্রশ্ন") is None


def test_parse_new_stage_lines():
    t = trace.parse_block(trace.anchored_block(NEW, Q2))
    assert t["mode"] == "ON_DEVICE_ASSISTED"
    assert t["fused"][0] == {"key": "952fbce0:7", "bm25_rank": 1, "bm25_score": 177.4, "dense_rank": 2, "cos": 0.56}
    assert t["fused"][1]["dense_rank"] is None
    assert t["pick"]["key"] == "952fbce0:7" and t["pick"]["fused_rank"] == 1
    assert t["pick"]["band"] == ["952fbce0:7", "e660517c:2"]
    assert t["gate"] == {"result": "serve", "key": "952fbce0:7", "reason": None}
    assert t["post"] == {"groundedness": "0.40", "floor": "0.25", "validator": "ok", "translation": "ok", "shown": "rewrite"}
    assert t["outcome"]["kind"] == "served_grounded"
    assert t["text_en"] == "The main reason for diarrhea is infection."


def test_parse_old_serve_decision_line_as_pick_and_gate():
    block = [P + f'──── turn ──── mode=ON_DEVICE_ASSISTED q="{Q2}"',
             P + "SERVE-DECISION serve 952fbce0-25bb-4de5-a0af-fda9c7b6b465:card:7 score=177.4 terms=[ডায়রিয়া]",
             P + "OUTCOME=FALLBACK kind=fallback_card_body groundedFrom=[952fbce0-25bb:card:7] reason=groundedness:0.15"]
    t = trace.parse_block(block)
    assert t["gate"] == {"result": "serve", "key": "952fbce0:7", "reason": None}
    assert t["pick"]["key"] == "952fbce0:7" and t["fused"] is None
    assert t["outcome"] == {"kind": "FALLBACK", "detail": "fallback_card_body"}


def test_old_refusal_names_the_first_candidate_as_the_pick():
    block = [P + f'──── turn ──── mode=ON_DEVICE_DIRECT q="{Q2}"',
             P + "SERVE-DECISION refuse reason=NO_EVIDENCE 7a1f98e9-ca69:card:3 score=143.5 terms=[] | 7e6098c0-1d0b:card:2 score=96.3",
             P + "OUTCOME=REFUSAL key=refused_no_ground topScore=143.5"]
    t = trace.parse_block(block)
    assert t["gate"] == {"result": "refuse", "key": "7a1f98e9:3", "reason": "NO_EVIDENCE"}
    assert t["outcome"] == {"kind": "REFUSAL", "detail": "refused_no_ground"}


def test_deny_list_refusal_has_no_pick():
    block = [P + f'──── turn ──── mode=ON_DEVICE_ASSISTED q="{Q2}"',
             "09-23 14:19:53.997 D/ChatViewModel( 8084): L0 deny-list: hard out-of-scope match — refusing without LLM call",
             P + "OUTCOME=REFUSAL key=refused_scope topScore=null"]
    t = trace.parse_block(block)
    assert t["gate"] == {"result": "refuse", "key": None, "reason": "DENY"}
    assert t["pick"] is None


def test_block_without_outcome_parses_with_outcome_none():
    assert trace.parse_block(trace.anchored_block(NEW[:4], Q2))["outcome"] is None


def test_crash_is_detected():
    block = NEW[:2] + ["09-23 14:19:55.000 F/libc( 8084): Fatal signal 11 (SIGSEGV) in tid 8100"]
    assert "Fatal signal" in trace.parse_block(block)["crash"]


def test_refusal_outcome_without_a_gate_line_is_a_refusal():
    # A capture that kept only the ChatTrace tag has no `L0 deny-list` line (it is logged
    # under ChatViewModel); the refusal outcome still says the turn was refused.
    block = [P + f'──── turn ──── mode=ON_DEVICE_ASSISTED q="{Q2}"',
             P + "OUTCOME=REFUSAL key=refused_scope topScore=null groundedFrom=[] reason=∅"]
    assert trace.parse_block(block)["gate"] == {"result": "refuse", "key": None, "reason": "DENY"}
    other = [block[0], P + "OUTCOME=REFUSAL key=refused_unsafe topScore=12.0"]
    assert trace.parse_block(other)["gate"] == {"result": "refuse", "key": None, "reason": "refused_unsafe"}
