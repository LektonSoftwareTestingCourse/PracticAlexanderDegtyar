import sys
from itertools import combinations, product

from allpairspy import AllPairs

PARAM_NAMES = [
    "card_status",
    "amount_vs_daily",
    "amount_vs_monthly",
    "amount_vs_balance",
    "expiry",
    "terminal_type",
    "mcc",
]

PARAMETERS = [
    ["ACTIVE", "INACTIVE", "BLOCKED", "EXPIRED"],
    ["below", "equal", "above"],
    ["below", "equal", "above"],
    ["below", "equal", "above"],
    ["valid", "current_month", "expired"],
    ["pos", "atm", "ecom"],
    ["grocery", "restaurant", "electronics", "travel"],
]

IDX = {name: i for i, name in enumerate(PARAM_NAMES)}


def get(row, name):
    """Значение параметра в частично заполненной строке или None."""
    i = IDX[name]
    return row[i] if i < len(row) else None


def is_valid(row):
    status = get(row, "card_status")
    daily = get(row, "amount_vs_daily")
    monthly = get(row, "amount_vs_monthly")
    balance = get(row, "amount_vs_balance")
    expiry = get(row, "expiry")

    # C1: неактивная карта отклоняется до проверок сумм
    if status is not None and status != "ACTIVE":
        for value in (daily, monthly, balance):
            if value is not None and value != "below":
                return False

    # C2: просроченная карта отклоняется до проверки баланса
    if expiry == "expired" and balance is not None and balance != "below":
        return False

    # C3: monthlyLimit = dailyLimit * 30, превышение месячного влечёт дневное
    if monthly == "above" and daily is not None and daily != "above":
        return False

    # C4: статус EXPIRED согласован с просроченной датой
    if status == "EXPIRED" and expiry is not None and expiry != "expired":
        return False

    return True


def expected_outcome(row):
    d = dict(zip(PARAM_NAMES, row))

    if d["card_status"] == "EXPIRED":
        return "54", "CARD_EXPIRED"
    if d["card_status"] == "BLOCKED":
        return "05", "CARD_BLOCKED"
    if d["card_status"] == "INACTIVE":
        return "05", "CARD_INACTIVE"

    if d["expiry"] == "expired":
        return "54", "CARD_EXPIRED"

    if d["amount_vs_balance"] == "above":
        return "51", "INSUFFICIENT_FUNDS"

    if d["amount_vs_daily"] == "above" or d["amount_vs_monthly"] == "above":
        return "61", "EXCEEDS_AMOUNT_LIMIT"

    return "00", "-"


def all_valid_rows():
    """Полное множество комбинаций, проходящих ограничения."""
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
        table.append([f"PW-{n:02d}"] + list(row) + [code, reason])

    widths = [
        max(len(str(r[i])) for r in [headers] + table) for i in range(len(headers))
    ]

    def line(cells):
        return "  ".join(str(c).ljust(widths[i]) for i, c in enumerate(cells)).rstrip()

    print(line(headers))
    print("-" * len(line(headers)))
    for r in table:
        print(line(r))
    return 0


if __name__ == "__main__":
    sys.exit(main())
