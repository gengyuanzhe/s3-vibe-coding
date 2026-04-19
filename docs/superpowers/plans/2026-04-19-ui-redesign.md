# S3 Storage Service UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the S3 Storage Service web interface with Tailwind CSS, sidebar layout, modals, toast notifications, and terminal log.

**Architecture:** Single-page HTML with embedded CSS (Tailwind CDN) and JavaScript. Sidebar lists buckets, main area shows selected bucket's objects. Modal dialogs for delete confirmation and upload progress. Toast notifications for operation feedback.

**Tech Stack:** Tailwind CSS 3.x CDN, vanilla JavaScript (Fetch API), Heroicons SVG icons

---

## File Structure

| File | Responsibility |
|------|----------------|
| `src/main/webapp/index.html` | Complete rewrite - HTML structure, Tailwind CSS, JavaScript logic |
| `CLAUDE.md` | Update Web UI section documentation |

---

### Task 1: HTML Skeleton with Tailwind CDN

**Files:**
- Modify: `src/main/webapp/index.html` (complete rewrite)

- [ ] **Step 1: Write HTML skeleton with Tailwind CDN**

Replace entire file with:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>S3 Storage Service</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
    <style>
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');
        body { font-family: 'Inter', sans-serif; }
        .font-mono { font-family: 'Consolas', 'Monaco', monospace; }
        
        /* Toast Animation */
        @keyframes fade-in-out {
            0% { opacity: 0; transform: translateY(10px); }
            10% { opacity: 1; transform: translateY(0); }
            90% { opacity: 1; transform: translateY(0); }
            100% { opacity: 0; transform: translateY(-10px); }
        }
        .toast { animation: fade-in-out 2s ease-in-out forwards; }
        
        /* Console Scrollbar */
        .console-scroll::-webkit-scrollbar { width: 8px; height: 8px; }
        .console-scroll::-webkit-scrollbar-track { background: #1e293b; border-radius: 4px; }
        .console-scroll::-webkit-scrollbar-thumb { background: #475569; border-radius: 4px; }
        .console-scroll::-webkit-scrollbar-thumb:hover { background: #64748b; }
    </style>
</head>
<body class="bg-slate-50 min-h-screen">
    <!-- Auth Warning Banner -->
    <div id="authWarning" class="hidden bg-yellow-50 border-b border-yellow-200 px-4 py-3 text-yellow-800 text-sm">
        <div class="max-w-7xl mx-auto flex items-center justify-between">
            <span><strong>Warning:</strong> Current auth mode is <code class="bg-yellow-100 px-1 rounded">aws-v4</code>. Web UI operations are unavailable. Switch to <code>both</code> or <code>none</code> in Auth Settings.</span>
        </div>
    </div>

    <!-- Main Layout: Sidebar + Main Area -->
    <div class="flex min-h-[calc(100vh-48px)]">
        <!-- Sidebar -->
        <aside id="sidebar" class="w-60 bg-white border-r border-slate-200 flex flex-col">
            <div class="p-4 border-b border-slate-200">
                <div class="flex items-center justify-between mb-4">
                    <h1 class="text-lg font-bold text-slate-800">Buckets</h1>
                    <button onclick="openNewBucketModal()" class="bg-blue-500 hover:bg-blue-600 text-white px-3 py-1.5 rounded-lg text-sm font-semibold flex items-center gap-1 transition-colors">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/></svg>
                        New
                    </button>
                </div>
                <button onclick="listBuckets()" class="w-full text-slate-500 hover:text-slate-700 text-sm flex items-center gap-2 py-1">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg>
                    Refresh
                </button>
            </div>
            
            <!-- Bucket List -->
            <div id="bucketList" class="flex-1 overflow-y-auto p-2 space-y-1">
                <!-- Buckets will be rendered here -->
            </div>
            
            <!-- Auth Settings -->
            <div class="p-4 border-t border-slate-200">
                <label class="block text-xs font-semibold text-slate-500 mb-2">Auth Mode</label>
                <select id="authMode" onchange="applyAuthMode()" class="w-full bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-blue-500">
                    <option value="aws-v4">aws-v4 (Strict)</option>
                    <option value="both">both (Permissive)</option>
                    <option value="none">none (Disabled)</option>
                </select>
            </div>
        </aside>

        <!-- Main Area -->
        <main id="mainArea" class="flex-1 p-6">
            <!-- Empty State -->
            <div id="emptyState" class="flex flex-col items-center justify-center h-full text-slate-400">
                <svg class="w-16 h-16 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M5 8h14M5 8a2 2 0 110-4h14a2 2 0 110 4M5 8v10a2 2 0 002 2h10a2 2 0 002-2V8m-9 4h4"></path></svg>
                <p class="text-lg font-medium">No bucket selected</p>
                <p class="text-sm">Select a bucket from the sidebar or create a new one</p>
            </div>

            <!-- Bucket Content (hidden by default) -->
            <div id="bucketContent" class="hidden space-y-4">
                <!-- Header -->
                <div class="flex items-center justify-between">
                    <div>
                        <h2 id="selectedBucketName" class="text-xl font-bold text-slate-800"></h2>
                        <p id="bucketStats" class="text-sm text-slate-500"></p>
                    </div>
                    <div class="flex gap-3">
                        <button onclick="openUploadModal()" class="bg-emerald-500 hover:bg-emerald-600 text-white px-4 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 transition-colors">
                            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"/></svg>
                            Upload
                        </button>
                        <button onclick="openDeleteBucketModal()" class="bg-red-100 hover:bg-red-200 text-red-700 px-4 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 border border-red-200 transition-colors">
                            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>
                            Delete Bucket
                        </button>
                    </div>
                </div>

                <!-- File List Table -->
                <div class="bg-white rounded-xl border border-slate-200 overflow-hidden">
                    <div class="bg-slate-50 px-4 py-3 border-b border-slate-200 grid grid-cols-12 text-xs font-semibold text-slate-500">
                        <div class="col-span-6">Name</div>
                        <div class="col-span-3">Size</div>
                        <div class="col-span-3 text-center">Actions</div>
                    </div>
                    <div id="fileList" class="divide-y divide-slate-100">
                        <!-- Files will be rendered here -->
                    </div>
                </div>

                <!-- Execution Log -->
                <div class="bg-slate-900 rounded-xl overflow-hidden">
                    <div class="bg-slate-800 px-4 py-2 flex items-center justify-between">
                        <span class="text-slate-400 text-xs font-mono">Execution Log</span>
                        <button onclick="clearConsole()" class="text-slate-500 hover:text-slate-300 text-xs transition-colors">Clear</button>
                    </div>
                    <div id="consoleOutput" class="p-4 h-32 overflow-y-auto font-mono text-sm text-emerald-400 leading-relaxed console-scroll">
                        > Ready for operations...
                    </div>
                </div>
            </div>
        </main>
    </div>

    <!-- Modals Container -->
    <div id="modalsContainer">
        <!-- New Bucket Modal -->
        <div id="newBucketModal" class="hidden fixed inset-0 z-50 flex items-center justify-center p-4">
            <div class="absolute inset-0 bg-black/50 backdrop-blur-sm" onclick="closeNewBucketModal()"></div>
            <div class="relative bg-white rounded-2xl shadow-xl max-w-md w-full p-6 animate-in fade-in zoom-in duration-200">
                <h3 class="text-lg font-bold text-slate-800 mb-4">Create New Bucket</h3>
                <input id="newBucketName" type="text" placeholder="Enter bucket name" class="w-full border border-slate-200 rounded-lg px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50">
                <div class="flex gap-3 mt-4">
                    <button onclick="createBucket()" class="flex-1 bg-blue-500 hover:bg-blue-600 text-white font-semibold py-2.5 rounded-lg transition-colors">Create</button>
                    <button onclick="closeNewBucketModal()" class="px-6 py-2.5 bg-slate-100 hover:bg-slate-200 text-slate-600 font-semibold rounded-lg transition-colors">Cancel</button>
                </div>
            </div>
        </div>

        <!-- Upload Modal -->
        <div id="uploadModal" class="hidden fixed inset-0 z-50 flex items-center justify-center p-4">
            <div class="absolute inset-0 bg-black/50 backdrop-blur-sm" onclick="closeUploadModal()"></div>
            <div class="relative bg-white rounded-2xl shadow-xl max-w-md w-full p-6">
                <h3 class="text-lg font-bold text-slate-800 mb-4">Upload File</h3>
                <div class="space-y-4">
                    <div>
                        <label class="block text-xs font-semibold text-slate-500 mb-2">File Name (optional)</label>
                        <input id="uploadFileName" type="text" placeholder="Custom file name or leave empty" class="w-full border border-slate-200 rounded-lg px-4 py-2 text-sm outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50">
                    </div>
                    <div>
                        <label class="block text-xs font-semibold text-slate-500 mb-2">Select File</label>
                        <input id="uploadFileInput" type="file" class="w-full border border-slate-200 rounded-lg bg-slate-50 file:mr-4 file:py-2 file:px-4 file:rounded-md file:border-0 file:text-sm file:font-semibold file:bg-blue-50 file:text-blue-700 hover:file:bg-blue-100 cursor-pointer">
                    </div>
                </div>
                <div class="flex gap-3 mt-4">
                    <button onclick="uploadFile()" class="flex-1 bg-emerald-500 hover:bg-emerald-600 text-white font-semibold py-2.5 rounded-lg transition-colors">Upload</button>
                    <button onclick="closeUploadModal()" class="px-6 py-2.5 bg-slate-100 hover:bg-slate-200 text-slate-600 font-semibold rounded-lg transition-colors">Cancel</button>
                </div>
            </div>
        </div>

        <!-- Delete Confirm Modal -->
        <div id="deleteModal" class="hidden fixed inset-0 z-50 flex items-center justify-center p-4">
            <div class="absolute inset-0 bg-black/50 backdrop-blur-sm" onclick="closeDeleteModal()"></div>
            <div class="relative bg-white rounded-2xl shadow-xl max-w-md w-full p-6">
                <div class="flex items-center gap-3 mb-4">
                    <div class="bg-red-100 p-2 rounded-full">
                        <svg class="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.542 0 2.18-1.193 1.542-2.18L12.546 4.18c-.68-1.18-2.18-1.18-2.86 0L3.498 16.82c-.68.987.02 2.18 1.542 2.18z"></path></svg>
                    </div>
                    <h3 id="deleteModalTitle" class="text-lg font-bold text-slate-800">Delete Item</h3>
                </div>
                <p id="deleteModalMessage" class="text-slate-600 text-sm mb-4"></p>
                <div class="flex gap-3">
                    <button id="deleteConfirmBtn" class="flex-1 bg-red-500 hover:bg-red-600 text-white font-semibold py-2.5 rounded-lg transition-colors">Delete</button>
                    <button onclick="closeDeleteModal()" class="px-6 py-2.5 bg-slate-100 hover:bg-slate-200 text-slate-600 font-semibold rounded-lg transition-colors">Cancel</button>
                </div>
            </div>
        </div>
    </div>

    <!-- Toast Container -->
    <div id="toastContainer" class="fixed bottom-4 right-4 z-50 space-y-2"></div>

    <script>
        const API_BASE = window.location.origin;
        let selectedBucket = null;
        let deleteTarget = null; // {type: 'file'|'bucket', bucket, key}

        // Initialize
        document.addEventListener('DOMContentLoaded', () => {
            listBuckets();
            loadAuthStatus();
        });

        // ... rest of JavaScript will be added in Task 2
    </script>
</body>
</html>
```

- [ ] **Step 2: Commit skeleton**

```bash
git add src/main/webapp/index.html
git commit -m "feat(ui): add HTML skeleton with Tailwind CDN and sidebar layout"
```

---

### Task 2: JavaScript - Core Functions (Bucket Management)

**Files:**
- Modify: `src/main/webapp/index.html` (JavaScript section)

- [ ] **Step 1: Add bucket management JavaScript functions**

Add to `<script>` section after `// Initialize` comment:

```javascript
        // ==================== Bucket Management ====================

        async function listBuckets() {
            try {
                const response = await fetch(`${API_BASE}/`);
                if (!response.ok) {
                    if (response.status === 403) {
                        showToast('Access denied. Check auth mode.', 'error');
                        return;
                    }
                    throw new Error('Failed to fetch buckets');
                }
                const xmlText = await response.text();
                const parser = new DOMParser();
                const xmlDoc = parser.parseFromString(xmlText, 'text/xml');
                const bucketNames = xmlDoc.getElementsByTagName('Name');

                const bucketListEl = document.getElementById('bucketList');
                bucketListEl.innerHTML = '';

                if (bucketNames.length === 0) {
                    bucketListEl.innerHTML = '<div class="text-center text-slate-400 text-sm py-4">No buckets</div>';
                    return;
                }

                for (let i = 0; i < bucketNames.length; i++) {
                    const name = bucketNames[i].textContent;
                    const isSelected = selectedBucket === name;
                    const bucketItem = document.createElement('div');
                    bucketItem.className = isSelected 
                        ? 'bg-blue-50 border-l-3 border-blue-500 text-blue-700 px-3 py-2 rounded-r-lg cursor-pointer font-medium'
                        : 'bg-slate-50 hover:bg-slate-100 text-slate-600 px-3 py-2 rounded-lg cursor-pointer';
                    bucketItem.innerHTML = `
                        <svg class="w-4 h-4 inline mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 8h14M5 8a2 2 0 110-4h14a2 2 0 110 4M5 8v10a2 2 0 002 2h10a2 2 0 002-2V8m-9 4h4"></path></svg>
                        ${name}
                    `;
                    bucketItem.onclick = () => selectBucket(name);
                    bucketListEl.appendChild(bucketItem);
                }

                logToConsole(`Loaded ${bucketNames.length} bucket(s)`);
            } catch (error) {
                showToast('Failed to load buckets', 'error');
                logToConsole(`Error: ${error.message}`, 'error');
            }
        }

        function selectBucket(name) {
            selectedBucket = name;
            document.getElementById('emptyState').classList.add('hidden');
            document.getElementById('bucketContent').classList.remove('hidden');
            document.getElementById('selectedBucketName').textContent = name;
            listBuckets(); // Refresh sidebar highlighting
            listFiles();
        }

        function openNewBucketModal() {
            document.getElementById('newBucketModal').classList.remove('hidden');
            document.getElementById('newBucketName').value = '';
            document.getElementById('newBucketName').focus();
        }

        function closeNewBucketModal() {
            document.getElementById('newBucketModal').classList.add('hidden');
        }

        async function createBucket() {
            const bucketName = document.getElementById('newBucketName').value.trim();
            if (!bucketName) {
                showToast('Please enter a bucket name', 'error');
                return;
            }

            try {
                logToConsole(`Creating bucket: ${bucketName}...`);
                const response = await fetch(`${API_BASE}/${bucketName}`, { method: 'PUT' });
                if (response.ok) {
                    showToast('Bucket created successfully', 'success');
                    logToConsole(`Bucket created: ${bucketName}`);
                    closeNewBucketModal();
                    listBuckets();
                    selectBucket(bucketName);
                } else if (response.status === 403) {
                    showToast('Access denied. Check auth mode.', 'error');
                    logToConsole('Error: Access denied', 'error');
                } else if (response.status === 409) {
                    showToast('Bucket already exists', 'error');
                    logToConsole('Error: Bucket already exists', 'error');
                } else {
                    showToast('Failed to create bucket', 'error');
                    logToConsole('Error: Failed to create bucket', 'error');
                }
            } catch (error) {
                showToast('Failed to create bucket', 'error');
                logToConsole(`Error: ${error.message}`, 'error');
            }
        }

        function openDeleteBucketModal() {
            if (!selectedBucket) return;
            deleteTarget = { type: 'bucket', bucket: selectedBucket };
            document.getElementById('deleteModalTitle').textContent = 'Delete Bucket';
            document.getElementById('deleteModalMessage').textContent = `Are you sure you want to delete "${selectedBucket}"? The bucket must be empty.`;
            document.getElementById('deleteConfirmBtn').onclick = deleteBucket;
            document.getElementById('deleteModal').classList.remove('hidden');
        }

        async function deleteBucket() {
            if (!deleteTarget || deleteTarget.type !== 'bucket') return;
            const bucket = deleteTarget.bucket;

            try {
                logToConsole(`Deleting bucket: ${bucket}...`);
                const response = await fetch(`${API_BASE}/${bucket}`, { method: 'DELETE' });
                if (response.ok || response.status === 204) {
                    showToast('Bucket deleted successfully', 'success');
                    logToConsole(`Bucket deleted: ${bucket}`);
                    closeDeleteModal();
                    selectedBucket = null;
                    document.getElementById('emptyState').classList.remove('hidden');
                    document.getElementById('bucketContent').classList.add('hidden');
                    listBuckets();
                } else if (response.status === 403) {
                    showToast('Access denied', 'error');
                    logToConsole('Error: Access denied', 'error');
                } else if (response.status === 409) {
                    showToast('Bucket is not empty', 'error');
                    logToConsole('Error: Bucket not empty', 'error');
                } else {
                    showToast('Failed to delete bucket', 'error');
                    logToConsole('Error: Failed to delete bucket', 'error');
                }
            } catch (error) {
                showToast('Failed to delete bucket', 'error');
                logToConsole(`Error: ${error.message}`, 'error');
            }
        }

        function closeDeleteModal() {
            document.getElementById('deleteModal').classList.add('hidden');
            deleteTarget = null;
        }
```

- [ ] **Step 2: Commit bucket management**

```bash
git add src/main/webapp/index.html
git commit -m "feat(ui): add bucket management JavaScript with sidebar selection"
```

---

### Task 3: JavaScript - File Operations (Upload, Download, Delete)

**Files:**
- Modify: `src/main/webapp/index.html` (JavaScript section)

- [ ] **Step 1: Add file operations JavaScript functions**

Add after bucket management section:

```javascript
        // ==================== File Operations ====================

        async function listFiles() {
            if (!selectedBucket) return;

            try {
                logToConsole(`Loading files from ${selectedBucket}...`);
                const response = await fetch(`${API_BASE}/${selectedBucket}`);
                if (!response.ok) {
                    if (response.status === 403) {
                        showToast('Access denied', 'error');
                        return;
                    }
                    throw new Error('Failed to fetch files');
                }
                const xmlText = await response.text();
                const parser = new DOMParser();
                const xmlDoc = parser.parseFromString(xmlText, 'text/xml');
                const contents = xmlDoc.getElementsByTagName('Contents');

                const fileListEl = document.getElementById('fileList');
                fileListEl.innerHTML = '';

                let totalSize = 0;
                const files = [];

                for (let i = 0; i < contents.length; i++) {
                    const key = contents[i].getElementsByTagName('Key')[0]?.textContent || '';
                    const size = parseInt(contents[i].getElementsByTagName('Size')[0]?.textContent || '0');
                    totalSize += size;
                    files.push({ key, size });
                }

                // Update stats
                document.getElementById('bucketStats').textContent = `${files.length} objects · ${formatSize(totalSize)} total`;

                if (files.length === 0) {
                    fileListEl.innerHTML = '<div class="text-center text-slate-400 text-sm py-8">No files in this bucket</div>';
                    logToConsole(`Bucket ${selectedBucket} is empty`);
                    return;
                }

                files.forEach(file => {
                    const fileRow = document.createElement('div');
                    fileRow.className = 'px-4 py-3 grid grid-cols-12 items-center hover:bg-slate-50 transition-colors';
                    fileRow.innerHTML = `
                        <div class="col-span-6 flex items-center gap-3">
                            <svg class="w-5 h-5 text-slate-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path></svg>
                            <span class="text-slate-700 text-sm truncate">${file.key}</span>
                        </div>
                        <div class="col-span-3 text-slate-500 text-sm">${formatSize(file.size)}</div>
                        <div class="col-span-3 flex justify-center gap-2">
                            <button onclick="downloadFile('${selectedBucket}', '${file.key}')" class="bg-blue-100 hover:bg-blue-200 text-blue-800 px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1 transition-colors">
                                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path></svg>
                                Download
                            </button>
                            <button onclick="openDeleteFileModal('${selectedBucket}', '${file.key}')" class="bg-red-100 hover:bg-red-200 text-red-700 px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1 transition-colors">
                                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path></svg>
                                Delete
                            </button>
                        </div>
                    `;
                    fileListEl.appendChild(fileRow);
                });

                logToConsole(`Loaded ${files.length} file(s) from ${selectedBucket}`);
            } catch (error) {
                showToast('Failed to load files', 'error');
                logToConsole(`Error: ${error.message}`, 'error');
            }
        }

        function openUploadModal() {
            if (!selectedBucket) {
                showToast('Please select a bucket first', 'error');
                return;
            }
            document.getElementById('uploadModal').classList.remove('hidden');
            document.getElementById('uploadFileName').value = '';
            document.getElementById('uploadFileInput').value = '';
        }

        function closeUploadModal() {
            document.getElementById('uploadModal').classList.add('hidden');
        }

        async function uploadFile() {
            const fileInput = document.getElementById('uploadFileInput');
            const fileNameInput = document.getElementById('uploadFileName').value.trim();

            if (!fileInput.files[0]) {
                showToast('Please select a file', 'error');
                return;
            }

            const file = fileInput.files[0];
            const key = fileNameInput || file.name;

            try {
                logToConsole(`Uploading ${key} (${formatSize(file.size)})...`);
                closeUploadModal();
                
                const response = await fetch(`${API_BASE}/${selectedBucket}/${key}`, {
                    method: 'PUT',
                    body: file
                });

                if (response.ok) {
                    const etag = response.headers.get('ETag');
                    showToast('File uploaded successfully', 'success');
                    logToConsole(`Uploaded: ${key} (ETag: ${etag || 'N/A'})`);
                    listFiles();
                } else if (response.status === 403) {
                    showToast('Access denied', 'error');
                    logToConsole('Error: Access denied', 'error');
                } else if (response.status === 413) {
                    showToast('File too large', 'error');
                    logToConsole('Error: File too large', 'error');
                } else {
                    showToast('Failed to upload file', 'error');
                    logToConsole('Error: Upload failed', 'error');
                }
            } catch (error) {
                showToast('Failed to upload file', 'error');
                logToConsole(`Error: ${error.message}`, 'error');
            }
        }

        function downloadFile(bucket, key) {
            logToConsole(`Downloading: ${key}...`);
            window.open(`${API_BASE}/${bucket}/${key}`, '_blank');
            showToast('Download started', 'info');
        }

        function openDeleteFileModal(bucket, key) {
            deleteTarget = { type: 'file', bucket, key };
            document.getElementById('deleteModalTitle').textContent = 'Delete File';
            document.getElementById('deleteModalMessage').textContent = `Are you sure you want to delete "${key}"?`;
            document.getElementById('deleteConfirmBtn').onclick = deleteFile;
            document.getElementById('deleteModal').classList.remove('hidden');
        }

        async function deleteFile() {
            if (!deleteTarget || deleteTarget.type !== 'file') return;
            const { bucket, key } = deleteTarget;

            try {
                logToConsole(`Deleting: ${key}...`);
                const response = await fetch(`${API_BASE}/${bucket}/${key}`, { method: 'DELETE' });
                if (response.ok || response.status === 204) {
                    showToast('File deleted successfully', 'success');
                    logToConsole(`Deleted: ${key}`);
                    closeDeleteModal();
                    listFiles();
                } else if (response.status === 403) {
                    showToast('Access denied', 'error');
                    logToConsole('Error: Access denied', 'error');
                } else {
                    showToast('Failed to delete file', 'error');
                    logToConsole('Error: Delete failed', 'error');
                }
            } catch (error) {
                showToast('Failed to delete file', 'error');
                logToConsole(`Error: ${error.message}`, 'error');
            }
        }

        function formatSize(bytes) {
            if (bytes === 0) return '0 B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        }
```

- [ ] **Step 2: Commit file operations**

```bash
git add src/main/webapp/index.html
git commit -m "feat(ui): add file upload, download, delete operations"
```

---

### Task 4: JavaScript - Auth Settings, Toast, Console Log

**Files:**
- Modify: `src/main/webapp/index.html` (JavaScript section)

- [ ] **Step 1: Add auth, toast, and console functions**

Add after file operations section:

```javascript
        // ==================== Auth Settings ====================

        async function loadAuthStatus() {
            try {
                const response = await fetch(`${API_BASE}/admin/auth-status`);
                if (response.ok) {
                    const data = await response.json();
                    document.getElementById('authMode').value = data.mode;
                    updateAuthWarning(data.mode);
                }
            } catch (error) {
                logToConsole('Warning: Could not load auth status', 'warn');
            }
        }

        async function applyAuthMode() {
            const mode = document.getElementById('authMode').value;
            try {
                logToConsole(`Changing auth mode to: ${mode}...`);
                const response = await fetch(`${API_BASE}/admin/auth-status`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ mode })
                });
                if (response.ok) {
                    const data = await response.json();
                    document.getElementById('authMode').value = data.mode;
                    updateAuthWarning(data.mode);
                    showToast(`Auth mode changed to "${mode}"`, 'success');
                    logToConsole(`Auth mode: ${mode}`);
                } else {
                    const data = await response.json();
                    showToast(data.error || 'Failed to update auth mode', 'error');
                    logToConsole('Error: Auth update failed', 'error');
                }
            } catch (error) {
                showToast('Failed to update auth mode', 'error');
                logToConsole(`Error: ${error.message}`, 'error');
            }
        }

        function updateAuthWarning(mode) {
            const warning = document.getElementById('authWarning');
            warning.classList.toggle('hidden', mode !== 'aws-v4');
        }

        // ==================== Toast Notifications ====================

        function showToast(message, type = 'info') {
            const container = document.getElementById('toastContainer');
            const toast = document.createElement('div');
            
            const colors = {
                success: 'bg-emerald-500',
                error: 'bg-red-500',
                info: 'bg-blue-500',
                warn: 'bg-yellow-500'
            };

            const icons = {
                success: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/>',
                error: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>',
                info: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/>',
                warn: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.542 0 2.18-1.193 1.542-2.18L12.546 4.18c-.68-1.18-2.18-1.18-2.86 0L3.498 16.82c-.68.987.02 2.18 1.542 2.18z"/>'
            };

            toast.className = `toast ${colors[type]} text-white px-4 py-3 rounded-lg shadow-lg flex items-center gap-3 text-sm font-medium`;
            toast.innerHTML = `
                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">${icons[type]}</svg>
                <span>${message}</span>
            `;
            
            container.appendChild(toast);
            
            setTimeout(() => {
                toast.remove();
            }, 2000);
        }

        // ==================== Console Log ====================

        function logToConsole(message, type = 'info') {
            const consoleEl = document.getElementById('consoleOutput');
            const timestamp = new Date().toLocaleTimeString();
            
            const colors = {
                info: 'text-emerald-400',
                error: 'text-red-400',
                warn: 'text-yellow-400',
                success: 'text-blue-400'
            };

            const line = document.createElement('div');
            line.className = colors[type];
            line.textContent = `[${timestamp}] ${message}`;
            consoleEl.appendChild(line);
            
            // Auto-scroll to bottom
            consoleEl.scrollTop = consoleEl.scrollHeight;
        }

        function clearConsole() {
            const consoleEl = document.getElementById('consoleOutput');
            consoleEl.innerHTML = '> Console cleared';
            consoleEl.className = 'p-4 h-32 overflow-y-auto font-mono text-sm text-emerald-400 leading-relaxed console-scroll';
        }
```

- [ ] **Step 2: Commit auth and utilities**

```bash
git add src/main/webapp/index.html
git commit -m "feat(ui): add auth settings, toast notifications, and console log"
```

---

### Task 5: Manual Testing and Verification

**Files:**
- None (verification only)

- [ ] **Step 1: Start the server**

```bash
mvn exec:java
```

- [ ] **Step 2: Open browser and verify**

Open `http://localhost:5080` and check:
1. Sidebar shows bucket list
2. Click bucket → main area shows files
3. New bucket modal opens and creates bucket
4. Upload modal works
5. Download opens new tab
6. Delete modal confirms before deletion
7. Toast notifications appear and fade
8. Console log shows operations
9. Auth mode dropdown changes mode
10. Auth warning appears when mode is `aws-v4`

- [ ] **Step 3: Fix any issues found**

If any issues are found, fix them inline and commit.

---

### Task 6: Update CLAUDE.md Documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update Web UI section**

Find the "Web 界面" section and replace with:

```markdown
## Web 界面

**访问地址**: http://localhost:5080/

**技术**: Tailwind CSS + vanilla JavaScript

**布局**: Sidebar + Main Area
- Sidebar: 桶列表 + 新建按钮 + Auth 设置
- Main Area: 桶详情 + 文件表格 + 执行日志

**功能**:
- Sidebar 桶列表导航
- 模态框：新建桶、上传文件、删除确认
- Toast 通知：操作反馈
- 终端日志：操作历史记录
- SVG 图标替代 emoji

**注意**: Web 界面使用 `API_BASE = window.location.origin`（S3 标准路径，无 `/api` 前缀）
```

- [ ] **Step 2: Commit documentation**

```bash
git add CLAUDE.md
git commit -m "docs: update Web UI section for Tailwind redesign"
```

---

### Task 7: Push and Finalize

**Files:**
- None (git operations)

- [ ] **Step 1: Push all commits**

```bash
git push
```

- [ ] **Step 2: Verify remote**

Check that all commits are pushed to `main-glm5.0` branch.

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | HTML skeleton with Tailwind | `index.html` |
| 2 | Bucket management JS | `index.html` |
| 3 | File operations JS | `index.html` |
| 4 | Auth, toast, console JS | `index.html` |
| 5 | Manual testing | - |
| 6 | Update documentation | `CLAUDE.md` |
| 7 | Push to remote | - |

**Total estimated time**: 30-45 minutes