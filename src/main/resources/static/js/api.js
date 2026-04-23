(function (global) {
    'use strict';

    function joinUrl(base, path) {
        if (!path.startsWith('/')) {
            path = '/' + path;
        }
        return base.replace(/\/$/, '') + path;
    }

    async function fetchJson(path, options) {
        const url = joinUrl(global.IACT_API_BASE || '', path);
        const opts = Object.assign(
            {
                headers: {},
            },
            options || {}
        );
        const hasBody = opts.body != null && !(opts.body instanceof FormData);
        if (hasBody && !opts.headers['Content-Type']) {
            opts.headers['Content-Type'] = 'application/json';
        }
        const res = await fetch(url, opts);
        const text = await res.text();
        let data = null;
        if (text) {
            try {
                data = JSON.parse(text);
            } catch {
                data = text;
            }
        }
        if (!res.ok) {
            const err = new Error(typeof data === 'string' ? data : res.statusText || 'Request failed');
            err.status = res.status;
            err.body = data;
            throw err;
        }
        return data;
    }

    global.IactApi = {
        fetchJson,
        experimentalList(page, size) {
            const p = page < 1 ? 1 : page;
            const s = size < 1 ? 20 : Math.min(size, 100);
            return fetchJson('/experimental/list?page=' + p + '&size=' + s);
        },
        listRequirements(opts) {
            opts = opts || {};
            const q = opts.onlyEnabled ? '?onlyEnabled=true' : '';
            return fetchJson('/requirements' + q);
        },
        createRequirement(body) {
            return fetchJson('/requirements', {
                method: 'POST',
                body: JSON.stringify(body),
            });
        },
        getRequirement(id) {
            return fetchJson('/requirements/' + encodeURIComponent(id));
        },
        updateRequirement(id, body) {
            return fetchJson('/requirements/' + encodeURIComponent(id), {
                method: 'PUT',
                body: JSON.stringify(body),
            });
        },
        /** POST /requirements/{id}/delete — 逻辑删除（enable=0） */
        softDeleteRequirement(id) {
            return fetchJson('/requirements/' + encodeURIComponent(id) + '/delete', {
                method: 'POST',
            });
        },
        /** POST /code/analyze/session/create — 返回 { success, sessionId } */
        createAnalyzeSession() {
            return fetchJson('/code/analyze/session/create', {
                method: 'POST',
            });
        },
        /** POST /code/analyze/sync — 非流式，返回 { success, eventType, data?, message? } */
        analyzeSync(body) {
            return fetchJson('/code/analyze/sync', {
                method: 'POST',
                body: JSON.stringify(body),
            });
        },
    };
})(typeof window !== 'undefined' ? window : globalThis);
