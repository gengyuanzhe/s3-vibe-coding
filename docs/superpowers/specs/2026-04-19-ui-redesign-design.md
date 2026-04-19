---
name: UI Redesign with Tailwind CSS
description: S3 Storage Service UI redesign using Tailwind CSS with sidebar layout
type: project
---

# S3 Storage Service UI Redesign

## Overview

Redesign the S3 Storage Service web interface using Tailwind CSS, adopting a sidebar + main area layout pattern. Replace emoji icons with SVG, implement Toast notifications, modal dialogs, and a terminal-style execution log.

## Goals

- Modern, professional UI using Tailwind CSS framework
- Sidebar navigation for bucket management
- Improved user experience with modals and toast notifications
- Consistent button styling without black borders

## Design Decisions

### 1. Layout Architecture

**Sidebar + Main Area pattern:**

```
┌─────────────────────────────────────────────────────────┐
│  Sidebar (240px)  │        Main Area (flex-1)           │
│                   │                                      │
│  ┌─────────────┐  │  ┌─────────────────────────────────┐│
│  │ Buckets     │  │  │ Header: Bucket name + stats     ││
│  │ + New btn   │  │  │ + Upload / Delete Bucket btns   ││
│  ├─────────────┤  │  ├─────────────────────────────────┤│
│  │ bucket-1 ✓  │  │  │ File List Table                 ││
│  │ bucket-2    │  │  │ (Name | Size | Actions)         ││
│  │ bucket-3    │  │  │ with Download/Delete buttons    ││
│  ├─────────────┤  │  ├─────────────────────────────────┤│
│  │ Auth Mode   │  │  │ Execution Log (terminal style)  ││
│  │ (select)    │  │  │ dark bg + green text            ││
│  └─────────────┘  │  └─────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
```

### 2. CSS Framework

**Tailwind CSS via CDN:**

```html
<script src="https://cdn.tailwindcss.com"></script>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap" rel="stylesheet">
```

- Delete all hand-written CSS (~260 lines)
- Use Tailwind utility classes throughout
- Background: `bg-slate-50` (#f1f5f9)
- Cards: `rounded-2xl` + `shadow-sm` + `border border-slate-200`

### 3. Component Specifications

#### Sidebar (240px fixed width)

- **Header**: "Buckets" label + "New" button (blue-500, white text)
- **Bucket List**: Scrollable, max-height 60vh
  - Unselected: `bg-slate-50` + `text-slate-600` + `rounded-lg`
  - Selected: `bg-blue-50` + `border-l-3px-blue-500` + `text-blue-700`
- **Auth Mode**: Select dropdown at bottom, fixed position

#### Main Area

- **Header**: Bucket name + statistics ("N objects · X MB total")
- **Action Buttons**:
  - Upload: `bg-emerald-500` + white text + SVG icon
  - Delete Bucket: `bg-red-100` + `text-red-700` + `border-red-200`
- **File List Table**:
  - Header row: `bg-slate-50` + `text-slate-500` + font-weight 600
  - File rows: Name with document SVG icon + Size + Actions
  - Download button: `bg-blue-100` + `text-blue-800` + SVG
  - Delete button: `bg-red-100` + `text-red-700` + SVG
- **Execution Log**: Dark terminal style
  - Background: `bg-slate-900` (#0f172a)
  - Header: `bg-slate-800` with "Execution Log" + "Clear" button
  - Content: `text-green-400` + monospace font

#### Modal Dialogs

**Delete Confirmation Modal:**

```html
<div class="modal-overlay bg-black/50 backdrop-blur-sm">
  <div class="bg-white rounded-3xl p-6 max-w-md">
    <h3>Delete "{filename}"?</h3>
    <p>This action cannot be undone.</p>
    <div class="flex gap-3">
      <button class="bg-red-500 text-white px-4 py-2 rounded-lg">Delete</button>
      <button class="bg-slate-100 text-slate-600 px-4 py-2 rounded-lg">Cancel</button>
    </div>
  </div>
</div>
```

**Upload Progress Modal:**

```html
<div class="modal-overlay">
  <div class="bg-white rounded-3xl p-6 max-w-md">
    <h3>Uploading File</h3>
    <div class="progress-bar bg-slate-200 rounded-full h-2">
      <div class="bg-emerald-500 h-2 rounded-full" style="width: 45%"></div>
    </div>
    <p class="text-slate-500">45% - 1.2 MB of 2.6 MB</p>
  </div>
</div>
```

**New Bucket Modal:**

```html
<div class="modal-overlay">
  <div class="bg-white rounded-3xl p-6 max-w-md">
    <h3>Create New Bucket</h3>
    <input class="border border-slate-200 rounded-lg p-2 w-full" placeholder="Bucket name">
    <div class="flex gap-3 mt-4">
      <button class="bg-blue-500 text-white px-4 py-2 rounded-lg">Create</button>
      <button class="bg-slate-100 text-slate-600 px-4 py-2 rounded-lg">Cancel</button>
    </div>
  </div>
</div>
```

#### Toast Notifications

**Success Toast:**

```css
@keyframes fade-in-out {
  0% { opacity: 0; transform: translateY(10px); }
  10% { opacity: 1; transform: translateY(0); }
  90% { opacity: 1; transform: translateY(0); }
  100% { opacity: 0; transform: translateY(-10px); }
}
.toast { animation: fade-in-out 2s ease-in-out forwards; }
```

```html
<div class="toast fixed bottom-4 right-4 bg-emerald-500 text-white px-4 py-3 rounded-lg shadow-lg">
  ✓ File uploaded successfully
</div>
```

**Error Toast:**

```html
<div class="toast fixed bottom-4 right-4 bg-red-500 text-white px-4 py-3 rounded-lg shadow-lg">
  ✗ Upload failed: File too large
</div>
```

### 4. Button Color Specification

| Button | Background | Text | Border | Icon Color |
|--------|------------|------|--------|------------|
| New | `#3b82f6` (blue-500) | white | none | white |
| Upload | `#10b981` (emerald-500) | white | none | white |
| Download | `#dbeafe` (blue-100) | `#1e40af` (blue-800) | none | `#1e40af` |
| Delete | `#fee2e2` (red-100) | `#b91c1c` (red-700) | none | `#b91c1c` |
| Delete Bucket | `#fee2e2` (red-100) | `#b91c1c` (red-700) | `#fecaca` (red-200) | `#b91c1c` |
| Modal Confirm | `#3b82f6` (blue-500) | white | none | - |
| Modal Cancel | `#f1f5f9` (slate-100) | `#475569` (slate-600) | none | - |

**Important**: All buttons use `border: none` to avoid black border artifacts.

### 5. SVG Icons (Heroicons)

Replace all emoji icons with inline SVG:

| Purpose | SVG Path |
|---------|----------|
| Bucket | `<path d="M5 8h14M5 8a2 2 0 110-4h14a2 2 0 110 4M5 8v10a2 2 0 002 2h10a2 2 0 002-2V8m-9 4h4"/>` |
| Upload | `<path d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"/>` |
| Download | `<path d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/>` |
| Delete | `<path d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>` |
| Plus (New) | `<path d="M12 4v16m8-8H4"/>` |
| File | `<path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/>` |

### 6. Interaction Flow

1. **Page Load**:
   - Fetch bucket list via `GET /`
   - Render buckets in sidebar
   - Show empty state in main area or first bucket's objects

2. **Click Bucket**:
   - Update selected state (highlight)
   - Fetch objects via `GET /{bucket}`
   - Render file list in main area

3. **New Bucket**:
   - Open modal with input field
   - On confirm: `PUT /{bucket}`
   - Refresh sidebar list

4. **Upload File**:
   - Open upload modal
   - Select file + optional custom name
   - On confirm: `PUT /{bucket}/{key}` with file body
   - Show progress modal during upload
   - Toast notification on completion

5. **Download File**:
   - Direct link: `window.open('/{bucket}/{key}')`
   - Log to execution terminal

6. **Delete File/Bucket**:
   - Open confirmation modal
   - On confirm: `DELETE /{bucket}/{key}` or `DELETE /{bucket}`
   - Toast notification + refresh list

7. **Auth Mode Change**:
   - Select from sidebar dropdown
   - `POST /admin/auth-status` with `{mode: "..."}`
   - Toast notification

### 7. Auth Warning Banner

Keep existing warning banner at top of page (not a modal):
- Show when `mode === 'aws-v4'`
- Style: `bg-yellow-50` + `border-yellow-200` + `text-yellow-800`

### 8. Responsive Design

- Sidebar collapses to hidden on mobile (< 768px)
- Toggle button to show/hide sidebar
- Main area full-width on mobile

## Implementation Notes

- All JavaScript logic remains unchanged
- Only HTML structure and CSS classes change
- API endpoints unchanged: `/`, `/{bucket}`, `/{bucket}/{key}`, `/admin/auth-status`
- No backend changes required

## Files to Modify

| File | Changes |
|------|---------|
| `src/main/webapp/index.html` | Complete rewrite with Tailwind classes, new layout, modals, toast |
| `CLAUDE.md` | Update Web UI section description |

## Success Criteria

- Professional appearance without black button borders
- Sidebar + main layout functional
- Modals work for delete confirm and upload progress
- Toast notifications appear and auto-dismiss
- Terminal log shows operation history
- All existing functionality preserved