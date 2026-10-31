#!/usr/bin/env python3
"""Public, read-only Polymarket data reader. All network requests use curl."""
import argparse
import json
import math
import re
import subprocess
import sys
import time
import unicodedata
from urllib.parse import urlencode

BASE_URL = "https://data-api.polymarket.com"
USER_AGENT = "FluwyBot-Polymarket-Connector/1.0"
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
POSITIONS_PAGE_SIZE = 1000
MAX_POSITION_PAGES = 2
WINDOW_SECONDS = 90 * 24 * 60 * 60


def fetch_json(path, params):
    url = BASE_URL + path + "?" + urlencode(params)
    args = [
        "curl", "--fail", "--silent", "--show-error", "--location",
        "--max-time", "25", "--retry", "2", "--retry-delay", "1",
        "--retry-max-time", "60", "--max-filesize", str(MAX_RESPONSE_BYTES),
        "-H", "Accept: application/json", "-H", "User-Agent: " + USER_AGENT,
        url,
    ]
    try:
        result = subprocess.run(args, capture_output=True, timeout=75)
    except FileNotFoundError as exc:
        raise RuntimeError("curl não está instalado no sandbox Linux.") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("curl excedeu o limite de tempo para consultar a API pública.") from exc
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip().replace("\n", " ")[:180]
        raise RuntimeError("curl falhou (código " + str(result.returncode) + ")" + (": " + detail if detail else "."))
    if len(result.stdout) > MAX_RESPONSE_BYTES:
        raise RuntimeError("Resposta da API excedeu o limite local de leitura.")
    try:
        value = json.loads(result.stdout.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError("A API pública retornou JSON inválido.") from exc
    time.sleep(0.25)
    return value


def safe_number(value):
    try:
        number = float(value)
        return number if math.isfinite(number) else None
    except (TypeError, ValueError, OverflowError):
        return None


def safe_epoch(value):
    number = safe_number(value)
    return int(number) if number is not None else None


def safe_label(value, fallback):
    text = "".join(" " if unicodedata.category(ch).startswith("C") else ch for ch in str(value or ""))
    return " ".join(text.split()).strip()[:64] or fallback


def is_evm_address(value):
    return isinstance(value, str) and re.fullmatch(r"0x[a-fA-F0-9]{40}", value) is not None


def leaderboards(limit):
    candidates = {}
    for period in ("month", "all"):
        root = fetch_json("/v2/leaderboard", {
            "time_period": period,
            "sort_by": "PNL",
            "category": "overall",
            "limit": str(limit),
        })
        rows = root.get("data") if isinstance(root, dict) else None
        if not isinstance(rows, list):
            raise RuntimeError("Formato inesperado no leaderboard público da Polymarket.")
        for row in rows:
            if not isinstance(row, dict):
                continue
            wallet = str(row.get("user_id", "")).strip()
            if not is_evm_address(wallet):
                continue
            key = wallet.lower()
            item = candidates.setdefault(key, {
                "wallet": wallet,
                "display": safe_label(row.get("user_name"), wallet),
                "month_rank": None,
                "month_pnl": None,
                "all_rank": None,
                "all_pnl": None,
            })
            if period == "month":
                item["month_rank"] = safe_epoch(row.get("rank"))
                item["month_pnl"] = safe_number(row.get("pnl"))
            else:
                item["all_rank"] = safe_epoch(row.get("rank"))
                item["all_pnl"] = safe_number(row.get("pnl"))
            label = safe_label(row.get("user_name"), wallet)
            if not item["display"] or item["display"] == wallet:
                item["display"] = label
    return candidates


def money(value):
    return format(value, ",.2f")


def list_wallets(limit):
    candidates = leaderboards(limit)
    print("Polymarket — carteiras encontradas nos leaderboards públicos; somente leitura.")
    print("Fonte: Data API v2 pública, sem login ou API key. Limite de até " + str(limit) + " entradas em cada leaderboard.")
    if not candidates:
        print("Nenhuma carteira EVM válida foi retornada nesta amostra.")
        return
    for item in candidates.values():
        sources = []
        if item["month_rank"] is not None:
            sources.append("mensal #" + str(item["month_rank"]))
        if item["all_rank"] is not None:
            sources.append("acumulado #" + str(item["all_rank"]))
        metrics = []
        if item["month_pnl"] is not None:
            metrics.append("PnL mensal reportado US$ " + money(item["month_pnl"]))
        if item["all_pnl"] is not None:
            metrics.append("PnL acumulado reportado US$ " + money(item["all_pnl"]))
        print("- " + item["display"] + " | " + item["wallet"] + " | " + " + ".join(sources) + (" | " + "; ".join(metrics) if metrics else ""))
    print("Os PnLs exibidos são os valores dos respectivos leaderboards (mensal ou vitalício), não uma métrica de 90 dias nem promessa de resultado futuro.")


def closed_positions(wallet, start, end):
    collected = []
    cursor = None
    has_more = False
    pages = 0
    while pages < MAX_POSITION_PAGES:
        params = {
            "user": wallet,
            "status": "CLOSED",
            "start": str(start),
            "end": str(end),
            "sortBy": "REALIZED_PNL",
            "sortDirection": "DESC",
            "limit": str(POSITIONS_PAGE_SIZE),
        }
        if cursor:
            params["cursor"] = cursor
        root = fetch_json("/v2/positions", params)
        rows = root.get("data") if isinstance(root, dict) else None
        if not isinstance(rows, list):
            raise RuntimeError("Formato inesperado ao consultar posições fechadas.")
        collected.extend(row for row in rows if isinstance(row, dict))
        pagination = root.get("pagination") if isinstance(root, dict) else None
        has_more = isinstance(pagination, dict) and pagination.get("has_more") is True
        cursor = pagination.get("next_cursor") if isinstance(pagination, dict) else None
        pages += 1
        if not has_more:
            break
        if not cursor:
            return collected, True
    return collected, has_more


def summarize(item, positions, start, end, page_limit_reached):
    cohort = []
    for position in positions:
        first = safe_epoch(position.get("first_entry_at"))
        last = safe_epoch(position.get("last_event_at"))
        if first is None or last is None or not (start <= first <= end and start <= last <= end):
            continue
        cohort.append(safe_number(position.get("realized_pnl")))
    known = [value for value in cohort if value is not None]
    return {
        "candidate": item,
        "pnl": sum(known),
        "count": len(cohort),
        "positive": sum(value > 0 for value in known),
        "negative": sum(value < 0 for value in known),
        "zero": sum(value == 0 for value in known),
        "missing": len(cohort) - len(known),
        "page_limit": page_limit_reached,
        "error": None,
    }


def analyze_wallets(limit):
    now = int(time.time())
    start = now - WINDOW_SECONDS
    candidates = leaderboards(limit)
    results = []
    for item in candidates.values():
        try:
            positions, capped = closed_positions(item["wallet"], start, now)
            results.append(summarize(item, positions, start, now, capped))
        except Exception as exc:
            results.append({"candidate": item, "pnl": 0.0, "count": 0, "positive": 0,
                            "negative": 0, "zero": 0, "missing": 0, "page_limit": False,
                            "error": " ".join(str(exc).split())[:160] or exc.__class__.__name__})
    results.sort(key=lambda result: result["pnl"], reverse=True)
    print("Polymarket — análise pública e somente leitura; janela móvel de 90 dias (90 × 24 h).")
    print("Amostra: até " + str(limit) + " carteiras do leaderboard mensal e até " + str(limit) + " do acumulado; " + str(len(candidates)) + " endereços EVM únicos consultados.")
    print("Métrica: soma do realized_pnl de posições CLOSED cujo first_entry_at e last_event_at estão ambos dentro da janela. Até " + str(MAX_POSITION_PAGES) + " páginas de " + str(POSITIONS_PAGE_SIZE) + " posições por carteira.")
    print("O ranking não é exaustivo, não inclui posições abertas, entradas anteriores ao período nem representa o PnL total da conta.")
    failed = sum(result["error"] is not None for result in results)
    if failed:
        print("Consultas de posições com falha: " + str(failed) + " (resultados parciais).")
    for index, result in enumerate(results, 1):
        item = result["candidate"]
        sources = []
        if item["month_rank"] is not None:
            sources.append("leaderboard mensal #" + str(item["month_rank"]))
        if item["all_rank"] is not None:
            sources.append("leaderboard acumulado #" + str(item["all_rank"]))
        line = str(index) + ". " + item["display"] + " (" + item["wallet"] + ") — "
        if result["error"]:
            line += "métricas da janela indisponíveis; fonte: " + " + ".join(sources) + ". Erro: " + result["error"]
        else:
            line += "PnL realizado da coorte fechada US$ " + money(result["pnl"]) + "; " + str(result["count"]) + " posições"
            if result["count"] > 0 and result["missing"] == 0:
                rate = result["positive"] * 100.0 / result["count"]
                line += "; " + format(rate, ".2f") + "% positivas"
            line += "; fonte da amostra: " + " + ".join(sources or ["leaderboard público"])
            if result["missing"]:
                line += "; " + str(result["missing"]) + " posições sem PnL disponível"
            if result["page_limit"]:
                line += "; paginação limitada (há mais posições que as páginas lidas)"
            if item["month_pnl"] is not None:
                line += "; leaderboard mensal (janela de mês): US$ " + money(item["month_pnl"])
            if item["all_pnl"] is not None:
                line += "; leaderboard acumulado (vitalício): US$ " + money(item["all_pnl"])
            line += "."
        print(line)
    print("Fonte: Polymarket Data API v2 pública, sem API key. Resultados passados não indicam retorno futuro. Nenhuma ordem foi criada, copiada ou enviada.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("list_wallets", "analyze_wallets"))
    parser.add_argument("--limit", type=int, default=10)
    args = parser.parse_args()
    if args.limit < 1 or args.limit > 10:
        raise RuntimeError("O limite deve estar entre 1 e 10 por leaderboard.")
    if args.action == "list_wallets":
        list_wallets(args.limit)
    else:
        analyze_wallets(args.limit)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("ERRO Polymarket: " + " ".join(str(exc).split())[:260], file=sys.stderr)
        sys.exit(2)
