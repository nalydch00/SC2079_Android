"""Text preprocessing for the review-sentiment project.

Two cleaning levels are provided:
  * clean_light(text)     -> for BiLSTM / DistilBERT: keeps word order, punctuation and case.
  * clean_classical(text) -> for bag-of-words models: lower-case, contractions expanded, lemmatised,
                             stopwords removed BUT negation, contrast and intensity words kept.
"""
import html
import re
from functools import lru_cache

# ---------------------------------------------------------------- raw clean-up
_BR = re.compile(r"<br\s*/?>", re.I)
_TAG = re.compile(r"<[^>]+>")
_URL = re.compile(r"https?://\S+|www\.\S+")
_WS = re.compile(r"\s+")
_REPEAT = re.compile(r"(.)\1{3,}")             # "soooooo" -> "sooo"
# Amazon fills in "Five Stars" / "One Star" when a reviewer leaves the title blank.
# Keeping it would leak the label (the star rating) into the text, so it is dropped.
AUTO_TITLE = re.compile(r"^\s*(one|two|three|four|five)\s+stars?\s*[.!]*\s*$", re.I)


def strip_html(text) -> str:
    """Unescape HTML entities (&amp; -> &), turn <br /> into spaces, remove any other tags."""
    if not isinstance(text, str):
        return ""
    text = html.unescape(html.unescape(text))   # some reviews are double-escaped (&amp;#34;)
    text = _BR.sub(" ", text)
    text = _TAG.sub(" ", text)
    return _WS.sub(" ", text).strip()


def combine_title_text(title, text) -> str:
    """'Title. Body' with auto-generated star titles removed."""
    title, text = strip_html(title), strip_html(text)
    if AUTO_TITLE.match(title):
        title = ""
    if title and text:
        sep = "" if title[-1] in ".!?" else "."
        return f"{title}{sep} {text}"
    return title or text


def clean_light(text: str) -> str:
    """Light cleaning for neural models: remove URLs, squash repeated characters and whitespace."""
    text = _URL.sub(" ", text)
    text = _REPEAT.sub(r"\1\1\1", text)
    return _WS.sub(" ", text).strip()


# ------------------------------------------------------- classical (bag-of-words)
NEGATIONS = {"not", "no", "nor", "never", "none", "nothing", "neither", "nobody", "nowhere", "cannot", "without"}
CONTRAST = {"but", "however", "although", "though", "yet", "while", "whereas", "despite", "except", "unless", "otherwise"}
INTENSITY = {"very", "too", "so", "only", "more", "most", "less", "least", "few", "enough", "really", "against", "again", "just"}
KEEP_WORDS = NEGATIONS | CONTRAST | INTENSITY

_CONTRACTIONS = [
    (re.compile(r"\bwon'?t\b"), "will not"), (re.compile(r"\bcan'?t\b"), "can not"),
    (re.compile(r"\bain'?t\b"), "is not"), (re.compile(r"\bshan'?t\b"), "shall not"),
    (re.compile(r"n't\b"), " not"),
    (re.compile(r"\b(does|did|is|was|are|were|has|have|had|could|would|should|do|need|must)nt\b"), r"\1 not"),  # "doesnt"
    (re.compile(r"'re\b"), " are"), (re.compile(r"'ve\b"), " have"), (re.compile(r"'ll\b"), " will"),
    (re.compile(r"'d\b"), " would"), (re.compile(r"'m\b"), " am"), (re.compile(r"'s\b"), ""),
]
_TOKEN = re.compile(r"[a-z]+")

_STOPWORDS = None
_LEMMATIZER = None


def _resources():
    """Load NLTK stopwords and the WordNet lemmatiser once (downloads them if missing)."""
    global _STOPWORDS, _LEMMATIZER
    if _STOPWORDS is None:
        import nltk
        for pkg, path in [("stopwords", "corpora/stopwords"), ("wordnet", "corpora/wordnet"), ("omw-1.4", "corpora/omw-1.4")]:
            try:
                nltk.data.find(path)
            except LookupError:
                nltk.download(pkg, quiet=True)
        from nltk.corpus import stopwords
        from nltk.stem import WordNetLemmatizer
        sw = set(stopwords.words("english"))
        sw |= {"would", "could", "also"}            # frequent, carry no sentiment
        _STOPWORDS = frozenset(sw - KEEP_WORDS)     # negation / contrast / intensity words survive
        _LEMMATIZER = WordNetLemmatizer()
    return _STOPWORDS, _LEMMATIZER


@lru_cache(maxsize=300_000)
def _lemma(tok: str) -> str:
    lem = _resources()[1]
    return lem.lemmatize(lem.lemmatize(tok, "v"), "n")   # verbs first ("leaking"->"leak"), then nouns ("bottles"->"bottle")


def clean_classical(text: str) -> str:
    """Lower-case, expand contractions, keep letters only, drop stopwords (keeping KEEP_WORDS), lemmatise."""
    stop, _ = _resources()
    text = clean_light(text).lower().replace("’", "'").replace("`", "'")
    for pat, rep in _CONTRACTIONS:
        text = pat.sub(rep, text)
    toks = [t for t in _TOKEN.findall(text) if (len(t) > 1 or t in KEEP_WORDS) and t not in stop]
    return " ".join(_lemma(t) for t in toks)


def clean_classical_many(texts, n_jobs: int = 1, chunksize: int = 2_000) -> list:
    """clean_classical over a list, optionally in parallel (each worker keeps its own lemma cache)."""
    texts = list(texts)
    if n_jobs <= 1 or len(texts) < 20_000:
        return [clean_classical(t) for t in texts]
    import multiprocessing as mp
    _resources()   # make sure NLTK data is present before forking
    ctx = mp.get_context("fork") if "fork" in mp.get_all_start_methods() else mp.get_context("spawn")
    with ctx.Pool(n_jobs) as pool:
        return pool.map(clean_classical, texts, chunksize)


# ---------------------------------------------------------------- engineered features
def text_features(texts):
    """Vectorised length / style features for a pandas Series of review texts."""
    import pandas as pd
    s = pd.Series(texts).fillna("").astype(str)
    n_end = s.str.count(r"[.!?]+(?:\s|$)")                       # sentence terminators
    ends_with_end = s.str.contains(r"[.!?]\s*$")
    letters = s.str.count(r"[A-Za-z]")
    return pd.DataFrame({
        "char_count": s.str.len(),
        "word_count": s.str.count(r"\S+"),
        "sentence_count": (n_end + (~ends_with_end).astype(int)).where(s.str.len() > 0, 0),
        "exclaim_count": s.str.count("!"),
        "upper_ratio": (s.str.count(r"[A-Z]") / letters.where(letters > 0, 1)).astype(float),
    }, index=s.index)
