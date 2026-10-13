"""Load the saved sentiment model once and classify new review texts.

Example:
    from toolkit.predictor import SentimentPredictor
    clf = SentimentPredictor("outputs/models")       # ensemble if DistilBERT is saved, else best linear model
    clf.predict(["Love the smell but the pump broke on day two."])
"""
import json
import sys
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from . import text_prep as tp

LABELS = ["negative", "neutral", "positive"]


class SentimentPredictor:
    def __init__(self, model_dir="outputs/models", backend="auto", device=None, low_confidence=0.6):
        self.dir = Path(model_dir)
        self.cfg = json.loads((self.dir / "deployment.json").read_text())
        self.low_confidence = low_confidence
        self.linear = joblib.load(self.dir / self.cfg["linear_file"])
        bert_dir = self.dir / self.cfg["bert_dir"]
        use_bert = backend in ("auto", "ensemble", "bert") and (bert_dir / "config.json").exists()
        if backend in ("ensemble", "bert") and not use_bert:
            raise FileNotFoundError(f"DistilBERT weights not found in {bert_dir}")
        self.backend = ("bert" if backend == "bert" else "ensemble") if use_bert else "linear"
        self.bert = self.tokenizer = None
        if use_bert:
            import torch
            from transformers import AutoModelForSequenceClassification, AutoTokenizer
            self.torch = torch
            self.device = device or ("cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu")
            self.tokenizer = AutoTokenizer.from_pretrained(bert_dir)
            self.bert = AutoModelForSequenceClassification.from_pretrained(bert_dir, dtype=torch.float32).to(self.device).eval()

    def _linear_proba(self, texts):
        frame = pd.DataFrame({"text_light": [tp.clean_light(t) for t in texts]})
        frame["text_classical"] = [tp.clean_classical(t) or "emptyreview" for t in texts]
        cols = self.cfg["linear_columns"]
        return self.linear.predict_proba(frame[cols] if isinstance(cols, list) else frame[cols])

    def _bert_proba(self, texts, batch_size=32):
        out = []
        with self.torch.no_grad():
            for i in range(0, len(texts), batch_size):
                enc = self.tokenizer([tp.clean_light(t) for t in texts[i:i + batch_size]], truncation=True,
                                     max_length=self.cfg["bert_max_len"], padding=True, return_tensors="pt").to(self.device)
                out.append(self.torch.softmax(self.bert(**enc).logits.float(), -1).cpu().numpy())
        return np.vstack(out)

    def predict_proba(self, texts) -> np.ndarray:
        texts = [tp.combine_title_text("", t) for t in texts]
        if self.backend == "linear":
            return self._linear_proba(texts)
        if self.backend == "bert":
            return self._bert_proba(texts)
        w = self.cfg["w_bert"]
        return w * self._bert_proba(texts) + (1 - w) * self._linear_proba(texts)

    def predict(self, texts) -> pd.DataFrame:
        texts = [texts] if isinstance(texts, str) else list(texts)
        p = self.predict_proba(texts)
        res = pd.DataFrame(p, columns=[f"p_{l}" for l in LABELS])
        res.insert(0, "label", np.array(LABELS)[p.argmax(1)])
        res.insert(1, "confidence", p.max(1))
        res["needs_human_review"] = res["confidence"] < self.low_confidence
        res.insert(0, "text", texts)
        return res


if __name__ == "__main__":   # python -m toolkit.predictor "some review text" ["another one" ...]
    clf = SentimentPredictor(Path(__file__).resolve().parents[1] / "outputs" / "models")
    with pd.option_context("display.max_colwidth", 80, "display.width", 200):
        print(f"backend: {clf.backend}")
        print(clf.predict(sys.argv[1:] or ["Works great, smells amazing!"]).round(3).to_string(index=False))
