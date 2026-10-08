from chat_eval import capture

P = "09-23 14:19:53.997 I/ChatTrace( 8084): "
ROW = {"id": "q1", "question": "ডায়রিয়ার কারণ কী?", "expect": {"cards": ["aaaaaaaa:0"]}}


def lines(mode="ON_DEVICE_ASSISTED", outcome=True):
    out = [P + f'──── turn ──── mode={mode} q="{ROW["question"]}"', P + "GATE serve aaaaaaaa:0"]
    return out + ([P + 'OUTCOME=served_grounded served len=5 text="x"'] if outcome else [])


def test_complete_turn_has_no_warnings():
    t = capture.build_turn(ROW, lines(), "ON_DEVICE_ASSISTED", "উত্তর দেখানো হয়েছে", 900)
    assert t["warnings"] == [] and t["anchored"] and t["gate"]["key"] == "aaaaaaaa:0"
    assert t["trace"][0].endswith(f'q="{ROW["question"]}"')


def test_warnings():
    assert capture.build_turn(ROW, [], "ON_DEVICE_ASSISTED", "", 1)["warnings"] == ["no_anchor"]
    assert "timeout" in capture.build_turn(ROW, lines(outcome=False), "ON_DEVICE_ASSISTED", "", 1)["warnings"]
    assert "mode_mismatch" in capture.build_turn(ROW, lines(mode="ON_DEVICE_DIRECT"), "ON_DEVICE_ASSISTED", "উত্তর", 1)["warnings"]
    assert "no_text" in capture.build_turn(ROW, lines(), "ON_DEVICE_ASSISTED", "", 1)["warnings"]


def test_an_earlier_complete_block_for_the_same_question_is_never_matched():
    # The same question was asked earlier in this app process; its full turn is still in
    # the log buffer. Only lines that appeared after the send may be anchored on.
    before = lines()
    later = "09-23 14:25:01.402 I/ChatTrace( 8084): "  # a real later line has its own timestamp
    after_send = before + [later + f'──── turn ──── mode=ON_DEVICE_ASSISTED q="{ROW["question"]}"']
    fresh = capture.fresh_lines(before, after_send)
    t = capture.build_turn(ROW, fresh, "ON_DEVICE_ASSISTED", "", 1)
    assert t["anchored"] and t["outcome"] is None and "timeout" in t["warnings"]
    assert capture.build_turn(ROW, capture.fresh_lines(before, before), "ON_DEVICE_ASSISTED", "", 1)["warnings"] == ["no_anchor"]


def _rewrite_lines(shown: str):
    return [P + f'──── turn ──── mode=ON_DEVICE_ASSISTED q="{ROW["question"]}"', P + "GATE serve aaaaaaaa:0",
            P + f'SHOWN len={len(shown)} text="{shown.replace(chr(10), "⏎")}"',
            P + 'OUTCOME=served_grounded served len=5 text="x"']


def test_a_rendered_list_is_taken_whole_from_the_shown_line():
    # The bubble renders each list item as its own text view, so the screen capture holds one item.
    shown = "1. প্রথম কাজটি করুন।  \n2. দ্বিতীয় কাজটি করুন।"
    t = capture.build_turn(ROW, _rewrite_lines(shown), "ON_DEVICE_ASSISTED", "প্রথম কাজটি করুন।", 900)
    assert t["text_shown"] == shown and t["text_shown_ui"] == "প্রথম কাজটি করুন।" and t["warnings"] == []


def test_a_capture_the_shown_line_does_not_contain_is_kept_and_warned():
    shown = "1. প্রথম কাজটি করুন।"
    t = capture.build_turn(ROW, _rewrite_lines(shown), "ON_DEVICE_ASSISTED", "আগের প্রশ্নের উত্তর", 900)
    assert t["text_shown"] == "আগের প্রশ্নের উত্তর" and "shown_mismatch" in t["warnings"]


def _direct_lines(dense: str):
    return [P + f'──── turn ──── mode=ON_DEVICE_DIRECT q="{ROW["question"]}"', P + dense,
            P + "GATE serve aaaaaaaa:0", P + 'OUTCOME=served_retrieval_only served len=5 text="x"']


def test_a_turn_on_the_wrong_search_path_is_warned():
    off = _direct_lines("DENSE off (index=false embedder=true)")
    on = _direct_lines("DENSE hits=8 top=aaaaaaaa:0@0.71")
    assert "dense_off" in capture.build_turn(ROW, off, "ON_DEVICE_DIRECT", "উত্তর", 1,
                                             dense_expected=capture.DENSE_EXPECTED["enhanced"])["warnings"]
    assert "dense_on" in capture.build_turn(ROW, on, "ON_DEVICE_DIRECT", "উত্তর", 1,
                                            dense_expected=capture.DENSE_EXPECTED["standard"])["warnings"]
    assert capture.build_turn(ROW, on, "ON_DEVICE_DIRECT", "উত্তর", 1,
                              dense_expected=capture.DENSE_EXPECTED["enhanced"])["warnings"] == []
