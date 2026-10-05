#!/usr/bin/env python3
"""Build assets/prep_profile.json for PREP LIVE from the BOM and Petpooja item-wise reports.

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

    # usage per (date, hour) from the reports
    use = collections.defaultdict(lambda: [0.0] * len(TARGETS))
    seen = set()
    dosa_prep = collections.defaultdict(list)
    for path in reports:
        ws = openpyxl.load_workbook(path, data_only=True, read_only=True).worksheets[0]
        for i, r in enumerate(ws.iter_rows(values_only=True)):
            if i < 6 or not r or r[0] is None or r[8] is None:
                continue
            if len(r) > 7 and r[7] not in (None, "Success"):
                continue
            t = datetime.datetime.strptime(str(r[8])[:19], "%Y-%m-%d %H:%M:%S")
            row_key = (r[0], str(r[8]), r[4], r[5])
            if row_key in seen:  # overlapping exports
                continue
            seen.add(row_key)
            for ti, v in menu.get(norm(r[4]), {}).items():
                use[(t.date(), t.hour)][int(ti)] += v * float(r[5] or 0)
            if r[1] == "Dine In" and "dosa" in str(r[4]).lower() and len(r) > 10 and r[10] is not None:
                dosa_prep[(r[0], t.date())].append(float(r[10]))

    days = sorted({d for d, _ in use})
    full_days = days[1:-1] if len(days) > 2 else days  # first/last export day may be partial
    by_wd = collections.defaultdict(list)
    for d in full_days:
        by_wd[d.weekday()].append(d)
    # profile[target][weekday][hour] = average kg (weekday 0 = Monday)
    profile = []
    for ti in range(len(TARGETS)):
        overall = [statistics.mean(use[(d, h)][ti] for d in full_days) for h in range(24)]
        wk = []
        for wd in range(7):
            ds = by_wd.get(wd)
            wk.append([round(statistics.mean(use[(d, h)][ti] for d in ds), 3) if ds else round(overall[h], 3)
                       for h in range(24)])
        profile.append(wk)

    preps = [max(v) for v in dosa_prep.values()]
    out = {
        "targets": names,
        "units": units,
        "defaults": defaults,
        "menu": menu,
        "recipes": recipes,
        "profile": profile,
        "profileDays": [str(d) for d in full_days],
        "dosaDineInAvgPrepMin": round(statistics.mean(preps), 2) if preps else None,
    }
    with open(out_path, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"{len(menu)} menu items, {len(full_days)} profile days ({full_days[0]}..{full_days[-1]}), "
          f"dosa avg prep {out['dosaDineInAvgPrepMin']} min -> {out_path}")


if __name__ == "__main__":
    main()
