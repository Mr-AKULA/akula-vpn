"""Minimal diagnostic ingestion. No traffic, messages, accounts or device identifiers."""
import hashlib
import html
import json
import os
import re
import sqlite3
import threading
import time
from collections import OrderedDict
from contextlib import contextmanager
from pathlib import Path
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from routers.admin import require_admin

router = APIRouter()
DB_PATH = Path(os.environ.get("AKULA_REPORTS_DB", "data/app-reports.sqlite3"))
_lock = threading.Lock()
_limits = OrderedDict()
_salt = os.urandom(32)


@contextmanager
def _db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=5)
    db.execute("CREATE TABLE IF NOT EXISTS reports (id TEXT PRIMARY KEY, received INTEGER NOT NULL, fingerprint TEXT NOT NULL, body TEXT NOT NULL)")
    db.execute("CREATE INDEX IF NOT EXISTS reports_received ON reports(received)")
    try:
        with db:
            yield db
    finally:
        db.close()


def _validate(data):
    fields = {"id", "kind", "operation", "app_version", "platform", "os_version", "occurred_at_ms", "exception_type", "frames", "causes"}
    if not isinstance(data, dict) or not fields <= set(data) or set(data) - fields - {"context"}:
        raise ValueError("Unexpected fields")
    if "context" in data:
        context = data["context"]
        required = {"protocol", "stage", "reason"}
        if not isinstance(context, dict) or not required <= set(context) or set(context) - required - {"elapsed_ms", "attempt", "exit_code"}:
            raise ValueError("Unexpected context fields")
        for field, allowed in (
            ("protocol", {"unknown", "amnezia_wg", "openvpn", "olcrtc"}),
            ("stage", {"unknown", "validate_profile", "start_tunnel", "wait_handshake", "wait_connection", "authenticate", "start_transport", "establish_tun", "running", "split_tunnel"}),
            ("reason", {"unknown_state", "handshake_timeout", "connection_timeout", "auth_rejected", "profile_invalid", "start_failed", "transport_failed", "native_unavailable", "tun_failed", "permission_required", "no_profile", "unsupported_platform", "split_apps_invalid", "process_exited", "all_attempts_failed"}),
        ):
            if not isinstance(context[field], str) or context[field] not in allowed:
                raise ValueError("Invalid context " + field)
        for field, low, high in (("elapsed_ms", 0, 86400000), ("attempt", 1, 10000), ("exit_code", -2147483648, 2147483647)):
            if field in context and (type(context[field]) is not int or not low <= context[field] <= high):
                raise ValueError("Invalid context " + field)
    if str(UUID(data["id"])) != data["id"]:
        raise ValueError("Invalid id")
    if data["kind"] not in ("error", "crash") or data["platform"] not in ("android", "windows", "linux", "macos"):
        raise ValueError("Invalid kind or platform")
    for field, limit, pattern in (
        ("operation", 64, r"[A-Za-z0-9._-]+"),
        ("app_version", 32, r"[A-Za-z0-9._-]+"),
        ("os_version", 64, r"[A-Za-z0-9._ -]+"),
        ("exception_type", 180, r"[A-Za-z0-9._$<>-]+"),
    ):
        value = data[field]
        if not isinstance(value, str) or len(value) > limit or not re.fullmatch(pattern, value):
            raise ValueError("Invalid " + field)
    if type(data["occurred_at_ms"]) is not int or not 0 < data["occurred_at_ms"] < 4102444800000:
        raise ValueError("Invalid timestamp")
    for name, count, pattern in (("frames", 48, r"[A-Za-z0-9._$<>-]+:-?[0-9]{1,6}"), ("causes", 4, r"[A-Za-z0-9._$<>-]+")):
        values = data[name]
        if not isinstance(values, list) or len(values) > count or any(not isinstance(v, str) or len(v) > 370 or not re.fullmatch(pattern, v) for v in values):
            raise ValueError("Invalid " + name)
    return data


@router.post("/api/app-reports", status_code=202)
async def ingest(request: Request):
    # In-memory salted bucket: no IP addresses are written to the diagnostics database.
    key = hashlib.sha256(_salt + (request.client.host if request.client else "unknown").encode()).digest()
    now = int(time.time())
    with _lock:
        start, count = _limits.get(key, (now, 0))
        if now - start >= 60:
            start, count = now, 0
        if count >= 120:
            raise HTTPException(429, "Report rate limit", headers={"Retry-After": "60"})
        _limits[key] = (start, count + 1)
        _limits.move_to_end(key)
        if len(_limits) > 4096:
            _limits.popitem(last=False)
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > 32768:
            raise HTTPException(413, "Report too large")
    try:
        data = _validate(json.loads(body))
    except (ValueError, TypeError, KeyError, AttributeError):
        raise HTTPException(422, "Invalid diagnostic report")
    canonical = json.dumps(data, ensure_ascii=True, separators=(",", ":"))
    signature = [data["kind"], data["operation"], data["exception_type"], data["frames"][:4]]
    if "context" in data:
        signature += [data["context"][field] for field in ("protocol", "stage", "reason")]
    fingerprint = hashlib.sha256(json.dumps(signature).encode()).hexdigest()[:16]
    with _lock, _db() as db:
        # Bounded storage; never displace an existing report to accept an upload flood.
        if db.execute("SELECT 1 FROM reports WHERE id=?", (data["id"],)).fetchone():
            return {"accepted": True, "id": data["id"]}
        if db.execute("SELECT count(*) FROM reports").fetchone()[0] >= 10000:
            raise HTTPException(503, "Diagnostic storage full", headers={"Retry-After": "3600"})
        db.execute("INSERT OR IGNORE INTO reports VALUES (?, ?, ?, ?)", (data["id"], now, fingerprint, canonical))
    return {"accepted": True, "id": data["id"]}


def _rows(limit=200):
    with _db() as db:
        return [{"received_at": received, "fingerprint": fingerprint, "report": json.loads(body)} for received, fingerprint, body in db.execute("SELECT received, fingerprint, body FROM reports ORDER BY received DESC, rowid DESC LIMIT ?", (max(1, min(limit, 1000)),))]


@router.get("/api/admin/app-reports/json")
def report_json(limit: int = 200, admin=Depends(require_admin)):
    return JSONResponse({"reports": _rows(limit)}, headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})


@router.get("/api/admin/app-reports", response_class=HTMLResponse)
def report_page(admin=Depends(require_admin)):
    cards = []
    for row in _rows():
        data = row["report"]
        title = f'{data["app_version"]} · {data["platform"]} {data["os_version"]} · {data["kind"]} · {data["operation"]}'
        context = data.get("context")
        if context:
            reasons = {
                "handshake_timeout": "Нет рукопожатия за отведённое время",
                "connection_timeout": "Истекло время подключения",
                "auth_rejected": "Сервер отклонил авторизацию",
                "profile_invalid": "Не удалось прочитать профиль",
                "start_failed": "Не удалось запустить компонент",
                "transport_failed": "Не удалось запустить транспорт",
                "native_unavailable": "Недоступна нативная библиотека",
                "tun_failed": "Не удалось создать системный туннель",
                "permission_required": "Требуется разрешение на VPN",
                "no_profile": "Не выбран профиль",
                "unsupported_platform": "Протокол не поддерживается на этой системе",
                "split_apps_invalid": "Неверный список приложений раздельного туннеля",
                "process_exited": "Компонент неожиданно завершился",
                "all_attempts_failed": "Все попытки подключения завершились неудачно",
                "unknown_state": "Причина пока не определена",
            }
            title += ' · ' + context["protocol"] + ' · ' + reasons.get(context["reason"], context["reason"])
            title += ' · этап: ' + context["stage"]
            if "elapsed_ms" in context:
                title += f' · {context["elapsed_ms"] / 1000:g} с'
        cards.append("<details><summary>" + html.escape(title) + "</summary><pre>" + html.escape(json.dumps(row, ensure_ascii=False, indent=2)) + "</pre></details>")
    return HTMLResponse('<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>AKULA — отчёты приложения</title><style>body{font:16px system-ui;max-width:1100px;margin:32px auto;padding:16px;background:#10202c;color:#edf5ff}a{color:#80d8ff}details{padding:16px;margin:8px 0;background:#213442;border-radius:12px}pre{overflow:auto;font-size:13px}summary{cursor:pointer}</style><h1>Отчёты AKULA VPN</h1><p>Последние 200 ошибок и вылетов. Обновите страницу для новых отчётов. Доступ только администраторам.</p><p><a href="/dashboard">Кабинет</a> · <a href="/api/admin/app-reports/json">Выгрузить JSON</a></p>' + ("".join(cards) or "<p>Отчётов пока нет.</p>") + '</html>', headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"})
