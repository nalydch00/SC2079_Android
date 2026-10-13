"""Shared evaluation toolkit: every model is scored, plotted and recorded the same way."""
from pathlib import Path

import matplotlib.pyplot as plt
import matplotlib.ticker as mtick
import numpy as np
import pandas as pd
from sklearn.metrics import (accuracy_score, average_precision_score, classification_report, confusion_matrix,
                             f1_score, log_loss, precision_recall_curve, precision_recall_fscore_support,
                             roc_auc_score, roc_curve)

LABELS = ["negative", "neutral", "positive"]
SENT_COLORS = {"negative": "#e34948", "neutral": "#8f8d86", "positive": "#2a78d6"}
INK, INK2 = "#0b0b0b", "#52514e"


class ResultsBook:
    """Shared results table with one row per (model, split), saved to CSV after every update."""

    def __init__(self, path):
        self.path = Path(path)
        self.df = pd.read_csv(self.path) if self.path.exists() else pd.DataFrame()

    def record(self, row: dict) -> None:
        if len(self.df):
            self.df = self.df[~((self.df["model"] == row["model"]) & (self.df["split"] == row["split"]))]
        self.df = pd.concat([self.df, pd.DataFrame([row])], ignore_index=True)
        self.df.to_csv(self.path, index=False)

    def table(self, split: str, sort_by: str = "macro_f1") -> pd.DataFrame:
        t = self.df[self.df["split"] == split].sort_values(sort_by, ascending=False)
        return t.reset_index(drop=True)


def neutral_calibration(y_true, proba, n_bins: int = 10):
    """Reliability table for P(neutral): predicted probability vs observed neutral rate, plus ECE."""
    p, hit = proba[:, 1], (np.asarray(y_true) == 1).astype(float)
    edges = np.linspace(0, 1, n_bins + 1)
    idx = np.clip(np.digitize(p, edges) - 1, 0, n_bins - 1)
    rows = [(p[idx == b].mean(), hit[idx == b].mean(), (idx == b).sum()) for b in range(n_bins) if (idx == b).any()]
    cal = pd.DataFrame(rows, columns=["mean_predicted", "observed_rate", "n"])
    ece = float((cal["n"] / len(p) * (cal["mean_predicted"] - cal["observed_rate"]).abs()).sum())
    return cal, ece


def _dashboard(name, split, y, proba, pred, metrics, cal):
    fig, axes = plt.subplots(2, 3, figsize=(17, 9.6))
    # (1) confusion matrix: counts and row %
    ax = axes[0, 0]
    cm = confusion_matrix(y, pred, labels=[0, 1, 2])
    cm_pct = cm / cm.sum(axis=1, keepdims=True)
    ax.imshow(cm_pct, cmap="Blues", vmin=0, vmax=1)
    for i in range(3):
        for j in range(3):
            ax.text(j, i, f"{cm[i, j]:,}\n({cm_pct[i, j]:.0%})", ha="center", va="center",
                    color="white" if cm_pct[i, j] > 0.55 else INK, fontsize=10)
    ax.set(xticks=range(3), yticks=range(3), xticklabels=LABELS, yticklabels=LABELS,
           xlabel="Predicted", ylabel="Actual", title="Confusion matrix (row %)")
    ax.grid(False)
    # (2) ROC and (3) precision-recall, one-vs-rest
    for k, lab in enumerate(LABELS):
        fpr, tpr, _ = roc_curve(y == k, proba[:, k])
        axes[0, 1].plot(fpr, tpr, color=SENT_COLORS[lab], label=f"{lab} (AUC {metrics[f'auc_{lab}']:.3f})")
        prec, rec, _ = precision_recall_curve(y == k, proba[:, k])
        axes[0, 2].plot(rec, prec, color=SENT_COLORS[lab], label=f"{lab} (AP {metrics[f'ap_{lab}']:.3f})")
    axes[0, 1].plot([0, 1], [0, 1], ls="--", color=INK2, lw=1)
    axes[0, 1].set(title="ROC curves (one-vs-rest)", xlabel="False positive rate", ylabel="True positive rate")
    axes[0, 2].set(title="Precision-recall curves", xlabel="Recall", ylabel="Precision", ylim=(0, 1.02))
    for ax in (axes[0, 1], axes[0, 2]):
        ax.legend(loc="lower left" if ax is axes[0, 2] else "lower right", fontsize=9)
    # (4) confidence histogram
    conf, correct = proba.max(axis=1), pred == y
    bins = np.linspace(1 / 3, 1, 21)
    for mask, lab, col in [(correct, "correct", "#2a78d6"), (~correct, "wrong", "#eb6834")]:
        if mask.any():
            axes[1, 0].hist(conf[mask], bins=bins, alpha=0.75, color=col, label=f"{lab} (n={mask.sum():,}, mean {conf[mask].mean():.2f})")
    axes[1, 0].set(title="Confidence of predictions", xlabel="Highest class probability", ylabel="Reviews")
    axes[1, 0].legend(fontsize=9)
    # (5) neutral calibration
    ax = axes[1, 1]
    ax.plot([0, 1], [0, 1], ls="--", color=INK2, lw=1, label="perfect calibration")
    ax.plot(cal["mean_predicted"], cal["observed_rate"], marker="o", markersize=7, color=SENT_COLORS["neutral"], label="model")
    ax.set(title=f"Neutral-class calibration (ECE {metrics['neutral_ece']:.3f})", xlabel="Predicted P(neutral)",
           ylabel="Observed share neutral", xlim=(0, 1), ylim=(0, 1))
    ax.legend(fontsize=9)
    # (6) per-class precision / recall / F1
    ax = axes[1, 2]
    p, r, f, _ = precision_recall_fscore_support(y, pred, labels=[0, 1, 2], zero_division=0)
    x = np.arange(3)
    for off, vals, lab, col in [(-0.26, p, "precision", "#9ec5f4"), (0, r, "recall", "#3987e5"), (0.26, f, "F1", "#184f95")]:
        ax.bar(x + off, vals, width=0.24, color=col, label=lab)
        for xi, v in zip(x + off, vals):
            ax.text(xi, v + 0.01, f"{v:.2f}", ha="center", fontsize=8, color=INK)
    ax.set(xticks=x, xticklabels=LABELS, ylim=(0, 1.1), title="Precision, recall and F1 by class")
    ax.legend(fontsize=9, ncol=3, loc="upper center")
    ax.grid(axis="x", visible=False)
    fig.suptitle(f"{name}  |  {split} set (n={len(y):,})  |  accuracy {metrics['accuracy']:.3f}  |  macro F1 {metrics['macro_f1']:.3f}",
                 fontsize=14, fontweight="bold", y=1.0)
    fig.tight_layout()
    return fig


def _compact(name, split, y, pred, metrics):
    fig, ax = plt.subplots(figsize=(4.6, 3.9))
    cm = confusion_matrix(y, pred, labels=[0, 1, 2])
    cm_pct = cm / cm.sum(axis=1, keepdims=True)
    ax.imshow(cm_pct, cmap="Blues", vmin=0, vmax=1)
    for i in range(3):
        for j in range(3):
            ax.text(j, i, f"{cm[i, j]:,}\n({cm_pct[i, j]:.0%})", ha="center", va="center",
                    color="white" if cm_pct[i, j] > 0.55 else INK, fontsize=9)
    ax.set(xticks=range(3), yticks=range(3), xticklabels=LABELS, yticklabels=LABELS, xlabel="Predicted", ylabel="Actual")
    ax.set_title(f"{name}: {split} set\nacc {metrics['accuracy']:.3f} | macro F1 {metrics['macro_f1']:.3f}", fontsize=10)
    ax.grid(False)
    fig.tight_layout()
    return fig


def evaluate_model(name, y_true, proba, *, split, book=None, ids=None, family="", train_time=np.nan,
                   infer_time=np.nan, plots="full", fig_dir=None, pred_dir=None, report=True, notes=""):
    """Score one model on one split.

    plots: "full" (six-panel dashboard), "compact" (confusion matrix only) or None.
    Returns a dict of metrics, which is also recorded in `book` (a ResultsBook) if given.
    """
    y = np.asarray(y_true).astype(int)
    proba = np.clip(np.asarray(proba, dtype=float), 1e-7, 1)
    proba = proba / proba.sum(axis=1, keepdims=True)
    pred = proba.argmax(axis=1)
    p, r, f, _ = precision_recall_fscore_support(y, pred, labels=[0, 1, 2], zero_division=0)
    cal, ece = neutral_calibration(y, proba)
    m = {"model": name, "family": family, "split": split, "n": len(y),
         "accuracy": accuracy_score(y, pred), "macro_f1": f1_score(y, pred, average="macro"),
         "weighted_f1": f1_score(y, pred, average="weighted")}
    for k, lab in enumerate(LABELS):
        m[f"f1_{lab}"], m[f"precision_{lab}"], m[f"recall_{lab}"] = f[k], p[k], r[k]
        m[f"auc_{lab}"] = roc_auc_score(y == k, proba[:, k])
        m[f"ap_{lab}"] = average_precision_score(y == k, proba[:, k])
    m["roc_auc_macro"] = float(np.mean([m[f"auc_{l}"] for l in LABELS]))
    m["log_loss"] = log_loss(y, proba, labels=[0, 1, 2])
    m["neutral_ece"] = ece
    m["train_time_s"] = train_time
    m["infer_time_s"] = infer_time
    m["infer_ms_per_review"] = infer_time / len(y) * 1000 if infer_time == infer_time else np.nan
    m["notes"] = notes

    if report:
        print(f"=== {name} | {split} set (n={len(y):,}) ===")
        print(classification_report(y, pred, labels=[0, 1, 2], target_names=LABELS, digits=3))
    print(f"[{split:>7}] {name}: accuracy {m['accuracy']:.3f} | macro F1 {m['macro_f1']:.3f} | "
          f"F1 neg/neu/pos {m['f1_negative']:.3f}/{m['f1_neutral']:.3f}/{m['f1_positive']:.3f} | "
          f"inference {m['infer_ms_per_review']:.3f} ms/review")
    if plots:
        fig = _dashboard(name, split, y, proba, pred, m, cal) if plots == "full" else _compact(name, split, y, pred, m)
        if fig_dir is not None:
            safe = "".join(c if c.isalnum() else "_" for c in name)
            fig.savefig(Path(fig_dir) / f"eval_{safe}_{split}.png", bbox_inches="tight")
        plt.show()
    if pred_dir is not None and ids is not None:
        safe = "".join(c if c.isalnum() else "_" for c in name)
        pd.DataFrame({"review_id": ids, "y_true": y, "y_pred": pred, **{f"p_{l}": proba[:, k] for k, l in enumerate(LABELS)}}
                     ).to_parquet(Path(pred_dir) / f"{safe}__{split}.parquet", index=False)
    if book is not None:
        book.record(m)
    return m


def plot_top_features(feature_names, weights, title, top: int = 12, ax_row=None):
    """Bar charts of the features that push most strongly towards each class (one panel per class).

    weights: array (3, n_features); larger = stronger evidence for that class.
    """
    names = np.asarray(feature_names)
    fig, axes = plt.subplots(1, 3, figsize=(16, 0.32 * top + 1.6))
    for k, (ax, lab) in enumerate(zip(axes, LABELS)):
        w = weights[k] - np.delete(weights, k, axis=0).mean(axis=0)   # evidence for k relative to the other two
        idx = np.argsort(-w)[:top][::-1]
        ax.barh(names[idx], w[idx], color=SENT_COLORS[lab], height=0.65)
        ax.set_title(f"Pushes towards {lab}", fontsize=11)
        ax.grid(axis="y", visible=False)
        ax.tick_params(axis="y", labelsize=9)
    fig.suptitle(title, fontweight="bold")
    fig.tight_layout()
    return fig
