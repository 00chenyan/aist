(function () {
    'use strict';

    let lastTaskRecords = [];
    let iactPendingDeleteId = null;
    /** 与 requirement.html / index.html 一致，后端 CodeAnalyzeRequest.projectId */
    const ANALYZE_PROJECT_ID = 1;
    let iactAnalyzeSessionId = null;
    let iactAnalyzeLoading = false;
    let iactBatchSessionActive = false;

    const $ = function (id) {
        return document.getElementById(id);
    };

    function escapeHtml(text) {
        if (text == null) {
            return '';
        }
        const div = document.createElement('div');
        div.textContent = String(text);
        return div.innerHTML;
    }

    function formatRequirementStatus(status) {
        if (status === 0 || status === '0') {
            return '待生成';
        }
        if (status === 1 || status === '1') {
            return '生成中';
        }
        if (status === 2 || status === '2') {
            return '已完成';
        }
        if (status == null || status === '') {
            return '待生成';
        }
        return String(status);
    }

    function truncate(s, maxLen) {
        if (s == null) {
            return '';
        }
        const t = String(s);
        if (t.length <= maxLen) {
            return t;
        }
        return t.slice(0, maxLen - 1) + '…';
    }

    const IACT_COLS = ['id', 'name', 'description', 'status', 'actions'];

    function deriveTaskRowView(row) {
        const name = row.subject || '(no name)';
        const desc = truncate(row.description, 120);
        const statusLabel = formatRequirementStatus(row.status);
        const canPreview = row.status === 2 || row.status === '2';
        const rawDescription = row.description != null ? String(row.description) : '';
        return { name, desc, statusLabel, canPreview, rawDescription };
    }

    function buildActionsCellInner(view) {
        const previewBtn = view.canPreview
            ? '<button type="button" class="btn btn-secondary btn-sm iact-btn-preview">Preview</button> '
            : '';
        return (
            '<button type="button" class="btn btn-secondary btn-sm iact-btn-config">Config</button> ' +
            previewBtn +
            '<button type="button" class="btn btn-primary btn-sm iact-btn-chat">Analyze</button> ' +
            '<button type="button" class="btn btn-secondary btn-sm iact-btn-delete">Delete</button>'
        );
    }

    function buildStatusCellHtml(row) {
        const v = deriveTaskRowView(row);
        const icon =
            '<svg class="iact-refresh-icon" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M3 21v-5h5"/></svg>';
        const st = row.status != null && row.status !== '' ? String(row.status) : '0';
        return (
            '<span class="iact-status-cell">' +
            '<span class="iact-status-label" data-status="' +
            escapeHtml(st) +
            '">' +
            escapeHtml(v.statusLabel) +
            '</span>' +
            '<button type="button" class="iact-btn-row-refresh" title="刷新此行数据" aria-label="刷新此行">' +
            icon +
            '</button></span>'
        );
    }

    function buildTaskRowHtml(row) {
        const v = deriveTaskRowView(row);
        return (
            '<tr data-id="' +
            escapeHtml(row.id) +
            '">' +
            '<td class="col-id">' +
            escapeHtml(row.id) +
            '</td>' +
            '<td class="col-name">' +
            escapeHtml(v.name) +
            '</td>' +
            '<td class="col-description" title="' +
            escapeHtml(v.rawDescription) +
            '">' +
            escapeHtml(v.desc) +
            '</td>' +
            '<td class="col-status">' +
            buildStatusCellHtml(row) +
            '</td>' +
            '<td class="col-actions">' +
            buildActionsCellInner(v) +
            '</td>' +
            '</tr>'
        );
    }

    function openConfigModal(record) {
        const modal = $('configModal');
        if (!modal) {
            return;
        }
        const form = $('configForm');
        if (form) {
            form.reset();
        }
        if (record) {
            $('configId').value = record.id != null ? String(record.id) : '';
            $('taskSubject').value = record.subject || '';
            $('taskDescription').value = record.description || '';
            $('gitCommitId').value = record.gitCommitId || '';
            $('projectName').value = record.projectName || '';
        } else {
            $('configId').value = '';
            $('taskSubject').value = '';
            $('taskDescription').value = '';
            $('gitCommitId').value = '';
            $('projectName').value = '';
        }
        modal.style.display = 'flex';
    }

    function closeConfigModal() {
        const modal = $('configModal');
        if (modal) {
            modal.style.display = 'none';
        }
    }

    function openDeleteConfirmModal(id) {
        iactPendingDeleteId = id != null ? String(id) : null;
        const modal = $('deleteConfirmModal');
        if (modal) {
            modal.style.display = 'flex';
        }
    }

    function closeDeleteConfirmModal() {
        iactPendingDeleteId = null;
        const modal = $('deleteConfirmModal');
        if (modal) {
            modal.style.display = 'none';
        }
    }

    /**
     * 需求描述：{subject}:{description}
     * git提交记录：{git_commit_id}
     * 请结合需求和git提交记录分析上述需求的影响范围
     */
    function buildAnalyzePrompt(record) {
        const subject = record.subject != null ? String(record.subject) : '';
        const description = record.description != null ? String(record.description) : '';
        const gitCommitId = record.gitCommitId != null ? String(record.gitCommitId) : '';
        return (
            '需求描述：' +
            subject +
            ':' +
            description +
            '\n' +
            'git提交记录：' +
            gitCommitId +
            '\n' +
            '请结合需求和git提交记录分析上述需求的影响范围'
        );
    }

    function setRowAnalyzeButtonState(requirementId, busy) {
        const tbody = $('taskTableBody');
        if (!tbody) {
            return;
        }
        const tr = tbody.querySelector('tr[data-id="' + String(requirementId) + '"]');
        if (!tr) {
            return;
        }
        const btn = tr.querySelector('.iact-btn-chat');
        if (btn) {
            btn.disabled = !!busy;
            btn.textContent = busy ? 'Analyzing…' : 'Analyze';
        }
    }

    async function ensureAnalyzeSession() {
        if (iactAnalyzeSessionId) {
            return iactAnalyzeSessionId;
        }
        const data = await window.IactApi.createAnalyzeSession();
        if (data && data.success && data.sessionId) {
            iactAnalyzeSessionId = data.sessionId;
            return iactAnalyzeSessionId;
        }
        throw new Error((data && data.message) || '创建分析会话失败');
    }

    async function runIactAnalyzeSync(requestBody, state) {
        const data = await window.IactApi.analyzeSync(requestBody);
        if (!data || !data.success) {
            throw new Error((data && data.message) || '分析失败');
        }
        if (data.eventType === 'question') {
            alert(
                '当前分析需要模型进一步澄清，本页暂不支持多轮对话，请调整任务描述后重试。'
            );
            state.doneData = '';
            return;
        }
        state.doneData = data.data != null ? String(data.data) : '';
    }

    function setBatchToolbarBusy(busy) {
        const batchBtn = $('batchAnalyzeBtn');
        const refreshBtn = $('toolbarRefreshBtn');
        const addBtn = $('addTaskBtn');
        if (batchBtn) {
            batchBtn.disabled = !!busy;
            batchBtn.textContent = busy ? 'Batch analyzing…' : 'Batch Analysis';
        }
        if (refreshBtn) {
            refreshBtn.disabled = !!busy;
        }
        if (addBtn) {
            addBtn.disabled = !!busy;
        }
    }

    function setAllRowAnalyzeButtonsDisabled(disable) {
        const tbody = $('taskTableBody');
        if (!tbody) {
            return;
        }
        tbody.querySelectorAll('.iact-btn-chat').forEach(function (b) {
            b.disabled = !!disable;
        });
    }

    /**
     * 与单条 Analyze 相同逻辑；可选无标题/描述时静默跳过（供批量用）。
     */
    async function runAnalyzeCore(record, options) {
        options = options || {};
        if (!record || record.id == null) {
            return;
        }
        const idStr = String(record.id);
        const subj = record.subject != null ? String(record.subject).trim() : '';
        const desc = record.description != null ? String(record.description).trim() : '';
        if (!subj && !desc) {
            if (!options.skipEmptyAlert) {
                alert('请先填写任务名称或描述后再分析。');
            }
            return;
        }
        const prompt = buildAnalyzePrompt(record);
        iactAnalyzeLoading = true;
        setRowAnalyzeButtonState(idStr, true);
        iactAnalyzeSessionId = null;
        const state = { doneData: null };
        try {
            await ensureAnalyzeSession();
            await window.IactApi.updateRequirement(
                idStr,
                Object.assign({}, record, { status: 1 })
            );
            await loadTasks();
            const body = {
                projectId: ANALYZE_PROJECT_ID,
                question: prompt.trim(),
                sessionId: iactAnalyzeSessionId,
            };
            await runIactAnalyzeSync(body, state);
            const merged = Object.assign({}, record, {
                analysisResults: state.doneData != null ? state.doneData : '',
                status: 2,
            });
            await window.IactApi.updateRequirement(idStr, merged);
            await loadTasks();
        } catch (e) {
            console.error(e);
            alert('分析或保存失败: ' + (e.message || 'Network error'));
        } finally {
            iactAnalyzeLoading = false;
            if (iactBatchSessionActive) {
                setAllRowAnalyzeButtonsDisabled(true);
            } else {
                setRowAnalyzeButtonState(idStr, false);
            }
        }
    }

    /**
     * 创建会话并拉取同步分析，将结果写入 requirement.analysisResults
     */
    async function runAnalyzeAndPersistToRequirement(record) {
        if (iactBatchSessionActive) {
            return;
        }
        if (iactAnalyzeLoading) {
            return;
        }
        await runAnalyzeCore(record, { skipEmptyAlert: false });
    }

    /**
     * enable=1、status ≠ 2 的任务，按顺序执行分析（与单条 Analyze 相同 API）。
     */
    async function runBatchAnalyzeSequentially() {
        if (iactBatchSessionActive) {
            return;
        }
        if (iactAnalyzeLoading) {
            return;
        }
        let raw;
        try {
            raw = await window.IactApi.listRequirements({ onlyEnabled: true });
        } catch (e) {
            console.error(e);
            alert('无法加载任务列表: ' + (e.message || 'Network error'));
            return;
        }
        const records = Array.isArray(raw) ? raw : [];
        const initialIds = records
            .filter(function (r) {
                const s = r && r.status;
                if (s === 2 || s === '2') {
                    return false;
                }
                if (r && r.enable != null && Number(r.enable) === 0) {
                    return false;
                }
                return true;
            })
            .map(function (r) {
                return r.id;
            });
        if (initialIds.length === 0) {
            alert('没有待分析任务（均已「已完成」或已停用）。');
            return;
        }
        if (!confirm('Run analysis on ' + initialIds.length + ' task(s) one by one?')) {
            return;
        }
        iactBatchSessionActive = true;
        setBatchToolbarBusy(true);
        setAllRowAnalyzeButtonsDisabled(true);
        try {
            for (let i = 0; i < initialIds.length; i++) {
                const idStr = String(initialIds[i]);
                let full;
                try {
                    full = await window.IactApi.getRequirement(idStr);
                } catch (e) {
                    console.error(e);
                    continue;
                }
                if (!full) {
                    continue;
                }
                if (full.enable != null && Number(full.enable) === 0) {
                    continue;
                }
                const st = full.status;
                if (st === 2 || st === '2') {
                    continue;
                }
                await runAnalyzeCore(full, { skipEmptyAlert: true });
            }
        } finally {
            iactBatchSessionActive = false;
            setBatchToolbarBusy(false);
            setAllRowAnalyzeButtonsDisabled(false);
            await loadTasks();
        }
    }

    /**
     * 源码行级高亮：ATX 标题中 # 与标题文分色；代码块内整行不解析为标题（避免 ``` 内误高亮）
     */
    function mdSourceToHighlightedHtml(s) {
        const lines = String(s).split('\n');
        let inFence = false;
        const reAtx = /^([ \t]{0,3})(#{1,6})([ \t]*)(.*)$/;
        return lines
            .map(function (line) {
                if (line.trimStart().startsWith('```')) {
                    inFence = !inFence;
                    return (
                        '<span class="iact-md-src-line iact-md-src-plainline">' + escapeHtml(line) + '</span>'
                    );
                }
                if (inFence) {
                    return (
                        '<span class="iact-md-src-line iact-md-src-plainline">' + escapeHtml(line) + '</span>'
                    );
                }
                const m = line.match(reAtx);
                if (m) {
                    return (
                        '<span class="iact-md-src-line">' +
                        escapeHtml(m[1]) +
                        '<span class="iact-md-hmark">' +
                        escapeHtml(m[2]) +
                        '</span>' +
                        (m[3] ? '<span class="iact-md-hgap">' + escapeHtml(m[3]) + '</span>' : '') +
                        '<span class="iact-md-htext">' +
                        escapeHtml(m[4]) +
                        '</span>' +
                        '</span>'
                    );
                }
                return (
                    '<span class="iact-md-src-line iact-md-src-plainline">' + escapeHtml(line) + '</span>'
                );
            })
            .join('\n');
    }

    /**
     * analysis_results：仅展示带语法高亮的 Markdown 源码（全屏层）
     */
    function openAnalysisPreviewModal(raw) {
        const text = raw != null && String(raw).trim() !== '' ? String(raw) : '（无分析结果）';
        const modal = $('previewModal');
        const content = $('previewContent');
        if (!content || !modal) {
            return;
        }
        content.innerHTML =
            '<div class="iact-md-read" role="document" aria-label="analysis_results Markdown">' +
            '<div class="iact-md-filename"><span class="iact-md-filename-text">analysis_results</span><span class="iact-md-filename-ext">.md</span></div>' +
            '<div class="iact-md-source-pane">' +
            '<pre class="iact-md-source-pre" spellcheck="false"><code class="iact-md-source-code"></code></pre></div>' +
            '</div>';
        const preCode = content.querySelector('.iact-md-source-code');
        if (preCode) {
            preCode.innerHTML = mdSourceToHighlightedHtml(text);
        }
        modal.style.display = 'flex';
    }

    function closePreviewModal() {
        const modal = $('previewModal');
        if (modal) {
            modal.style.display = 'none';
        }
    }

    async function loadTasks() {
        const tbody = $('taskTableBody');
        const empty = $('emptyState');
        if (!tbody) {
            return;
        }
        tbody.innerHTML =
            '<tr class="loading-row"><td colspan="5">Loading...</td></tr>';
        try {
            const raw = await window.IactApi.listRequirements({ onlyEnabled: true });
            const records = Array.isArray(raw) ? raw : [];
            lastTaskRecords = records;
            if (records.length === 0) {
                tbody.innerHTML = '';
                if (empty) {
                    empty.style.display = 'block';
                }
                return;
            }
            if (empty) {
                empty.style.display = 'none';
            }
            tbody.innerHTML = records.map(buildTaskRowHtml).join('');
        } catch (e) {
            console.error(e);
            tbody.innerHTML =
                '<tr><td colspan="5">Failed to load tasks. Check console.</td></tr>';
            if (empty) {
                empty.style.display = 'none';
            }
        }
    }

    /**
     * 拉取最新列表，仅更新指定表列的单元格；行集合变化时回退为整表重绘。
     */
    async function refreshColumn(col, triggerBtn) {
        if (IACT_COLS.indexOf(col) < 0) {
            return;
        }
        const tbody = $('taskTableBody');
        const empty = $('emptyState');
        if (!tbody) {
            return;
        }
        if (triggerBtn) {
            triggerBtn.classList.add('is-loading');
            triggerBtn.disabled = true;
        }
        try {
            const raw = await window.IactApi.listRequirements({ onlyEnabled: true });
            const records = Array.isArray(raw) ? raw : [];
            lastTaskRecords = records;
            if (records.length === 0) {
                tbody.innerHTML = '';
                if (empty) {
                    empty.style.display = 'block';
                }
                return;
            }
            if (empty) {
                empty.style.display = 'none';
            }
            const dataRows = Array.from(tbody.querySelectorAll('tr[data-id]'));
            if (dataRows.length !== records.length) {
                tbody.innerHTML = records.map(buildTaskRowHtml).join('');
                return;
            }
            const byId = new Map();
            records.forEach(function (r) {
                byId.set(String(r.id), r);
            });
            for (let i = 0; i < dataRows.length; i++) {
                const id = dataRows[i].getAttribute('data-id');
                if (id == null || !byId.has(id)) {
                    tbody.innerHTML = records.map(buildTaskRowHtml).join('');
                    return;
                }
            }
            for (let j = 0; j < dataRows.length; j++) {
                const tr = dataRows[j];
                const id = tr.getAttribute('data-id');
                const row = byId.get(id);
                const v = deriveTaskRowView(row);
                if (col === 'id') {
                    tr.setAttribute('data-id', String(row.id));
                    tr.querySelector('td.col-id').innerHTML = escapeHtml(row.id);
                } else if (col === 'name') {
                    tr.querySelector('td.col-name').innerHTML = escapeHtml(v.name);
                } else if (col === 'description') {
                    const td = tr.querySelector('td.col-description');
                    td.setAttribute('title', v.rawDescription);
                    td.innerHTML = escapeHtml(v.desc);
                } else if (col === 'status') {
                    tr.querySelector('td.col-status').innerHTML = buildStatusCellHtml(row);
                } else if (col === 'actions') {
                    tr.querySelector('td.col-actions').innerHTML = buildActionsCellInner(v);
                }
            }
        } catch (e) {
            console.error(e);
            alert('刷新失败。请检查网络或控制台。');
        } finally {
            if (triggerBtn) {
                triggerBtn.classList.remove('is-loading');
                triggerBtn.disabled = false;
            }
        }
    }

    function bindColumnRefresh() {
        const thead = document.querySelector('.task-table thead');
        if (!thead) {
            return;
        }
        thead.addEventListener('click', function (ev) {
            const btn = ev.target && ev.target.closest && ev.target.closest('.iact-col-refresh');
            if (!btn || !thead.contains(btn)) {
                return;
            }
            const c = btn.getAttribute('data-col');
            if (c) {
                void refreshColumn(c, btn);
            }
        });
    }

    function removeTaskRowFromUi(idStr) {
        const tbody = $('taskTableBody');
        const empty = $('emptyState');
        const tr = tbody && tbody.querySelector('tr[data-id="' + String(idStr) + '"]');
        if (tr && tr.parentNode) {
            tr.remove();
        }
        lastTaskRecords = lastTaskRecords.filter(function (r) {
            return String(r.id) !== String(idStr);
        });
        if (lastTaskRecords.length === 0 && empty) {
            empty.style.display = 'block';
        }
    }

    async function refreshTaskRow(idStr, triggerBtn) {
        if (triggerBtn) {
            triggerBtn.classList.add('is-loading');
            triggerBtn.disabled = true;
        }
        try {
            const row = await window.IactApi.getRequirement(idStr);
            if (row && row.enable != null && Number(row.enable) === 0) {
                removeTaskRowFromUi(idStr);
                return;
            }
            const idx = lastTaskRecords.findIndex(function (r) {
                return String(r.id) === String(idStr);
            });
            if (idx < 0) {
                await loadTasks();
                return;
            }
            lastTaskRecords[idx] = row;
            const tbody = $('taskTableBody');
            const tr = tbody && tbody.querySelector('tr[data-id="' + String(idStr) + '"]');
            if (!tr) {
                await loadTasks();
                return;
            }
            if (String(row.id) !== String(idStr)) {
                tr.setAttribute('data-id', String(row.id));
            }
            const v = deriveTaskRowView(row);
            tr.querySelector('td.col-id').innerHTML = escapeHtml(row.id);
            tr.querySelector('td.col-name').innerHTML = escapeHtml(v.name);
            const tdDesc = tr.querySelector('td.col-description');
            tdDesc.setAttribute('title', v.rawDescription);
            tdDesc.innerHTML = escapeHtml(v.desc);
            tr.querySelector('td.col-status').innerHTML = buildStatusCellHtml(row);
            tr.querySelector('td.col-actions').innerHTML = buildActionsCellInner(v);
        } catch (e) {
            if (e.status === 404) {
                removeTaskRowFromUi(idStr);
                return;
            }
            console.error(e);
            alert('刷新失败。请检查网络或控制台。');
        } finally {
            if (triggerBtn) {
                triggerBtn.classList.remove('is-loading');
                triggerBtn.disabled = false;
            }
        }
    }

    function bindTableActions() {
        const tbody = $('taskTableBody');
        if (!tbody) {
            return;
        }
        tbody.addEventListener('click', function (ev) {
            const btn = ev.target.closest('button');
            if (!btn || !tbody.contains(btn)) {
                return;
            }
            const tr = btn.closest('tr');
            const id = tr && tr.getAttribute('data-id');
            if (!id) {
                return;
            }
            const record = { id: id };
            if (btn.classList.contains('iact-btn-row-refresh')) {
                void refreshTaskRow(id, btn);
                return;
            }
            if (btn.classList.contains('iact-btn-config')) {
                const full = lastTaskRecords.find(function (r) {
                    return String(r.id) === String(id);
                });
                openConfigModal(full || { id: id });
                return;
            }
            if (btn.classList.contains('iact-btn-preview')) {
                const full = lastTaskRecords.find(function (r) {
                    return String(r.id) === String(id);
                });
                const raw = full && (full.analysisResults != null ? full.analysisResults : full.analysis_results);
                openAnalysisPreviewModal(raw);
                return;
            }
            if (btn.classList.contains('iact-btn-chat')) {
                if (iactBatchSessionActive) {
                    return;
                }
                if (iactAnalyzeLoading) {
                    return;
                }
                const full = lastTaskRecords.find(function (r) {
                    return String(r.id) === String(id);
                });
                if (!full) {
                    return;
                }
                void runAnalyzeAndPersistToRequirement(full);
                return;
            }
            if (btn.classList.contains('iact-btn-delete')) {
                openDeleteConfirmModal(id);
            }
        });
    }

    function wireModals() {
        $('addTaskBtn') &&
            $('addTaskBtn').addEventListener('click', function () {
                openConfigModal(null);
            });
        $('batchAnalyzeBtn') &&
            $('batchAnalyzeBtn').addEventListener('click', function () {
                void runBatchAnalyzeSequentially();
            });
        $('toolbarRefreshBtn') &&
            $('toolbarRefreshBtn').addEventListener('click', function () {
                const btn = $('toolbarRefreshBtn');
                if (btn) {
                    btn.classList.add('is-loading');
                    btn.disabled = true;
                }
                loadTasks()
                    .catch(function (e) {
                        console.error(e);
                    })
                    .finally(function () {
                        if (btn) {
                            btn.classList.remove('is-loading');
                            if (!iactBatchSessionActive) {
                                btn.disabled = false;
                            }
                        }
                    });
            });
        $('addTaskBtnEmpty') &&
            $('addTaskBtnEmpty').addEventListener('click', function () {
                openConfigModal(null);
            });
        $('closeConfigModal') &&
            $('closeConfigModal').addEventListener('click', closeConfigModal);
        $('cancelConfigBtn') &&
            $('cancelConfigBtn').addEventListener('click', closeConfigModal);
        $('saveConfigBtn') &&
            $('saveConfigBtn').addEventListener('click', async function () {
                const subject = $('taskSubject') && $('taskSubject').value.trim();
                if (!subject) {
                    alert('Please enter Task Name.');
                    return;
                }
                const description = $('taskDescription') ? $('taskDescription').value : '';
                const gitCommitId = $('gitCommitId') ? $('gitCommitId').value.trim() : '';
                const projectName = $('projectName') ? $('projectName').value.trim() : '';
                const idStr = $('configId') && $('configId').value;
                const patch = {
                    subject: subject,
                    description: description,
                    gitCommitId: gitCommitId,
                    projectName: projectName,
                };
                try {
                    if (idStr) {
                        const base = lastTaskRecords.find(function (r) {
                            return String(r.id) === String(idStr);
                        });
                        const merged = Object.assign({}, base || {}, patch);
                        await window.IactApi.updateRequirement(idStr, merged);
                    } else {
                        await window.IactApi.createRequirement(
                            Object.assign({}, patch, { status: 0 })
                        );
                    }
                    closeConfigModal();
                    await loadTasks();
                } catch (e) {
                    console.error(e);
                    alert('Save failed. Check console or network.');
                }
            });
        $('closePreviewModal') &&
            $('closePreviewModal').addEventListener('click', closePreviewModal);
        const previewM = $('previewModal');
        if (previewM) {
            previewM.addEventListener('click', function (ev) {
                if (ev.target === previewM) {
                    previewM.style.display = 'none';
                }
            });
        }
        const delM = $('deleteConfirmModal');
        if (delM) {
            delM.addEventListener('click', function (ev) {
                if (ev.target === delM) {
                    closeDeleteConfirmModal();
                }
            });
        }
        $('closeDeleteConfirmModal') &&
            $('closeDeleteConfirmModal').addEventListener('click', closeDeleteConfirmModal);
        $('cancelDeleteConfirmBtn') &&
            $('cancelDeleteConfirmBtn').addEventListener('click', closeDeleteConfirmModal);
        $('confirmDeleteBtn') &&
            $('confirmDeleteBtn').addEventListener('click', function () {
                const id = iactPendingDeleteId;
                if (!id) {
                    closeDeleteConfirmModal();
                    return;
                }
                window.IactApi.softDeleteRequirement(id)
                    .then(function () {
                        closeDeleteConfirmModal();
                        return loadTasks();
                    })
                    .catch(function (e) {
                        console.error(e);
                        alert('Delete failed. Check console or network.');
                    });
            });
    }

    document.addEventListener('DOMContentLoaded', function () {
        wireModals();
        bindColumnRefresh();
        bindTableActions();
        loadTasks();
    });
})();
