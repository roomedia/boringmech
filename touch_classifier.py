"""Occurrence-type (touch-structure) classifier for Polymarket questions.

Implements backtest-touch-allfields-SPEC.md exactly. Question text only;
descriptions are used solely by the audit sample, never here.

Returns (kind, touch_side):
  kind: 'price_barrier' | 'price_level' | 'survival_frame' | 'count'
        | 'occurrence' | None
  touch_side: 'yes' | 'no' | None  (the side decided by a single occurrence)
"""
import re

import backtest_touch as bt  # is_touch (price barrier / level)

MONTH = (r"(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?"
         r"|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?"
         r"|nov(?:ember)?|dec(?:ember)?)")
DATE = (rf"(?:{MONTH}\s+\d{{1,2}}(?:st|nd|rd|th)?(?:,?\s*20\d\d)?"
        rf"|\d{{1,2}}\s+{MONTH}(?:\s+20\d\d)?"
        rf"|{MONTH}\s+20\d\d|20\d\d-\d{{2}}-\d{{2}})")
WINDOW = re.compile(
    rf"\b(?:by|before|until|through)\s+(?:the\s+)?"
    rf"(?:{DATE}|{MONTH}|end\s+of\s+(?:the\s+)?(?:year|month|week)"
    rf"|this\s+(?:year|month|week)|next\s+(?:year|month|week))"
    rf"|\bin\s+(?:{MONTH}(?:\s+20\d\d)?|20\d\d)\b",
    re.I)
POINT_IN_TIME = re.compile(rf"\bbe\b[^?]*\bon\s+(?:{DATE})\b", re.I)
ELECTION = re.compile(
    r"\bwin(?:s|ning)?\s+the\b[^?]*\b(election|presidency|nomination|primary|race)\b"
    r"|\bbe\s+elected\b"
    r"|\bnext\s+(president|prime\s+minister|chancellor)\b",
    re.I)
SURVIVAL_CUE = re.compile(r"\b(throughout|entire|whole)\b|\ball\s+of\b", re.I)
SURVIVAL_VERB = re.compile(
    r"\b(hold|holds|remain|remains|stay|stays|last|lasts|survive|survives)\b", re.I)
COUNT = re.compile(r"\b\d+\s+or\s+more\b|\bat\s+least\s+\d+", re.I)


def classify_touch(question):
    t = bt.is_touch(question)
    if t == "barrier":
        return ("price_barrier", "yes")
    if t == "level":
        return ("price_level", None)
    if POINT_IN_TIME.search(question):
        return (None, None)
    if ELECTION.search(question):
        return (None, None)
    has_window = bool(WINDOW.search(question))
    if SURVIVAL_CUE.search(question) or (SURVIVAL_VERB.search(question) and has_window):
        return ("survival_frame", "no")
    if COUNT.search(question) and has_window:
        return ("count", "yes")
    if has_window:
        return ("occurrence", "yes")
    return (None, None)
