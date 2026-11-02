"""
Извлекает из репозитория NotEnoughUpdates-REPO все названия и описания предметов SkyBlock
и превращает их в ключи для перевода.

Правила (должны совпадать с ru.skyrus.ItemLore в моде!):
  * цветовые коды §x убираются, пробелы схлопываются;
  * числа (+50, 1,250, 2.5%, 10s) заменяются на {0}, {1}... по порядку;
  * описание делится на абзацы: подряд идущие непустые строки;
    строка-«подпись» (вида «Strength: +50», до 40 символов перед двоеточием) и строка из одних заглавных
    букв (редкость) всегда идут отдельным абзацем.
"""
import json
import os
import re
import sys

CODE = re.compile(r"§.")
NUM = re.compile(r"[+-]?[0-9][0-9,]*(?:\.[0-9]+)?")
LABEL = re.compile(r"^[^:]{1,40}:\s")
HAS_LETTER = re.compile(r"[A-Za-z]{2,}")


def plain(s):
    return re.sub(r"\s+", " ", CODE.sub("", s)).strip()


def is_single(line):
    p = plain(line)
    if LABEL.match(p):
        return True
    letters = [c for c in p if c.isalpha()]
    return bool(letters) and all(c.isupper() for c in letters) and len(letters) >= 3


def to_key(text):
    nums = []

    def rep(m):
        nums.append(m.group())
        return "{%d}" % (len(nums) - 1)

    return NUM.sub(rep, text), nums


def ends_paragraph(prev, nxt):
    if re.search(r"[.!?]$", prev):
        return True
    if len(prev.split(" ")) <= 4 and not re.search(r"[,;:]$", prev) and nxt[:1].isupper():
        return True
    return False


def paragraphs(lore):
    out, cur = [], []
    for line in lore:
        p = plain(line)
        if cur and p and not is_single(line) and ends_paragraph(cur[-1], p):
            out.append(" ".join(cur)); cur = []
        if not p:
            if cur:
                out.append(" ".join(cur)); cur = []
            continue
        if is_single(line):
            if cur:
                out.append(" ".join(cur)); cur = []
            out.append(p)
            continue
        cur.append(p)
    if cur:
        out.append(" ".join(cur))
    return out


def main(repo, out_path):
    names, texts = {}, {}
    items_dir = os.path.join(repo, "items")
    for fn in sorted(os.listdir(items_dir)):
        if not fn.endswith(".json"):
            continue
        try:
            with open(os.path.join(items_dir, fn), encoding="utf-8") as f:
                it = json.load(f)
        except Exception:
            continue
        name = plain(it.get("displayname", ""))
        if HAS_LETTER.search(name):
            k, nums = to_key(name)
            names.setdefault(k, name)
        for para in paragraphs(it.get("lore", [])):
            if not HAS_LETTER.search(para):
                continue
            if "{" in para or "}" in para:
                continue
            k, nums = to_key(para)
            texts.setdefault(k, para)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"names": names, "texts": texts}, f, ensure_ascii=False, indent=0)
    chars = sum(len(v) for v in names.values()) + sum(len(v) for v in texts.values())
    print(f"names={len(names)} texts={len(texts)} chars={chars}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
