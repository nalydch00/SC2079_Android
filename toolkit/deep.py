"""PyTorch models and training utilities shared by the BiLSTM, attention-BiLSTM and DistilBERT models."""
import json
import math
import re
import time
from collections import Counter

import numpy as np
import torch
import torch.nn as nn
from sklearn.metrics import f1_score
from torch.nn.utils.rnn import pack_padded_sequence, pad_packed_sequence

_TOKEN = re.compile(r"[a-z0-9]+(?:'[a-z]+)?|[!?]")


def word_tokenize(text: str) -> list:
    """Lower-case word tokens; '!' and '?' kept as tokens because they carry emotion."""
    return _TOKEN.findall(text.lower())


class Vocab:
    PAD, UNK = 0, 1

    def __init__(self, itos):
        self.itos = list(itos)
        self.stoi = {w: i for i, w in enumerate(self.itos)}

    @classmethod
    def build(cls, token_lists, max_size=30_000, min_freq=2):
        counts = Counter(t for toks in token_lists for t in toks)
        words = [w for w, c in counts.most_common(max_size - 2) if c >= min_freq]
        return cls(["<pad>", "<unk>"] + words)

    def encode(self, tokens, max_len):
        ids = [self.stoi.get(t, self.UNK) for t in tokens[:max_len]]
        return ids or [self.UNK]

    def save(self, path):
        with open(path, "w") as f:
            json.dump(self.itos, f)

    @classmethod
    def load(cls, path):
        with open(path) as f:
            return cls(json.load(f))

    def __len__(self):
        return len(self.itos)


class BucketBatches:
    """Yields lists of indices; each batch holds reviews of similar length (less padding = faster)."""

    def __init__(self, lengths, batch_size, shuffle, seed=0):
        self.lengths, self.bs, self.shuffle, self.seed, self.epoch = np.asarray(lengths), batch_size, shuffle, seed, 0

    def __iter__(self):
        rng = np.random.default_rng(self.seed + self.epoch)
        self.epoch += 1
        idx = rng.permutation(len(self.lengths)) if self.shuffle else np.arange(len(self.lengths))
        chunk = self.bs * 50
        batches = []
        for s in range(0, len(idx), chunk):
            part = idx[s:s + chunk]
            part = part[np.argsort(self.lengths[part], kind="stable")]
            batches += [part[i:i + self.bs] for i in range(0, len(part), self.bs)]
        if self.shuffle:
            rng.shuffle(batches)
        return iter(batches)

    def __len__(self):
        return math.ceil(len(self.lengths) / self.bs)


def make_batches(seqs, labels, batch_size, shuffle, seed=0):
    """Generator of padded batches (dicts of tensors) from lists of token-id sequences."""
    sampler = BucketBatches([len(s) for s in seqs], batch_size, shuffle, seed)

    class _Loader:
        def __len__(self):
            return len(sampler)

        def __iter__(self):
            for b in sampler:
                lens = [len(seqs[i]) for i in b]
                ids = torch.zeros(len(b), max(lens), dtype=torch.long)
                for r, i in enumerate(b):
                    ids[r, :lens[r]] = torch.tensor(seqs[i])
                batch = {"input_ids": ids, "attention_mask": (ids != 0).long(), "index": torch.tensor(b)}
                if labels is not None:
                    batch["labels"] = torch.tensor(np.asarray(labels)[b], dtype=torch.long)
                yield batch

    return _Loader()


class BiLSTMClassifier(nn.Module):
    """Embedding -> bidirectional LSTM -> [mean-pool ; max-pool] -> dropout -> linear."""

    def __init__(self, vocab_size, emb_dim=128, hidden=128, dropout=0.4, n_classes=3):
        super().__init__()
        self.emb = nn.Embedding(vocab_size, emb_dim, padding_idx=0)
        self.lstm = nn.LSTM(emb_dim, hidden, batch_first=True, bidirectional=True)
        self.drop = nn.Dropout(dropout)
        self.fc = nn.Linear(4 * hidden, n_classes)

    def encode(self, input_ids, attention_mask):
        lengths = attention_mask.sum(1).clamp(min=1)
        x = self.drop(self.emb(input_ids))
        packed = pack_padded_sequence(x, lengths.cpu(), batch_first=True, enforce_sorted=False)
        out, _ = self.lstm(packed)
        out, _ = pad_packed_sequence(out, batch_first=True, total_length=input_ids.size(1))
        return out, lengths

    def forward(self, input_ids, attention_mask, **_):
        out, lengths = self.encode(input_ids, attention_mask)
        mask = attention_mask.unsqueeze(-1).bool()
        mean = (out * mask).sum(1) / lengths.unsqueeze(1)
        mx = out.masked_fill(~mask, -1e4).max(1).values
        return self.fc(self.drop(torch.cat([mean, mx], dim=1)))


class AttentionBiLSTMClassifier(BiLSTMClassifier):
    """BiLSTM whose word outputs are combined by learned attention weights (one weight per word)."""

    def __init__(self, vocab_size, emb_dim=128, hidden=128, dropout=0.4, n_classes=3, attn_dim=128):
        super().__init__(vocab_size, emb_dim, hidden, dropout, n_classes)
        self.attn = nn.Sequential(nn.Linear(2 * hidden, attn_dim), nn.Tanh(), nn.Linear(attn_dim, 1, bias=False))
        self.fc = nn.Linear(2 * hidden, n_classes)

    def forward(self, input_ids, attention_mask, return_attention=False, **_):
        out, _ = self.encode(input_ids, attention_mask)
        scores = self.attn(out).squeeze(-1).masked_fill(attention_mask == 0, -1e4)
        alpha = torch.softmax(scores, dim=1)                       # (batch, words), sums to 1 per review
        context = (alpha.unsqueeze(-1) * out).sum(1)
        logits = self.fc(self.drop(context))
        return (logits, alpha) if return_attention else logits


class HFClassifier(nn.Module):
    """Wrap a Hugging Face sequence-classification model so it returns plain logits."""

    def __init__(self, hf_model):
        super().__init__()
        self.hf = hf_model

    def forward(self, input_ids, attention_mask, **_):
        return self.hf(input_ids=input_ids, attention_mask=attention_mask).logits


@torch.no_grad()
def predict_proba(model, loader, device, n=None):
    """Class probabilities in the ORIGINAL order of the inputs (batches are length-sorted internally)."""
    model.eval()
    out, order = [], []
    for batch in loader:
        logits = model(batch["input_ids"].to(device), batch["attention_mask"].to(device))
        out.append(torch.softmax(logits.float(), -1).cpu().numpy())
        order.append(batch["index"].numpy())
    probs, order = np.concatenate(out), np.concatenate(order)
    res = np.zeros_like(probs)
    res[order] = probs
    return res


def train_classifier(model, train_loader, val_loader, y_val, *, epochs, lr, ckpt_path, device,
                     weight_decay=0.01, warmup_frac=0.0, patience=2, grad_clip=1.0, evals_per_epoch=1, log=print):
    """AdamW + linear warm-up/decay, early stopping on validation macro F1, best weights saved to ckpt_path."""
    model.to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=weight_decay)
    total = epochs * len(train_loader)
    warm = int(warmup_frac * total)
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: (s + 1) / max(1, warm) if s < warm else max(0.0, (total - s) / max(1, total - warm)))
    loss_fn = nn.CrossEntropyLoss()
    eval_at = {int(len(train_loader) * (k + 1) / evals_per_epoch) for k in range(evals_per_epoch)}
    best_f1, bad, step, history, t0 = -1.0, 0, 0, [], time.time()
    for epoch in range(1, epochs + 1):
        model.train()
        run_loss, n_seen = 0.0, 0
        for i, batch in enumerate(train_loader, start=1):
            logits = model(batch["input_ids"].to(device), batch["attention_mask"].to(device))
            loss = loss_fn(logits, batch["labels"].to(device))
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), grad_clip)
            opt.step()
            sched.step()
            step += 1
            run_loss += loss.item() * len(batch["labels"])
            n_seen += len(batch["labels"])
            if i % max(1, len(train_loader) // 5) == 0 and i not in eval_at:
                log(f"  epoch {epoch} | batch {i}/{len(train_loader)} | train loss {run_loss / n_seen:.4f} | {time.time() - t0:.0f}s")
            if i in eval_at:
                proba = predict_proba(model, val_loader, device)
                f1 = f1_score(y_val, proba.argmax(1), average="macro")
                vloss = float(-np.log(np.clip(proba[np.arange(len(y_val)), y_val], 1e-9, 1)).mean())
                improved = f1 > best_f1
                history.append({"epoch": round(epoch - 1 + i / len(train_loader), 2), "train_loss": run_loss / n_seen,
                                "val_loss": vloss, "val_macro_f1": f1, "elapsed_s": time.time() - t0})
                log(f"  epoch {history[-1]['epoch']:.2f} | train loss {run_loss / n_seen:.4f} | val loss {vloss:.4f} | "
                    f"val macro F1 {f1:.4f}{'  <- best, saved' if improved else ''} | {time.time() - t0:.0f}s")
                if improved:
                    best_f1, bad = f1, 0
                    torch.save(model.state_dict(), ckpt_path)
                else:
                    bad += 1
                model.train()
                if bad >= patience:
                    log(f"  early stopping: no improvement in {patience} evaluations")
                    return history, time.time() - t0
    return history, time.time() - t0
