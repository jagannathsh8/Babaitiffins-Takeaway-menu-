#!/usr/bin/env python3
"""Build assets/prep_profile.json + assets/prep_days.txt for PREP LIVE from the BOM and Petpooja item-wise reports.

    python3 make_prep_profile.py BOM.xlsx out.json REPORT1.xlsx [REPORT2.xlsx ...]

Only quantities are kept: per-portion usage of each tracked prepared item, the raw-ingredient
recipe (per kg) of each tracked item, and average usage per weekday/hour. No prices, costs,
KOT ids, tables or staff names leave the source files.
"""
import collections
import datetime
import json
import re
import statistics
import sys

import openpyxl

# Default cards (BOM product name, optional alias). Every other OP/CP item can be picked on the tablet.
DEFAULTS = [
    ("CP - Breakfast Sambar", None),
    ("OP - Peanut chutney", None),
    ("OP - Aloo masala", None),
    ("OP - Aloo curry", "Sagu"),
    ("OP - Upma", None),
    ("OP - New Pongal", None),
    ("CP - Allam Chutney", None),
    ("OP - Tomato chutney", None),
    ("OP - Kesari bath", None),
    ("OP - Dosa batter", None),
    ("OP - Idli batter", None),
]


# Festival / holiday dates in the seed data (behave like Sundays; skipped as history sources).
HOLIDAYS = ["2026-09-14", "2026-10-02"]


def norm(s):
    return re.sub(r"\s+", " ", str(s).strip().lower())


def load_bom(path):
    bom = collections.defaultdict(list)
    uom, label = {}, {}
    ws = openpyxl.load_workbook(path, data_only=True).worksheets[0]
    for r in ws.iter_rows(min_row=2, values_only=True):
        if r[1] and r[2]:
            bom[norm(r[1])].append((r[2].strip(), float(r[8] or 0), (r[4] or "").strip()))
            uom.setdefault(norm(r[1]), (r[12] or "").strip())
            label.setdefault(norm(r[1]), r[1].strip())
            uom.setdefault(norm(r[2]), (r[4] or "").strip())   # ingredient unit (for CP items)
            label.setdefault(norm(r[2]), r[2].strip())
    return bom, uom, label


def prepared(key):
    return key.startswith(("op - ", "cp - "))


def main():
    bom_path, out_path, reports = sys.argv[1], sys.argv[2], sys.argv[3:]
    bom, uom, label = load_bom(bom_path)

    def expand(name, mult, out, depth=0):
        """Adds usage of every prepared (OP/CP) item reached from `name` (recursively)."""
        if depth > 6:
            return
        for ing, q, _ in bom.get(norm(name), []):
            k = norm(ing)
            if prepared(k) and q > 0:
                out[k] += mult * q
                if k in bom and k != norm(name):
                    expand(ing, mult * q, out, depth + 1)

    raw_menu = {}
    for key in bom:
        if prepared(key):
            continue
        out = collections.Counter()
        expand(key, 1.0, out)
        if out:
            raw_menu[key] = out
    targets = sorted({k for out in raw_menu.values() for k in out})
    index = {k: i for i, k in enumerate(targets)}
    menu = {m: {str(index[k]): round(v, 5) for k, v in out.items() if v > 0} for m, out in raw_menu.items()}

    # Keep the BOM names with their OP / CP prefix (as requested by the kitchen team).
    names = [label[k] for k in targets]
    defaults = []
    for bom_name, alias in DEFAULTS:
        k = norm(bom_name)
        if k in index:
            defaults.append(index[k])
            if alias:
                names[index[k]] = label[k] + " (" + alias + ")"
    units = [(uom.get(k) or "kg").lower() for k in targets]

    # recipe breakdown per unit of each target (its direct BOM ingredients)
    recipes = [[[ing, round(q, 5), u]
                for ing, q, u in bom.get(k, []) if q > 0] for k in targets]
    TARGETS = targets  # used below for sizing

    # Rows per (file, date); for dates present in several exports keep the file with most rows.
    per_file_day = collections.defaultdict(list)
    for path in reports:
        ws = openpyxl.load_workbook(path, data_only=True, read_only=True).worksheets[0]
        for i, r in enumerate(ws.iter_rows(values_only=True)):
            if i < 6 or not r or r[0] is None or r[8] is None:
                continue
            if len(r) > 7 and r[7] not in (None, "Success"):
                continue
            t = datetime.datetime.strptime(str(r[8])[:19], "%Y-%m-%d %H:%M:%S")
            per_file_day[(path, t.date())].append(r)
    best = {}
    for (path, d), rows in per_file_day.items():
        if d not in best or len(rows) > len(best[d]):
            best[d] = rows

    use = collections.defaultdict(lambda: collections.defaultdict(lambda: [0.0] * 24))   # date -> t -> hours
    dish = collections.defaultdict(lambda: collections.defaultdict(lambda: [0.0] * 24))  # date -> dish -> hours
    dosa_prep = collections.defaultdict(list)
    for d, rows in best.items():
        for r in rows:
            t = datetime.datetime.strptime(str(r[8])[:19], "%Y-%m-%d %H:%M:%S")
            q = float(r[5] or 0)
            dish[d][str(r[4]).strip()][t.hour] += q
            for ti, v in menu.get(norm(r[4]), {}).items():
                use[d][int(ti)][t.hour] += v * q
            if r[1] == "Dine In" and "dosa" in str(r[4]).lower() and len(r) > 10 and r[10] is not None:
                dosa_prep[(r[0], d)].append(float(r[10]))

    # A day is complete when orders exist from the morning (<= 8 AM) to the night (>= 9 PM).
    complete, partial = [], []
    for d in sorted(dish):
        hours = [h for h in range(24) if any(v[h] > 0 for v in dish[d].values())]
        (complete if hours and min(hours) <= 8 and max(hours) >= 21 else partial).append(d)

    holidays = [h for h in HOLIDAYS if datetime.date.fromisoformat(h) in dish]
    lines = []
    for d in complete:
        for ti, hrs in sorted(use[d].items()):
            cells = " ".join(f"{h}:{round(v, 3)}" for h, v in enumerate(hrs) if v > 0)
            if cells:
                lines.append(f"T|{d}|{ti}|{cells}")
        for name, hrs in sorted(dish[d].items()):
            cells = " ".join(f"{h}:{round(v, 3):g}" for h, v in enumerate(hrs) if v > 0)
            if cells:
                lines.append(f"D|{d}|{name.replace('|', '/')}|{cells}")
    days_path = out_path.rsplit("/", 1)[0] + "/prep_days.txt"
    with open(days_path, "w") as f:
        f.write("\n".join(lines))

    avg_per_day = [round(statistics.mean(sum(use[d][t]) for d in complete), 3) if complete else 0
                   for t in range(len(targets))]
    preps = [max(v) for v in dosa_prep.values()]
    out = {
        "targets": names,
        "units": units,
        "defaults": defaults,
        "menu": menu,
        "recipes": recipes,
        "avgPerDay": avg_per_day,
        "holidays": holidays,
        "seedDays": [str(d) for d in complete],
        "dosaDineInAvgPrepMin": round(statistics.mean(preps), 2) if preps else None,
    }
    with open(out_path, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"{len(menu)} menu items, {len(targets)} prep items, {len(complete)} complete days "
          f"({complete[0]}..{complete[-1]}), skipped partial {[str(d) for d in partial]}, holidays {holidays}, "
          f"dosa avg prep {out['dosaDineInAvgPrepMin']} min -> {out_path} + {days_path}")


if __name__ == "__main__":
    main()
