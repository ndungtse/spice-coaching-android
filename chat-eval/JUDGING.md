# Judging instructions

You are judging what a community health worker (CHW) was shown by the offline chat, for one
question at a time. Each sheet entry gives the question, the expected card (the answer the
content team wrote for it), the card actually served if it was a different one, and the exact
text the CHW saw.

Give one verdict per entry:

- **CORRECT**: the shown text answers the question with the expected card's content. It
  contradicts nothing in that card and leaves out none of the card's points that the
  question asks for.
- **PARTIAL**: the shown text is about the right content, but omits points the question asks
  for, or is partly off the question.
- **INCORRECT**: the shown text gives different content, contradicts the card, or does not
  address the question.

When the served card is a neighbour or another card, judge whether its content answers the
question as well as the expected card would.

Also record **hallucination**: any claim in the shown text that the served card does not
contain. Quote the phrase. Record it whatever the verdict.

Write one or two sentences of reason, naming the missing or wrong point.

## Verdict file

One JSON object per line, one line per sheet entry, in any order:

```json
{"id":"uc2_q027","verdict":"PARTIAL","reason":"States infection as the cause but omits the virus, bacteria and parasite types the card lists.","hallucination":null,"judge":"model:claude-opus-5-5"}
```

- `verdict`: `CORRECT`, `PARTIAL` or `INCORRECT`.
- `hallucination`: `null`, or `{"phrase": "…"}`.
- `judge`: `model:<model id>` or `human:<initials>`.
