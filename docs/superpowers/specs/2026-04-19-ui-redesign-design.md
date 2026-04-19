# UI Redesign: Tailwind CSS + Sidebar Layout

**Date**: 2026-04-19
**Scope**: Rewrite `src/main/webapp/index.html` with modern UI inspired by demo.html

## Goal

Redesign the S3 Storage Service web UI using the same frontend stack and visual style as demo.html: Tailwind CSS (CDN), Inter font, inline SVG icons, slate/indigo color scheme, rounded corners, and modern interaction patterns.

## Tech Stack

- **Tailwind CSS** via `https://cdn.tailwindcss.com` (same as demo.html)
- **Inter font** via Google Fonts CDN
- **Inline SVG icons** (Heroicons-style, replacing emoji icons)
- **No build tools** — single HTML file, all CSS/JS inline

## Layout

### Desktop (>= 768px)

```
┌──────────────────────────────────────────┐
│  Header: S3 Storage Service              │
├────────────┬─────────────────────────────┤
│ Sidebar    │  Main Content Area          │
│            │                             │
│ [Bucket 1] │  Bucket Name + Action Bar   │
│ [Bucket 2] │  ─────────────────────────  │
│ [Bucket 3] │  File Table                 │
│            │  - Name | Size | Date | Act │
│            │  - hello.txt | 12B | ...    │
│            │                             │
│ [+Create]  │  ─────────────────────────  │
│            │  Console (dark terminal)    │
├────────────┴─────────────────────────────┤
│  (Auth warning banner, conditional)      │
└──────────────────────────────────────────┘
```

### Mobile (< 768px)

- Sidebar collapses into a dropdown bucket selector in the header
- File table stacks into card layout
- Console panel stays at bottom

## Components

### 1. Header Bar
- Top bar with title "S3 Storage Service" and subtitle
- Auth warning banner (conditional, when mode is `aws-v4`)
- Mobile: bucket selector dropdown replaces sidebar

### 2. Sidebar (left panel)
- Bucket list with active state highlight (indigo background)
- Click bucket to select and load files
- "Create Bucket" button at bottom
- Delete bucket via right-click or action in main area header

### 3. Main Content Area
- **Sub-header**: bucket name, object count, action buttons (Upload, Auth Settings, Delete Bucket)
- **File Table**: columns — Name, Size, Last Modified, Actions (Download/Delete)
- **Empty state**: centered message "No files in this bucket"

### 4. Console Panel (bottom)
- Dark background (`#0f172a` slate-900)
- Green monospace text for log output
- Clear button in header
- Shows API request/response logs: `GET /my-bucket → 200 OK (3 objects)`
- Custom scrollbar styling (matching demo.html)

### 5. Modal Dialogs
All modals use backdrop blur overlay, rounded-3xl container, smooth animation.

**Upload Modal**:
- File input (browser native)
- Custom file name input (optional)
- Upload button

**Create Bucket Modal**:
- Bucket name input
- Create button

**Auth Settings Modal**:
- Mode selector dropdown (aws-v4 / both / none)
- Apply button
- Current status display

### 6. Toast Notifications
- Fixed bottom-center position
- Auto-dismiss after 3 seconds
- Animated fade-in/fade-out (CSS keyframe, matching demo.html)
- Green checkmark icon + message text
- Types: success (emerald), error (red), info (blue)

## Color Scheme

Matching demo.html's slate/indigo palette:
- Background: `slate-50` (#f8fafc)
- Cards/panels: white with `slate-200` borders
- Primary buttons: `indigo-600` (#4f46e5)
- Danger buttons: `red-500` (#ef4444)
- Success: `emerald-600` (#059669)
- Console: `slate-900` (#0f172a) background, `green-400` (#4ade80) text

## JavaScript Architecture

### State Management
- Simple global state object: `{ buckets: [], selectedBucket: null, files: [], authMode: null }`
- All API calls go through a centralized `api()` helper that logs to console

### API Helper
```javascript
async function api(method, path, options = {}) {
    appendConsole(`> ${method} ${path}`);
    const response = await fetch(`${API_BASE}${path}`, { method, ...options });
    const status = response.ok ? '✓' : '✗';
    appendConsole(`${status} ${method} ${path} → ${response.status}`);
    return response;
}
```

### Existing Functions (preserved logic, updated UI targets)
- `createBucket()` — opens modal, calls `PUT /{bucket}`
- `listBuckets()` — calls `GET /`, updates sidebar
- `uploadFile()` — opens modal, calls `PUT /{bucket}/{key}`
- `listFiles()` — calls `GET /{bucket}`, updates file table
- `downloadFile(bucket, key)` — `window.open()`
- `deleteFile(bucket, key)` — confirm dialog, calls `DELETE /{bucket}/{key}`
- `loadAuthStatus()` — calls `GET /admin/auth-status`
- `applyAuthMode()` — calls `POST /admin/auth-status`

### New Functions
- `selectBucket(name)` — update sidebar active state, load files
- `openModal(id)` / `closeModal(id)` — generic modal toggle
- `showToast(message, type)` — toast notification
- `appendConsole(text)` — add log line to console panel
- `clearConsole()` — clear console output
- `renderSidebar()` — render bucket list in sidebar
- `renderFileTable(files)` — render file table rows

## Responsive Breakpoints

- `>= 768px`: sidebar visible, table layout
- `< 768px`: no sidebar, dropdown selector, card-based file list

## File Changes

Only one file modified: `src/main/webapp/index.html`

Complete rewrite of the file. All existing HTML, CSS, and JavaScript replaced. API endpoints and behavior remain identical.

## Out of Scope

- No backend changes
- No new API endpoints
- No drag-and-drop file upload
- No file preview
- No dark mode toggle (console is always dark)
- No authentication changes (V4 auth logic unchanged)
