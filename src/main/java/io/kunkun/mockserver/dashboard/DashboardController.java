package io.kunkun.mockserver.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Dashboard ingestion + read API and a self-contained live UI.
 *
 * <p>Ingestion endpoints (POST) match the contract the TPS Generator client's {@code DashboardClient}
 * expects and are guarded by {@code X-API-Key} when {@code dashboard.api-key} is configured. The read
 * endpoints and the {@code /dashboard} page are open so the UI can poll them.
 */
@RestController
public class DashboardController {

    private final DashboardService service;

    @Value("${dashboard.api-key:}")
    private String apiKey;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    // ===== Ingestion (from the load-test client) =====

    @PostMapping("/api/tests/register")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-API-Key", required = false) String key) {
        if (!authorized(key)) {
            return unauthorized();
        }
        service.register(payload);
        return ResponseEntity.ok(Map.of("status", "registered", "testId", String.valueOf(payload.get("testId"))));
    }

    @PostMapping("/api/metrics/update")
    public ResponseEntity<Map<String, Object>> update(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-API-Key", required = false) String key) {
        if (!authorized(key)) {
            return unauthorized();
        }
        boolean ok = service.update(payload);
        return ok ? ResponseEntity.ok(Map.of("status", "updated"))
                : ResponseEntity.status(404).body(Map.of("status", "error", "message", "unknown testId"));
    }

    @PostMapping("/api/tests/finish")
    public ResponseEntity<Map<String, Object>> finish(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-API-Key", required = false) String key) {
        if (!authorized(key)) {
            return unauthorized();
        }
        boolean ok = service.finish(payload);
        return ok ? ResponseEntity.ok(Map.of("status", "finished"))
                : ResponseEntity.status(404).body(Map.of("status", "error", "message", "unknown testId"));
    }

    @PostMapping("/api/tests/result")
    public ResponseEntity<Map<String, Object>> result(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-API-Key", required = false) String key) {
        if (!authorized(key)) {
            return unauthorized();
        }
        boolean ok = service.result(payload);
        return ok ? ResponseEntity.ok(Map.of("status", "result-recorded"))
                : ResponseEntity.badRequest().body(Map.of("status", "error", "message", "testId is required"));
    }

    // ===== Read API (for the UI) =====

    @GetMapping("/api/tests")
    public ResponseEntity<Map<String, Object>> listTests() {
        List<TestRun> runs = service.list();
        return ResponseEntity.ok(Map.of("count", runs.size(), "tests", runs));
    }

    @GetMapping("/api/tests/{testId}")
    public ResponseEntity<Object> getTest(@PathVariable String testId) {
        TestRun run = service.get(testId);
        return run != null ? ResponseEntity.ok(run)
                : ResponseEntity.status(404).body(Map.of("status", "error", "message", "unknown testId"));
    }

    @DeleteMapping("/api/tests")
    public ResponseEntity<Map<String, Object>> clear(
            @RequestHeader(value = "X-API-Key", required = false) String key) {
        if (!authorized(key)) {
            return unauthorized();
        }
        service.clear();
        return ResponseEntity.ok(Map.of("status", "cleared"));
    }

    // ===== UI =====

    @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public String dashboard() {
        return DASHBOARD_HTML;
    }

    // ===== helpers =====

    private boolean authorized(String provided) {
        // No key configured => open (development). Otherwise the header must match.
        return apiKey == null || apiKey.isBlank() || apiKey.equals(provided);
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Invalid or missing X-API-Key"));
    }

    private static final String DASHBOARD_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1"/>
            <title>TPS Generator — Live Dashboard</title>
            <style>
              :root { color-scheme: dark; }
              * { box-sizing: border-box; }
              body { margin:0; font:14px/1.45 system-ui,Segoe UI,Roboto,sans-serif; background:#0d1117; color:#e6edf3; }
              header { padding:16px 24px; border-bottom:1px solid #21262d; display:flex; align-items:center; gap:12px; }
              header h1 { font-size:18px; margin:0; font-weight:650; }
              header .meta { color:#8b949e; font-size:12px; margin-left:auto; }
              main { padding:20px 24px; }
              table { width:100%; border-collapse:collapse; }
              th,td { text-align:left; padding:9px 12px; border-bottom:1px solid #21262d; white-space:nowrap; }
              th { color:#8b949e; font-weight:600; font-size:12px; text-transform:uppercase; letter-spacing:.04em; }
              tr.run { cursor:pointer; }
              tr.run:hover { background:#161b22; }
              .badge { padding:2px 8px; border-radius:999px; font-size:12px; font-weight:600; }
              .running { background:#1f6feb33; color:#79c0ff; }
              .finished { background:#23863633; color:#7ee787; }
              .num { font-variant-numeric:tabular-nums; }
              .detail { background:#0b0f14; }
              .detail pre { margin:0; padding:12px 16px; color:#c9d1d9; overflow:auto; max-height:340px; }
              .empty { color:#8b949e; padding:40px; text-align:center; }
              .dot { width:8px;height:8px;border-radius:50%;background:#3fb950;display:inline-block;margin-right:6px;animation:pulse 1.6s infinite; }
              @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.3} }
            </style>
            </head>
            <body>
            <header>
              <h1>TPS Generator — Live Dashboard</h1>
              <span class="meta"><span class="dot"></span>auto-refreshing every 2s · <span id="count">0</span> runs</span>
            </header>
            <main>
              <table>
                <thead><tr>
                  <th>Test</th><th>Status</th><th>Target</th><th class="num">TPS</th>
                  <th class="num">Success</th><th class="num">p95 (ms)</th><th class="num">Total</th><th>Updated</th>
                </tr></thead>
                <tbody id="rows"></tbody>
              </table>
              <div id="empty" class="empty">No runs yet. Point a TPS Generator at this server with dashboard enabled.</div>
            </main>
            <script>
              const fmtPct = v => (v==null? '–' : (v*100).toFixed(2)+'%');
              const fmtNum = v => (v==null? '–' : Number(v).toLocaleString());
              const ago = t => { if(!t) return '–'; const s=Math.round((Date.now()-t)/1000); return s<60? s+'s ago' : Math.round(s/60)+'m ago'; };
              const esc = s => (s==null?'':String(s)).replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
              let openId = null;

              async function refresh() {
                let data;
                try { data = await (await fetch('/api/tests')).json(); } catch(e) { return; }
                const runs = data.tests || [];
                document.getElementById('count').textContent = runs.length;
                document.getElementById('empty').style.display = runs.length? 'none':'block';
                const rows = document.getElementById('rows');
                rows.innerHTML = runs.map(r => {
                  const s = r.summary || {};
                  const tps = s.currentTps, sr = s.successRate, p95 = s.p95ResponseTime, total = s.totalRequests;
                  const statusCls = r.status==='finished'? 'finished':'running';
                  let html = `<tr class="run" data-id="${esc(r.testId)}">
                    <td>${esc(r.testName)||esc(r.testId)}</td>
                    <td><span class="badge ${statusCls}">${esc(r.status)}</span></td>
                    <td>${esc(r.targetServiceUrl)||'–'}</td>
                    <td class="num">${tps==null?'–':Number(tps).toFixed(0)}</td>
                    <td class="num">${fmtPct(sr)}</td>
                    <td class="num">${p95==null?'–':fmtNum(p95)}</td>
                    <td class="num">${fmtNum(total)}</td>
                    <td>${ago(r.lastUpdated)}</td></tr>`;
                  if (r.testId === openId) {
                    const detail = { summary:r.summary, statusCodes:r.statusCodes, resources:r.resources, result:r.result };
                    html += `<tr class="detail"><td colspan="8"><pre>${esc(JSON.stringify(detail,null,2))}</pre></td></tr>`;
                  }
                  return html;
                }).join('');
                rows.querySelectorAll('tr.run').forEach(tr => tr.onclick = () => {
                  const id = tr.getAttribute('data-id');
                  openId = (openId===id)? null : id;
                  refresh();
                });
              }
              refresh();
              setInterval(refresh, 2000);
            </script>
            </body>
            </html>
            """;
}
