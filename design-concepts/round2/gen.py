#!/usr/bin/env python3
"""Generate round-2 HTML mockups and screenshot with Playwright + system Chrome."""
from __future__ import annotations
import os, base64, io
from pathlib import Path
from playwright.sync_api import sync_playwright
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
ART = Path("/workspace/artifacts")
FONT_REG = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
FONT_BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"

# --- tiny thumbnails (realistic-ish) ----------------------------------------
def make_thumbs():
    thumbs = {}
    # photo-like warm gradient
    im = Image.new("RGB", (96, 96)); d = ImageDraw.Draw(im)
    for y in range(96):
        r = int(220 - y * 0.4); g = int(160 - y * 0.2); b = int(120 + y * 0.3)
        d.line([(0, y), (96, y)], fill=(r, g, b))
    d.ellipse([20, 18, 48, 40], fill=(255, 230, 160))
    buf = io.BytesIO(); im.save(buf, "PNG"); thumbs["jpg"] = base64.b64encode(buf.getvalue()).decode()
    # video-like dark with play
    im = Image.new("RGB", (96, 96), (30, 32, 40)); d = ImageDraw.Draw(im)
    d.polygon([(38, 28), (38, 68), (72, 48)], fill=(255, 255, 255))
    buf = io.BytesIO(); im.save(buf, "PNG"); thumbs["mp4"] = base64.b64encode(buf.getvalue()).decode()
    return thumbs

THUMBS = make_thumbs()

# Lucide-like inline SVGs (24)
ICONS = {
    "menu": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"><path d="M4 7h16M4 12h16M4 17h16"/></svg>',
    "search": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>',
    "more": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"><circle cx="12" cy="6" r="1.2" fill="currentColor"/><circle cx="12" cy="12" r="1.2" fill="currentColor"/><circle cx="12" cy="18" r="1.2" fill="currentColor"/></svg>',
    "folder": '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M3.5 7.5A2 2 0 015.5 5.5h3.2l1.6 1.8H18.5a2 2 0 012 2V17.5a2 2 0 01-2 2h-13a2 2 0 01-2-2v-10z" opacity=".9"/></svg>',
    "file": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M7 3.5h6.5L19 9v11.5a1.5 1.5 0 01-1.5 1.5h-10A1.5 1.5 0 016 20.5v-15A1.5 1.5 0 017.5 4"/><path d="M13.5 3.5V9H19"/></svg>',
    "apk": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="6" y="3" width="12" height="18" rx="2"/><path d="M10 17h4"/><circle cx="12" cy="9" r="2.5"/></svg>',
    "zip": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M7 3.5h6.5L19 9v11.5a1.5 1.5 0 01-1.5 1.5h-10A1.5 1.5 0 016 20.5v-15A1.5 1.5 0 017.5 4"/><path d="M13.5 3.5V9H19M10 7h2M10 10h2M10 13h2M11 13v4"/></svg>',
    "back": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"><path d="M15 6l-6 6 6 6"/></svg>',
    "phone": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="7" y="2.5" width="10" height="19" rx="2"/><path d="M10 18h4"/></svg>',
    "cast": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M3 15.5a7.5 7.5 0 017.5 7.5M3 11a12 12 0 0112 12M3 6.5V4.5A1.5 1.5 0 014.5 3h15A1.5 1.5 0 0121 4.5v15a1.5 1.5 0 01-1.5 1.5H14"/><circle cx="3.8" cy="20.2" r="1.1" fill="currentColor"/></svg>',
    "folder_side": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3.5 7.5A2 2 0 015.5 5.5h3.2l1.6 1.8H18.5a2 2 0 012 2V17.5a2 2 0 01-2 2h-13a2 2 0 01-2-2v-10z"/></svg>',
    "home": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 10.5L12 4l8 6.5V20a1 1 0 01-1 1h-5v-6H10v6H5a1 1 0 01-1-1v-9.5z"/></svg>',
    "transfer": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M7 7h11M14 3l4 4-4 4M17 17H6M10 13l-4 4 4 4"/></svg>',
    "apps": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="4" y="4" width="6" height="6" rx="1.2"/><rect x="14" y="4" width="6" height="6" rx="1.2"/><rect x="4" y="14" width="6" height="6" rx="1.2"/><rect x="14" y="14" width="6" height="6" rx="1.2"/></svg>',
    "trash": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M5 8h14M9 8V6a1 1 0 011-1h4a1 1 0 011 1v2M7 8l1 12h8l1-12"/></svg>',
    "term": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><rect x="3" y="4.5" width="18" height="15" rx="2"/><path d="M7 9l3 3-3 3M12 15h5"/></svg>',
    "settings": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="3"/><path d="M12 3.5v2M12 18.5v2M4.9 6.5l1.4 1.4M17.7 16.1l1.4 1.4M3.5 12h2M18.5 12h2M4.9 17.5l1.4-1.4M17.7 7.9l1.4-1.4"/></svg>',
    "wifi": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><path d="M4.5 9.5a11 11 0 0115 0M7.5 12.5a7 7 0 019 0M10.5 15.5a3 3 0 013 0"/><circle cx="12" cy="18.5" r="1" fill="currentColor"/></svg>',
    "bat": '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="8" width="16" height="9" rx="1.5"/><path d="M19 11h1.5v3H19"/><rect x="5" y="10" width="10" height="5" rx="0.5" fill="currentColor"/></svg>',
}

LEFT_ROWS = [
    ("folder", "DCIM", "128 项", None),
    ("folder", "Download", "42 项", None),
    ("folder", "Movies", "18 项", None),
    ("folder", "音乐", "56 项", None),
    ("zip", "旅行照片.zip", "700 MB · 昨天", "sel"),
    ("file", "会议纪要.txt", "12 KB · 今天", None),
    ("mp4", "演示视频 1080p.mp4", "3.0 GB · 10-12", None),
    ("jpg", "IMG_20261003.jpg", "4.8 MB · 10-03", None),
    ("apk", "app-release.apk", "15 MB · 今天", None),
]
RIGHT_ROWS = [
    ("folder", "纪录片", "目录", None),
    ("folder", "动画", "目录", None),
    ("folder", "蓝光原盘", "目录", None),
    ("mp4", "流浪地球2.mkv", "28 GB", None),
    ("mp4", "三体.S01E01.mkv", "4.2 GB", None),
]

THEMES = {
    "D": {  # HyperOS calm
        "light": dict(bg="#F5F6F8", surface="#FFFFFF", surface2="#EEF0F3", text="#1A1A1A", muted="#8A8F98",
                      accent="#3482FF", accentSoft="#E8F1FF", line="#E6E8EC", paneFocus="#FFFFFF", paneBlur="#FAFBFC",
                      status="#1A1A1A", termBg="#1C1C1E", termFg="#E5E5EA", selBg="#E8F1FF"),
        "dark": dict(bg="#000000", surface="#121212", surface2="#1C1C1E", text="#F2F2F7", muted="#8E8E93",
                     accent="#5B9CFF", accentSoft="#1A2A44", line="#2C2C2E", paneFocus="#121212", paneBlur="#0A0A0A",
                     status="#F2F2F7", termBg="#000000", termFg="#E5E5EA", selBg="#1A2A44"),
    },
    "E": {  # dark-first restrained
        "light": dict(bg="#EAEAED", surface="#F4F4F6", surface2="#DEDEE2", text="#1C1C1E", muted="#6C6C70",
                      accent="#2F6FED", accentSoft="#DCE6FA", line="#D0D0D5", paneFocus="#F7F7F9", paneBlur="#EFEFF2",
                      status="#1C1C1E", termBg="#1C1C1E", termFg="#D4D4D8", selBg="#DCE6FA"),
        "dark": dict(bg="#000000", surface="#0E0E10", surface2="#18181B", text="#FAFAFA", muted="#A1A1AA",
                     accent="#3B82F6", accentSoft="#172554", line="#27272A", paneFocus="#0E0E10", paneBlur="#050506",
                     status="#FAFAFA", termBg="#000000", termFg="#E4E4E7", selBg="#172554"),
    },
    "F": {  # Nothing mono + red
        "light": dict(bg="#F2F2F2", surface="#FFFFFF", surface2="#EBEBEB", text="#000000", muted="#666666",
                      accent="#D71921", accentSoft="#FCE8E9", line="#D9D9D9", paneFocus="#FFFFFF", paneBlur="#F7F7F7",
                      status="#000000", termBg="#111111", termFg="#F5F5F5", selBg="#FCE8E9"),
        "dark": dict(bg="#000000", surface="#0A0A0A", surface2="#141414", text="#FFFFFF", muted="#888888",
                     accent="#FF2D2D", accentSoft="#3A1010", line="#2A2A2A", paneFocus="#0A0A0A", paneBlur="#050505",
                     status="#FFFFFF", termBg="#000000", termFg="#F0F0F0", selBg="#3A1010"),
    },
}

def icon(name, cls="ico"):
    return f'<span class="{cls}">{ICONS[name]}</span>'

def status_bar(t, dark=False):
    return f'''
    <div class="status">
      <span class="time">9:41</span>
      <span class="right">{icon("wifi")}{icon("bat")}</span>
    </div>'''

def gesture():
    return '<div class="gesture"><i></i></div>'

def file_icon(kind, t):
    letter = t["letter"]
    # D: soft type colors; E: monochrome + accent folders only; F: pure mono outline
    colors = {
        "D": {"folder": "#3482FF", "zip": "#F5A524", "apk": "#34C759", "file": "#8A8F98", "mp4": "#AF52DE", "jpg": "#FF6B6B"},
        "E": {"folder": "#3B82F6", "zip": "#A1A1AA", "apk": "#A1A1AA", "file": "#A1A1AA", "mp4": "#A1A1AA", "jpg": "#A1A1AA"},
        "F": {"folder": "currentColor", "zip": "currentColor", "apk": "currentColor", "file": "currentColor", "mp4": "currentColor", "jpg": "currentColor"},
    }
    c = colors[letter][kind if kind in colors[letter] else "file"]
    # thumbnails only for D (HyperOS colorful); E/F stay icon-only for calm mono
    if kind in ("jpg", "mp4") and letter == "D":
        src = THUMBS["jpg" if kind == "jpg" else "mp4"]
        return f'<span class="thumb"><img src="data:image/png;base64,{src}" alt=""/></span>'
    if kind == "folder" and letter in ("E", "F"):
        name = "folder_side"  # outline
    elif kind == "mp4" and letter != "D":
        name = "file"
    else:
        name = {"folder": "folder", "zip": "zip", "apk": "apk", "file": "file", "mp4": "file", "jpg": "file"}[kind]
    return f'<span class="ico type" style="color:{c}">{ICONS[name]}</span>'

def rows_html(rows, t):
    out = []
    for kind, name, meta, flag in rows:
        sel = " sel" if flag == "sel" else ""
        out.append(f'''<div class="row{sel}">{file_icon(kind, t)}
          <div class="meta"><div class="name">{name}</div><div class="sub">{meta}</div></div></div>''')
    return "\n".join(out)

def title_block(letter, t, page):
    if letter == "F" and page == "main":
        return '''<div class="title-wrap"><div class="dot-title">FILES</div><div class="title-cn">文件</div></div>'''
    titles = {"main": "文件", "devices": "设备", "terminal": "终端"}
    return f'<div class="title-wrap"><div class="title">{titles[page]}</div></div>'

def main_html(letter, mode, tablet=False):
    t = dict(THEMES[letter][mode])
    t["letter"] = letter
    w, h = (1280, 800) if tablet else (412, 915)
    path = "本机  /  存储/模拟的/0"
    rail = ""
    side = ""
    if tablet:
        rail_items = [("home","文件",True),("transfer","传输",False),("apps","应用",False),("cast","设备",False),("term","终端",False),("trash","回收站",False)]
        rail = '<nav class="rail">' + "".join(
            f'<button class="rail-item{" on" if on else ""}">{icon(ic)}<span>{lab}</span></button>'
            for ic,lab,on in rail_items) + '</nav>'
        side = '''<aside class="side">
          <div class="side-h">位置</div>
          <div class="side-item on">本机存储</div>
          <div class="side-item">Download</div>
          <div class="side-item">DCIM</div>
          <div class="side-h">网络</div>
          <div class="side-item">NAS · 影视</div>
          <div class="side-item">ADB · Xiaomi</div>
          <div class="side-h">书签</div>
          <div class="side-item">工作文档</div>
        </aside>'''
    body = f'''
    <div class="phone {'tablet' if tablet else ''}" style="width:{w}px;height:{h}px">
      {status_bar(t)}
      <header class="top">
        {'<button class="icon-btn">'+icon("menu")+'</button>' if not tablet else ''}
        {title_block(letter, t, "main")}
        <button class="icon-btn">{icon("search")}</button>
        <button class="icon-btn">{icon("more")}</button>
      </header>
      <div class="path"><span class="crumb">本机</span><span class="sep">/</span><span class="rest">存储/模拟的/0</span></div>
      <div class="body">
        {rail}{side}
        <div class="panes">
          <section class="pane focus">
            <div class="pane-h"><span>本机</span><i class="dot"></i></div>
            <div class="list">{rows_html(LEFT_ROWS, t)}</div>
          </section>
          <section class="pane">
            <div class="pane-h"><span>NAS · 影视</span></div>
            <div class="list">{rows_html(RIGHT_ROWS, t)}</div>
          </section>
        </div>
      </div>
      <footer class="actions">
        <span class="selcount">已选 1 项 · 700 MB</span>
        <div class="btns"><button>复制</button><button>移动</button><button>删除</button><button>更多</button></div>
      </footer>
      {gesture()}
    </div>'''
    return page_wrap(letter, mode, body, w, h)

def devices_html(letter, mode):
    t = dict(THEMES[letter][mode]); t["letter"] = letter
    w, h = 412, 915
    cards = [
        ("Xiaomi 23049RAD8C", "192.168.1.23:41235", "在线"),
        ("Pixel 8 Pro", "192.168.1.40:5555", "离线"),
    ]
    items = ""
    for name, addr, st in cards:
        on = "on" if st == "在线" else ""
        items += f'''<div class="dev">
          <div class="dev-ico">{icon("phone")}</div>
          <div class="dev-meta"><div class="name">{name}</div><div class="sub">{addr}</div></div>
          <span class="badge {on}">{st}</span>
        </div>
        <div class="dev-actions"><button>投屏</button><button>文件</button><button>工具</button></div>'''
    body = f'''
    <div class="phone" style="width:{w}px;height:{h}px">
      {status_bar(t)}
      <header class="top">
        <button class="icon-btn">{icon("back")}</button>
        {title_block(letter, t, "devices")}
        <button class="text-btn">配对</button>
      </header>
      <div class="page">{items}
        <div class="hint">首次使用需在目标设备「无线调试」中重新配对。</div>
      </div>
      {gesture()}
    </div>'''
    return page_wrap(letter, mode, body, w, h)

def terminal_html(letter, mode):
    t = dict(THEMES[letter][mode]); t["letter"] = letter
    w, h = 412, 915
    body = f'''
    <div class="phone" style="width:{w}px;height:{h}px">
      {status_bar(t)}
      <header class="top">
        <button class="icon-btn">{icon("back")}</button>
        {title_block(letter, t, "terminal")}
        <span class="chip">目标 · Xiaomi</span>
      </header>
      <div class="term">
        <div class="tabs"><span class="tab on">shell</span><span class="tab">+</span></div>
        <pre class="scroll"><span class="p-user">shell</span><span class="p-at">@</span><span class="p-host">Xiaomi</span> <span class="p-dir">sdcard</span> <span class="p-pct">%</span> ls
DCIM   Download   Movies   Music   Pictures
<span class="p-user">shell</span><span class="p-at">@</span><span class="p-host">Xiaomi</span> <span class="p-dir">sdcard</span> <span class="p-pct">%</span> getprop ro.product.model
23049RAD8C
<span class="p-user">shell</span><span class="p-at">@</span><span class="p-host">Xiaomi</span> <span class="p-dir">sdcard</span> <span class="p-pct">%</span> <span class="caret">█</span></pre>
        <div class="keys"><span>Esc</span><span>Tab</span><span>Ctrl</span><span>Alt</span><span>↑</span><span>↓</span><span>←</span><span>→</span></div>
      </div>
      {gesture()}
    </div>'''
    return page_wrap(letter, mode, body, w, h)

def css(letter, mode):
    t = THEMES[letter][mode]
    radius = {"D": "18px", "E": "12px", "F": "8px"}[letter]
    title_size = {"D": "26px", "E": "22px", "F": "20px"}[letter]
    return f'''
@font-face {{ font-family: 'NotoSC'; src: url('file://{FONT_REG}'); font-weight: 400; }}
@font-face {{ font-family: 'NotoSC'; src: url('file://{FONT_BOLD}'); font-weight: 700; }}
* {{ box-sizing: border-box; margin: 0; padding: 0; }}
body {{ background: #222; display: flex; justify-content: center; align-items: flex-start; padding: 24px; font-family: 'NotoSC', 'Noto Sans CJK SC', sans-serif; -webkit-font-smoothing: antialiased; }}
.phone {{ background: {t['bg']}; color: {t['text']}; border-radius: 28px; overflow: hidden; position: relative; display: flex; flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,.35); }}
.phone.tablet {{ border-radius: 16px; }}
.status {{ height: 44px; padding: 12px 20px 0; display: flex; justify-content: space-between; align-items: center; font-size: 13px; font-weight: 600; color: {t['status']}; flex-shrink: 0; }}
.status .right {{ display: flex; gap: 6px; align-items: center; }}
.status .ico {{ width: 16px; height: 16px; display: inline-flex; }}
.status .ico svg {{ width: 16px; height: 16px; }}
.top {{ display: flex; align-items: center; gap: 4px; padding: 4px 10px 8px; flex-shrink: 0; }}
.title-wrap {{ flex: 1; min-width: 0; padding-left: 8px; }}
.title {{ font-size: {title_size}; font-weight: 700; letter-spacing: -0.02em; line-height: 1.2; }}
.dot-title {{ font-family: ui-monospace, 'SF Mono', Menlo, monospace; font-size: 11px; letter-spacing: 0.28em; color: {t['muted']}; margin-bottom: 2px; }}
.title-cn {{ font-size: 22px; font-weight: 700; }}
.icon-btn {{ width: 40px; height: 40px; border: 0; background: transparent; color: {t['text']}; border-radius: 20px; display: flex; align-items: center; justify-content: center; }}
.icon-btn .ico, .icon-btn .ico svg {{ width: 22px; height: 22px; }}
.text-btn {{ border: 0; background: transparent; color: {t['accent']}; font-weight: 600; font-size: 15px; padding: 8px 12px; font-family: inherit; }}
.chip {{ font-size: 12px; color: {t['accent']}; background: {t['accentSoft']}; padding: 4px 10px; border-radius: 999px; font-weight: 500; }}
.path {{ margin: 0 14px 10px; padding: 9px 12px; background: {t['surface2']}; border-radius: {radius}; font-size: 13px; color: {t['muted']}; display: flex; gap: 6px; align-items: center; overflow: hidden; white-space: nowrap; flex-shrink: 0; }}
.path .crumb {{ color: {t['accent']}; font-weight: 600; }}
.path .sep {{ opacity: .5; }}
.path .rest {{ overflow: hidden; text-overflow: ellipsis; color: {t['text']}; }}
.body {{ flex: 1; min-height: 0; display: flex; padding: 0 10px; gap: 8px; }}
.rail {{ width: 72px; display: flex; flex-direction: column; gap: 4px; padding-top: 4px; flex-shrink: 0; }}
.rail-item {{ border: 0; background: transparent; color: {t['muted']}; border-radius: 14px; padding: 10px 4px; display: flex; flex-direction: column; align-items: center; gap: 4px; font-size: 10px; font-family: inherit; }}
.rail-item.on {{ background: {t['accentSoft']}; color: {t['accent']}; }}
.rail-item .ico, .rail-item .ico svg {{ width: 22px; height: 22px; }}
.side {{ width: 200px; background: {t['surface']}; border-radius: {radius}; padding: 12px; overflow: auto; flex-shrink: 0; border: 1px solid {t['line']}; }}
.side-h {{ font-size: 11px; color: {t['muted']}; margin: 10px 0 6px; font-weight: 600; letter-spacing: .04em; }}
.side-item {{ padding: 8px 10px; border-radius: 10px; font-size: 13px; color: {t['text']}; }}
.side-item.on {{ background: {t['accentSoft']}; color: {t['accent']}; font-weight: 600; }}
.panes {{ flex: 1; min-width: 0; display: flex; gap: 8px; min-height: 0; }}
.pane {{ flex: 1; min-width: 0; background: {t['paneBlur']}; border-radius: {radius}; border: 1px solid {t['line']}; display: flex; flex-direction: column; overflow: hidden; }}
.pane.focus {{ background: {t['paneFocus']}; border-color: {t['accent']}; box-shadow: 0 0 0 1px {t['accentSoft']}; }}
.pane-h {{ display: flex; align-items: center; justify-content: space-between; padding: 10px 12px 8px; font-size: 13px; font-weight: 600; color: {t['muted']}; flex-shrink: 0; }}
.pane.focus .pane-h {{ color: {t['accent']}; }}
.pane-h .dot {{ width: 7px; height: 7px; border-radius: 50%; background: {t['accent']}; }}
.list {{ overflow: auto; flex: 1; }}
.row {{ display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-top: 1px solid {t['line']}; }}
.row:first-child {{ border-top: 0; }}
.row.sel {{ background: {t['selBg']}; }}
.row .ico.type, .row .ico.type svg {{ width: 24px; height: 24px; flex-shrink: 0; }}
.thumb {{ width: 36px; height: 36px; border-radius: 8px; overflow: hidden; flex-shrink: 0; }}
.thumb img {{ width: 100%; height: 100%; object-fit: cover; display: block; }}
.meta {{ min-width: 0; flex: 1; }}
.name {{ font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: {t['text']}; }}
.sub {{ font-size: 11px; color: {t['muted']}; margin-top: 1px; }}
.actions {{ flex-shrink: 0; padding: 8px 14px 6px; border-top: 1px solid {t['line']}; background: {t['surface']}; }}
.selcount {{ display: block; font-size: 11px; color: {t['muted']}; margin-bottom: 6px; }}
.btns {{ display: flex; gap: 8px; }}
.btns button {{ flex: 1; border: 0; background: {t['surface2']}; color: {t['text']}; font-weight: 600; font-size: 13px; padding: 10px 0; border-radius: 12px; font-family: inherit; }}
.btns button:first-child {{ color: {t['accent']}; }}
.gesture {{ height: 20px; display: flex; justify-content: center; align-items: center; flex-shrink: 0; }}
.gesture i {{ width: 108px; height: 4px; border-radius: 2px; background: {t['text']}; opacity: .2; }}
.page {{ flex: 1; overflow: auto; padding: 8px 14px 20px; }}
.dev {{ display: flex; align-items: center; gap: 12px; padding: 14px 4px 6px; }}
.dev-ico {{ width: 40px; height: 40px; border-radius: 12px; background: {t['surface2']}; display: flex; align-items: center; justify-content: center; color: {t['accent']}; }}
.dev-ico .ico, .dev-ico .ico svg {{ width: 22px; height: 22px; }}
.dev-meta {{ flex: 1; min-width: 0; }}
.dev-meta .name {{ font-size: 15px; font-weight: 600; }}
.dev-meta .sub {{ font-size: 12px; color: {t['muted']}; }}
.badge {{ font-size: 11px; padding: 3px 8px; border-radius: 999px; background: {t['surface2']}; color: {t['muted']}; }}
.badge.on {{ background: {t['accentSoft']}; color: {t['accent']}; }}
.dev-actions {{ display: flex; gap: 8px; padding: 0 4px 12px 56px; border-bottom: 1px solid {t['line']}; }}
.dev-actions button {{ border: 0; background: {t['surface2']}; color: {t['text']}; font-size: 12px; font-weight: 600; padding: 7px 12px; border-radius: 999px; font-family: inherit; }}
.hint {{ margin-top: 16px; font-size: 12px; color: {t['muted']}; line-height: 1.5; }}
.term {{ flex: 1; min-height: 0; display: flex; flex-direction: column; margin: 0 12px 8px; background: {t['termBg']}; border-radius: {radius}; overflow: hidden; border: 1px solid {t['line']}; }}
.tabs {{ display: flex; gap: 8px; padding: 8px 10px; border-bottom: 1px solid rgba(255,255,255,.08); }}
.tab {{ font-size: 12px; color: #888; padding: 4px 10px; border-radius: 8px; }}
.tab.on {{ background: rgba(255,255,255,.08); color: #fff; }}
.scroll {{ flex: 1; padding: 12px; font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace; font-size: 12.5px; line-height: 1.55; color: {t['termFg']}; white-space: pre-wrap; overflow: auto; }}
.p-user {{ color: #34C759; }} .p-at {{ color: #34C759; }} .p-host {{ color: #34C759; }}
.p-dir {{ color: #0A84FF; }} .p-pct {{ color: {t['termFg']}; }} .caret {{ color: {t['termFg']}; }}
.keys {{ display: flex; gap: 6px; padding: 8px; overflow: auto; border-top: 1px solid rgba(255,255,255,.08); }}
.keys span {{ flex-shrink: 0; background: rgba(255,255,255,.08); color: #ddd; font-size: 12px; padding: 6px 10px; border-radius: 8px; font-family: ui-monospace, Menlo, monospace; }}
'''

def page_wrap(letter, mode, body, w, h):
    return f'''<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>{letter}-{mode}</title>
<style>{css(letter, mode)}</style></head><body>{body}</body></html>'''

JOBS = []
for letter in "DEF":
    JOBS.append((f"design2-{letter}-main-phone-light", lambda L=letter: main_html(L, "light", False), 412, 915))
    dark_mode = "dark"
    JOBS.append((f"design2-{letter}-main-phone-dark", lambda L=letter: main_html(L, dark_mode, False), 412, 915))
    JOBS.append((f"design2-{letter}-main-tablet", lambda L=letter: main_html(L, "light" if L != "E" else "dark", True), 1280, 800))
    JOBS.append((f"design2-{letter}-devices-phone", lambda L=letter: devices_html(L, "light" if L != "E" else "dark"), 412, 915))
    JOBS.append((f"design2-{letter}-terminal-phone", lambda L=letter: terminal_html(L, "dark"), 412, 915))

def main():
    ART.mkdir(parents=True, exist_ok=True)
    html_dir = ROOT / "html"
    html_dir.mkdir(exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        for name, gen, w, h in JOBS:
            html = gen()
            path = html_dir / f"{name}.html"
            path.write_text(html, encoding="utf-8")
            page = browser.new_page(viewport={"width": w + 48, "height": h + 48}, device_scale_factor=2)
            page.goto(path.as_uri(), wait_until="networkidle")
            page.wait_for_timeout(150)
            # clip the phone element
            box = page.query_selector(".phone").bounding_box()
            out = ART / f"{name}.png"
            page.screenshot(path=str(out), clip=box, type="png")
            page.close()
            # compress if needed
            im = Image.open(out)
            if out.stat().st_size > 1_500_000:
                im.save(out, "PNG", optimize=True)
            print(f"{name}: {im.size[0]}x{im.size[1]} {out.stat().st_size//1024}KB")
        browser.close()

if __name__ == "__main__":
    main()
