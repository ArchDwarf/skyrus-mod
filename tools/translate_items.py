"""
Переводит всё, что извлёк extract_items.py, на русский (бесплатный Google Translate) и пишет
src/main/resources/assets/skyrus/items_ru.json  =  {"names": {ключ: перевод}, "texts": {ключ: перевод}}.

Работает инкрементально: уже переведённые ключи из прошлого items_ru.json не переводятся заново.
Числа в переводе заменяются обратно на {0}, {1}..., чтобы перевод подходил к любым значениям.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

SRC_NUM = re.compile(r"[+-]?[0-9][0-9,]*(?:\.[0-9]+)?")
# В русском переводе Google может написать «1 250» или «2,5»
TR_NUM = re.compile(r"[+-]?[0-9](?:[0-9   ,.]*[0-9])?")
BATCH_CHARS = 1800
SEP = "\n"


def norm_src(n):
    return n.lstrip("+-").replace(",", "")


def norm_tr(n):
    n = n.lstrip("+-").replace(" ", "").replace(" ", "").replace(" ", "")
    if re.fullmatch(r"\d+,\d{1,2}", n):          # 2,5 -> 2.5
        n = n.replace(",", ".")
    return n.replace(",", "")


def to_template(source_text, translated):
    """Меняет числа в переводе на {i}. Возвращает None, если числа не сошлись."""
    src_nums = SRC_NUM.findall(source_text)
    if not src_nums:
        return translated
    by_value = {}
    for i, n in enumerate(src_nums):
        by_value.setdefault(norm_src(n), i)
    tr_nums = list(TR_NUM.finditer(translated))
    used = set()
    out, last = [], 0
    ok = True
    for m in tr_nums:
        v = norm_tr(m.group())
        if v in by_value:
            idx = by_value[v]
        else:
            ok = False
            break
        used.add(idx)
        out.append(translated[last:m.start()])
        out.append("{%d}" % idx)
        last = m.end()
    out.append(translated[last:])
    # Повторяющиеся значения (например две «5») делят один индекс — это нормально
    needed = {by_value[norm_src(n)] for n in src_nums}
    if ok and used == needed:
        return "".join(out)
    # Запасной вариант: столько же чисел и в том же порядке
    if len(tr_nums) == len(src_nums):
        out, last = [], 0
        for i, m in enumerate(tr_nums):
            out.append(translated[last:m.start()])
            out.append("{%d}" % i)
            last = m.end()
        out.append(translated[last:])
        return "".join(out)
    return None


def google(text, tries=6):
    url = ("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t&q="
           + urllib.parse.quote(text))
    delay = 2
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=20) as r:
                data = json.loads(r.read().decode("utf-8"))
            return "".join(p[0] for p in data[0] if p[0])
        except Exception as e:
            print(f"  ошибка ({e}), повтор через {delay} с", flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 120)
    raise RuntimeError("Google Translate недоступен")


def translate_many(texts):
    """Переводит список строк пачками; при несовпадении числа строк — по одной."""
    result = {}
    batch = []
    size = 0

    def flush():
        nonlocal batch, size
        if not batch:
            return
        tr = google(SEP.join(batch))
        parts = tr.split(SEP)
        if len(parts) == len(batch):
            for s, t in zip(batch, parts):
                result[s] = t.strip()
        else:
            for s in batch:
                result[s] = google(s).strip()
                time.sleep(0.3)
        batch, size = [], 0
        time.sleep(0.5)

    try:
        for i, t in enumerate(texts):
            if size + len(t) + 1 > BATCH_CHARS:
                flush()
            batch.append(t)
            size += len(t) + 1
            if i % 500 == 0:
                print(f"  {i}/{len(texts)}", flush=True)
        flush()
    except RuntimeError as e:
        # Google заблокировал запросы — сохраняем то, что успели; следующий запуск продолжит
        print(f"Остановка: {e}. Переведено {len(result)} из {len(texts)}.", flush=True)
    return result


def main(extracted_path, out_path):
    with open(extracted_path, encoding="utf-8") as f:
        src = json.load(f)
    old = {"names": {}, "texts": {}}
    if os.path.exists(out_path):
        with open(out_path, encoding="utf-8") as f:
            old = json.load(f)

    out = {"_info": "SkyRus: переводы названий и описаний предметов SkyBlock (источник — NotEnoughUpdates-REPO). "
                    "Сгенерировано tools/translate_items.py. {0},{1}... — числа.",
           "names": {}, "texts": {}}
    for section in ("names", "texts"):
        todo = {}
        for key, text in src[section].items():
            if key in old.get(section, {}):
                out[section][key] = old[section][key]
            else:
                todo[key] = text
        print(f"{section}: готово {len(out[section])}, перевести {len(todo)}", flush=True)
        if not todo:
            continue
        uniq = sorted(set(todo.values()))
        tr = translate_many(uniq)
        bad = 0
        for key, text in todo.items():
            t = tr.get(text)
            tpl = to_template(text, t) if t else None
            if tpl is None or not tpl.strip():
                bad += 1
                continue
            out[section][key] = tpl
        print(f"{section}: не удалось сопоставить числа в {bad} строках (их переведёт автоперевод в игре)", flush=True)

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=0, sort_keys=True)
    print(f"Итого: names={len(out['names'])} texts={len(out['texts'])}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
