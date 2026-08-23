#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".deploy" / "ownerguard_cloud"
CORE = OUT / "includes" / "core.php"
APP = OUT / "app.php"
UI = OUT / "includes" / "ui.php"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"Could not patch {label}: anchor missing")
    return text.replace(old, new, 1)


core = CORE.read_text(encoding="utf-8")
core = core.replace("const OG_VERSION = '1.3.27.6';", "const OG_VERSION = '1.3.27.7';")

helpers = r'''
function og_admin_gallery_lock(): void {
    og_start_session();
    unset($_SESSION['admin_gallery_secret'], $_SESSION['admin_gallery_user_id'], $_SESSION['admin_gallery_expires']);
}
function og_admin_gallery_session_key(): string {
    og_start_session();
    $runtime = og_runtime();
    $secret = (string)($runtime['app_secret'] ?? '');
    if ($secret === '') throw new RuntimeException('Cloud application secret is unavailable');
    return hash_hmac('sha256', 'owner-guard-admin-gallery|' . session_id(), $secret, true);
}
function og_admin_gallery_unlock(string $passphrase, int $userId): void {
    if ($passphrase === '') throw new InvalidArgumentException('Administrator escrow passphrase is required');
    $pem = @file_get_contents(OG_PRIVATE_KEY);
    $private = is_string($pem) ? @openssl_pkey_get_private($pem, $passphrase) : false;
    if (!$private) throw new RuntimeException('Administrator escrow passphrase is incorrect');
    $iv = random_bytes(12); $tag = '';
    $cipher = openssl_encrypt($passphrase, 'aes-256-gcm', og_admin_gallery_session_key(), OPENSSL_RAW_DATA, $iv, $tag);
    if ($cipher === false || strlen($tag) !== 16) throw new RuntimeException('Could not protect the gallery session');
    og_start_session();
    $_SESSION['admin_gallery_secret'] = og_b64url($iv . $tag . $cipher);
    $_SESSION['admin_gallery_user_id'] = $userId;
    $_SESSION['admin_gallery_expires'] = time() + 1800;
}
function og_admin_gallery_passphrase(int $userId, bool $touch = true): string {
    og_start_session();
    $stored = (string)($_SESSION['admin_gallery_secret'] ?? '');
    $owner = (int)($_SESSION['admin_gallery_user_id'] ?? 0);
    $expires = (int)($_SESSION['admin_gallery_expires'] ?? 0);
    if ($stored === '' || $owner !== $userId || $expires < time()) { og_admin_gallery_lock(); return ''; }
    $raw = og_b64url_decode($stored);
    if ($raw === false || strlen($raw) < 29) { og_admin_gallery_lock(); return ''; }
    $iv = substr($raw, 0, 12); $tag = substr($raw, 12, 16); $cipher = substr($raw, 28);
    $clear = openssl_decrypt($cipher, 'aes-256-gcm', og_admin_gallery_session_key(), OPENSSL_RAW_DATA, $iv, $tag);
    if (!is_string($clear) || $clear === '') { og_admin_gallery_lock(); return ''; }
    if ($touch) $_SESSION['admin_gallery_expires'] = time() + 1800;
    return $clear;
}
'''
core = replace_once(core, "function og_mgf1(string $seed, int $length): string {", helpers + "\nfunction og_mgf1(string $seed, int $length): string {", "gallery session helpers")
CORE.write_text(core, encoding="utf-8")

app = APP.read_text(encoding="utf-8")
route_block = r'''
if ($route === 'admin_gallery_media') {
    $u = og_require_web_user('admin');
    $passphrase = og_admin_gallery_passphrase((int)$u['id']);
    if ($passphrase === '') {
        http_response_code(423); og_security_headers(false); header('Content-Type: text/plain; charset=utf-8');
        echo 'Administrator media gallery is locked'; exit;
    }
    $id = (int)($_GET['id'] ?? 0);
    $st = og_db()->prepare('SELECT o.*,u.username,u.display_name FROM objects o JOIN users u ON u.id=o.user_id WHERE o.id=?');
    $st->execute([$id]); $o = $st->fetch();
    if (!$o) { http_response_code(404); exit('Not found'); }
    $mime = strtolower(trim((string)$o['mime_type']));
    if (!str_starts_with($mime, 'image/') && !str_starts_with($mime, 'video/')) { http_response_code(415); exit('Unsupported media type'); }
    try {
        $key = og_unwrap_admin((string)$o['admin_envelope'], $passphrase);
        $clear = og_decrypt_ogc2((string)$o['storage_path'], $key);
    } catch (Throwable $e) {
        og_audit((int)$u['id'], 'admin_gallery_decrypt_failed', 'id='.$id.' error='.$e->getMessage());
        http_response_code(422); og_security_headers(false); header('Content-Type: text/plain; charset=utf-8'); echo $e->getMessage(); exit;
    }
    $size = strlen($clear); $start = 0; $end = max(0, $size - 1); $partial = false;
    $range = trim((string)($_SERVER['HTTP_RANGE'] ?? ''));
    if ($range !== '' && preg_match('/^bytes=(\d*)-(\d*)$/', $range, $m)) {
        if ($m[1] !== '') $start = max(0, (int)$m[1]);
        if ($m[2] !== '') $end = min($end, (int)$m[2]);
        if ($m[1] === '' && $m[2] !== '') { $length = min($size, (int)$m[2]); $start = max(0, $size - $length); $end = max(0, $size - 1); }
        if ($start > $end || $start >= $size) { http_response_code(416); header('Content-Range: bytes */'.$size); exit; }
        $partial = true;
    }
    $length = $size > 0 ? ($end - $start + 1) : 0;
    og_audit((int)$u['id'], 'admin_gallery_media_view', 'object='.$id.' owner='.(int)$o['user_id'].' bytes='.$start.'-'.$end);
    og_security_headers(false); if ($partial) http_response_code(206);
    header('Content-Type: '.$mime); header('Accept-Ranges: bytes'); header('Content-Length: '.$length);
    if ($partial) header('Content-Range: bytes '.$start.'-'.$end.'/'.$size);
    $filename = preg_replace('/[^A-Za-z0-9._-]/', '_', (string)$o['object_name']);
    header('Content-Disposition: inline; filename="'.$filename.'"');
    echo $length === $size ? $clear : substr($clear, $start, $length); exit;
}

if ($route === 'admin_gallery') {
    $u = og_require_web_user('admin'); $msg = '';
    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
        og_require_csrf(); $action = (string)($_POST['action'] ?? 'unlock');
        if ($action === 'lock') {
            og_admin_gallery_lock(); og_audit((int)$u['id'], 'admin_gallery_lock', 'manual');
            $msg = og_success_card('Administrator media gallery locked.');
        } else {
            try {
                og_admin_gallery_unlock((string)($_POST['escrow_passphrase'] ?? ''), (int)$u['id']);
                og_audit((int)$u['id'], 'admin_gallery_unlock', '30-minute idle session');
                $msg = og_success_card('Administrator media gallery unlocked for this session.');
            } catch (Throwable $e) {
                og_audit((int)$u['id'], 'admin_gallery_unlock_failed', $e->getMessage());
                $msg = og_error_card($e->getMessage());
            }
        }
    }
    $passphrase = og_admin_gallery_passphrase((int)$u['id'], false);
    if ($passphrase === '') {
        $body = $msg.'<div class="card"><h2>Unlock all-user media gallery</h2><p>Enter the separate Administrator escrow passphrase once. It is encrypted inside this browser session, expires after 30 minutes of inactivity, and is cleared on sign-out.</p><form method="post"><input type="hidden" name="csrf" value="'.og_h(og_csrf()).'"><input type="hidden" name="action" value="unlock"><label>Administrator escrow passphrase<input type="password" name="escrow_passphrase" minlength="16" required autocomplete="current-password"></label><button>Unlock media gallery</button></form><p class="muted">The Administrator account password and recovery code cannot decrypt vault media.</p></div>';
        og_layout('Administrator media gallery', $body, $u);
    }
    $owner = max(0, (int)($_GET['owner'] ?? 0));
    $type = (string)($_GET['type'] ?? 'all'); if (!in_array($type, ['all','image','video'], true)) $type = 'all';
    $page = max(1, (int)($_GET['page'] ?? 1)); $per = 36; $offset = ($page - 1) * $per;
    $where = ["(o.mime_type LIKE 'image/%' OR o.mime_type LIKE 'video/%')", "o.admin_envelope<>''"]; $params = [];
    if ($owner > 0) { $where[] = 'o.user_id=?'; $params[] = $owner; }
    if ($type === 'image') $where[] = "o.mime_type LIKE 'image/%'";
    if ($type === 'video') $where[] = "o.mime_type LIKE 'video/%'";
    $whereSql = implode(' AND ', $where);
    $countSt = og_db()->prepare('SELECT COUNT(*) FROM objects o WHERE '.$whereSql); $countSt->execute($params); $total = (int)$countSt->fetchColumn();
    $query = 'SELECT o.*,u.username,u.display_name FROM objects o JOIN users u ON u.id=o.user_id WHERE '.$whereSql.' ORDER BY o.created_at DESC,o.id DESC LIMIT '.$per.' OFFSET '.$offset;
    $st = og_db()->prepare($query); $st->execute($params); $items = $st->fetchAll();
    $owners = og_db()->query("SELECT u.id,u.username,u.display_name,COUNT(o.id) media_count FROM users u JOIN objects o ON o.user_id=u.id WHERE (o.mime_type LIKE 'image/%' OR o.mime_type LIKE 'video/%') AND o.admin_envelope<>'' GROUP BY u.id ORDER BY u.display_name COLLATE NOCASE")->fetchAll();
    $ownerOptions = '<option value="0">All vault owners</option>';
    foreach ($owners as $row) { $label = trim((string)$row['display_name']); if ($label === '') $label = (string)$row['username']; $ownerOptions .= '<option value="'.(int)$row['id'].'"'.($owner===(int)$row['id']?' selected':'').'>'.og_h($label).' ('.(int)$row['media_count'].')</option>'; }
    $cards = '';
    foreach ($items as $o) {
        $id = (int)$o['id']; $mime = strtolower((string)$o['mime_type']); $src = 'admin_gallery_media.php?id='.$id;
        $name = trim((string)$o['display_name']); if ($name === '') $name = (string)$o['username'];
        $media = str_starts_with($mime, 'image/')
            ? '<a class="admin-media-frame" href="'.og_h($src).'" target="_blank" rel="noopener"><img loading="lazy" src="'.og_h($src).'" alt="'.og_h((string)$o['object_name']).'"></a>'
            : '<div class="admin-media-frame"><video controls playsinline preload="metadata" src="'.og_h($src).'"></video></div>';
        $cards .= '<article class="admin-media-card">'.$media.'<div class="admin-media-copy"><b>'.og_h($name).'</b><span>'.og_h((string)$o['object_name']).'</span><small>'.og_h((string)$o['event_id']).' · '.og_h(og_ui_time((int)$o['created_at'])).'</small></div></article>';
    }
    if ($cards === '') $cards = '<div class="card"><p>No encrypted image or video objects match the selected filters.</p></div>';
    $pages = max(1, (int)ceil($total / $per)); $pager = '';
    if ($pages > 1) {
        $pager = '<div class="admin-gallery-pager">';
        if ($page > 1) $pager .= '<a class="action-link" href="?'.og_h(http_build_query(['owner'=>$owner,'type'=>$type,'page'=>$page-1])).'">Previous</a>';
        $pager .= '<span>Page '.$page.' of '.$pages.'</span>';
        if ($page < $pages) $pager .= '<a class="action-link" href="?'.og_h(http_build_query(['owner'=>$owner,'type'=>$type,'page'=>$page+1])).'">Next</a>';
        $pager .= '</div>';
    }
    $expires = max(0, (int)($_SESSION['admin_gallery_expires'] ?? 0) - time());
    $style = '<style>.admin-gallery-toolbar{display:flex;gap:12px;align-items:end;flex-wrap:wrap}.admin-gallery-toolbar form{display:flex;gap:10px;align-items:end;flex-wrap:wrap}.admin-gallery-toolbar label{min-width:180px}.admin-gallery-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:14px;margin-top:14px}.admin-media-card{background:#fff;border:1px solid #dfe6ef;border-radius:14px;overflow:hidden;box-shadow:0 6px 20px rgba(20,33,61,.07)}.admin-media-frame{display:block;aspect-ratio:4/3;background:#0b1220;overflow:hidden}.admin-media-frame img,.admin-media-frame video{width:100%;height:100%;display:block;object-fit:contain;background:#0b1220}.admin-media-copy{display:grid;gap:4px;padding:12px}.admin-media-copy span,.admin-media-copy small{overflow-wrap:anywhere;color:#64748b}.admin-gallery-pager{display:flex;justify-content:center;align-items:center;gap:14px;margin:18px 0}.admin-unlock-status{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}@media(max-width:640px){.admin-gallery-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.admin-media-copy{padding:9px}.admin-media-copy span{font-size:12px}}</style>';
    $lock = '<form method="post"><input type="hidden" name="csrf" value="'.og_h(og_csrf()).'"><input type="hidden" name="action" value="lock"><button class="secondary">Lock now</button></form>';
    $filter = '<form method="get"><label>Vault owner<select name="owner">'.$ownerOptions.'</select></label><label>Media type<select name="type"><option value="all"'.($type==='all'?' selected':'').'>Images and videos</option><option value="image"'.($type==='image'?' selected':'').'>Images only</option><option value="video"'.($type==='video'?' selected':'').'>Videos only</option></select></label><button>Apply filters</button></form>';
    $body = $style.$msg.'<div class="card admin-unlock-status"><div><b>Gallery unlocked</b><p class="muted">All owners · '.$total.' matching media objects · session refreshes with activity · about '.max(1,(int)ceil($expires/60)).' minutes remaining</p></div>'.$lock.'</div><div class="card admin-gallery-toolbar">'.$filter.'</div><div class="admin-gallery-grid">'.$cards.'</div>'.$pager;
    og_layout('Administrator media gallery', $body, $u);
}

'''
app = replace_once(app, "if ($route === 'settings') { header('Location: users.php'); exit; }", route_block + "if ($route === 'settings') { header('Location: users.php'); exit; }", "admin gallery routes")
APP.write_text(app, encoding="utf-8")

ui = UI.read_text(encoding="utf-8")
ui = ui.replace("'decrypt.php','users.php'", "'decrypt.php','admin_gallery.php','admin_gallery_media.php','users.php'")
ui = ui.replace("['incidents','audit']", "['incidents','admin_gallery','audit']")
nav_anchor = "if($admin)$nav.=og_ui_nav_item('owners.php','Vault owners'"
if "admin_gallery.php','Media gallery'" not in ui:
    idx = ui.find(nav_anchor)
    if idx < 0:
        raise SystemExit("Could not add gallery drawer navigation")
    line_start = ui.rfind("\n", 0, idx) + 1
    indent = ui[line_start:idx]
    ui = ui[:line_start] + indent + "if($admin)$nav.=og_ui_nav_item('admin_gallery.php','Media gallery','M',['admin_gallery','admin_gallery_media']);\n" + ui[line_start:]
UI.write_text(ui, encoding="utf-8")

(OUT / "admin_gallery.php").write_text("<?php $_GET['route']='admin_gallery'; require __DIR__ . '/app.php';\n", encoding="utf-8")
(OUT / "admin_gallery_media.php").write_text("<?php $_GET['route']='admin_gallery_media'; require __DIR__ . '/app.php';\n", encoding="utf-8")
(OUT / "VERSION").write_text("OwnerGuard Cloud 1.3.27.7\n", encoding="utf-8")
(OUT / "ADMIN_MEDIA_GALLERY.md").write_text(
    "# Administrator Media Gallery\n\n"
    "- Requires an authenticated Administrator session.\n"
    "- Requires the separate escrow passphrase once per 30-minute idle session.\n"
    "- Stores only an AES-GCM encrypted session copy, bound to the PHP session ID and Cloud app secret.\n"
    "- Streams decrypted images and videos with no-store headers.\n"
    "- Audits unlock, lock, media view, and failures.\n"
    "- Does not persist plaintext media.\n",
    encoding="utf-8",
)
print("Applied OwnerGuard Cloud 1.3.27.7 Administrator media gallery")
