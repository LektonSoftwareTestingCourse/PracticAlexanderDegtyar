import sys
from itertools import combinations, product

from allpairspy import AllPairs

PARAM_NAMES = [
    "bin",
    "cardholder_name",
    "currency_code",
    "daily_limit",
    "monthly_limit",
    "initial_balance",
]

PARAMETERS = [
    ["registered_valid", "unregistered_valid", "invalid_format"],
    ["valid", "empty", "invalid_chars"],
    ["valid", "wrong_length", "non_digit"],
    ["positive", "zero", "negative"],
    ["above_daily", "equal_daily", "below_daily", "negative"],
    ["positive", "zero", "negative"],
]

IDX = {name: i for i, name in enumerate(PARAM_NAMES)}


def get(row, name):
    """Значение параметра в частично заполненной строке или None."""
    i = IDX[name]
    return row[i] if i < len(row) else None


def is_valid(row):
    daily = get(row, "daily_limit")
    monthly = get(row, "monthly_limit")

    if daily == "zero" and monthly == "below_daily":
        return False

    return True


def expected_outcome(row):
    d = dict(zip(PARAM_NAMES, row))

    if d["bin"] == "invalid_format":
        return "400", "VALIDATION_ERROR (bin)"
    if d["cardholder_name"] in ("empty", "invalid_chars"):
        return "400", "VALIDATION_ERROR (cardholderName)"
    if d["currency_code"] in ("wrong_length", "non_digit"):
        return "400", "VALIDATION_ERROR (currencyCode)"
    if d["daily_limit"] == "negative":
        return "400", "VALIDATION_ERROR (dailyLimit)"
    if d["monthly_limit"] == "negative":
        return "400", "VALIDATION_ERROR (monthlyLimit)"

    if d["bin"] == "unregistered_valid":
        return "404", "BIN_NOT_FOUND"

    return "201", "CREATED"


def all_valid_rows():
    """Полное множество комбинаций (ограничений нет - все валидны)."""
    return [row for row in product(*PARAMETERS) if is_valid(list(row))]


def pairs_of(row):
    """Все пары «(параметр, значение) x (параметр, значение)» строки."""
    return {
        ((i, row[i]), (j, row[j]))
        for i, j in combinations(range(len(PARAM_NAMES)), 2)
    }


def feasible_pairs(valid_rows):
    """Пары, достижимые хотя бы в одной валидной комбинации."""
    result = set()
    for row in valid_rows:
        result |= pairs_of(row)
    return result


def complete_coverage(rows, valid_rows, target_pairs):
    """Жадно дополняет набор до покрытия всех достижимых пар."""
    covered = set()
    for row in rows:
        covered |= pairs_of(row)

    added = []
    while True:
        missing = target_pairs - covered
        if not missing:
            break
        best = max(valid_rows, key=lambda r: len(pairs_of(r) & missing))
        gain = len(pairs_of(best) & missing)
        if gain == 0:
            break
        added.append(list(best))
        covered |= pairs_of(best)

    return rows + added, len(added), len(target_pairs - covered)


def main():
    base = [list(row) for row in AllPairs(PARAMETERS, filter_func=is_valid)]
    valid_rows = all_valid_rows()
    target = feasible_pairs(valid_rows)

    rows, added, still_missing = complete_coverage(base, valid_rows, target)

    if still_missing:
        print(f"ОШИБКА: не покрыто пар: {still_missing}", file=sys.stderr)
        return 1

    headers = ["#"] + PARAM_NAMES + ["expected_code", "expected_reason"]
    table = []
    for n, row in enumerate(rows, start=1):
        code, reason = expected_outcome(row)
        table.append([f"CM-{n:02d}"] + list(row) + [code, reason])

    widths = [
        max(len(str(r[i])) for r in [headers] + table) for i in range(len(headers))
    ]

    def line(cells):
        return "  ".join(str(c).ljust(widths[i]) for i, c in enumerate(cells)).rstrip()

    print(line(headers))
    print("-" * len(line(headers)))
    for r in table:
        print(line(r))

    print(f"\nfull={len(list(product(*PARAMETERS)))} valid={len(valid_rows)} "
          f"pairs={len(target)} rows={len(rows)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
