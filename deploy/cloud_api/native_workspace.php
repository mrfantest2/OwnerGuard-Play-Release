<?php
declare(strict_types=1);
require_once __DIR__ . '/../includes/core.php';

function og_native_owner_label(array $row): string {
    $display = trim((string)($row['display_name'] ?? ''));
    if ($display !== '') return $display;
    $username = trim((string)($row['username'] ?? ''));
    return $username !== '' ? $username : 'Legacy vault';
}
function og_native_device_label(array $row): string {
    $name = trim((string)($row['device_name'] ?? ''));
    if ($name !== '') return $name;
    $id = trim((string)($row['device_id'] ?? ''));
    return $id !== '' ? $id : 'Unknown device';
}
function og_native_scoped_rows(array $user, int $limit = 5000): array {
    $limit = max(50, min(5000, $limit));
    if (($user['role'] ?? '') === 'admin') {
        $sql = 'SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id ORDER BY o.received_at DESC LIMIT ' . $limit;
        return og_db()->query($sql)->fetchAll();
    }
    $st = og_db()->prepare('SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE o.user_id=? ORDER BY o.received_at DESC LIMIT ' . $limit);
    $st->execute([(int)$user['id']]);
    return $st->fetchAll();
}
function og_native_events(array $rows): array {
    $events = [];
    foreach ($rows as $row) {
        $ownerId = $row['user_id'] === null ? 'legacy' : (string)$row['user_id'];
        $key = $ownerId . '|' . (string)$row['event_id'];
        if (!isset($events[$key])) {
            $events[$key] = [
                'event' => (string)$row['event_id'],
                'owner_id' => $ownerId,
                'owner' => og_native_owner_label($row),
                'device' => og_native_device_label($row),
                'devices' => [],
                'objects' => 0,
                'photos' => 0,
                'videos' => 0,
                'bytes' => 0,
                'captured_at' => (int)$row['created_at'],
                'received_at' => (int)($row['received_at'] ?? $row['created_at']),
            ];
        }
        $events[$key]['objects']++;
        $events[$key]['bytes'] += (int)($row['size_bytes'] ?? 0);
        $events[$key]['captured_at'] = min((int)$events[$key]['captured_at'], (int)$row['created_at']);
        $events[$key]['received_at'] = max((int)$events[$key]['received_at'], (int)($row['received_at'] ?? $row['created_at']));
        $events[$key]['devices'][og_native_device_label($row)] = true;
        $mime = (string)($row['mime_type'] ?? '');
        if (str_starts_with($mime, 'image/')) $events[$key]['photos']++;
        if (str_starts_with($mime, 'video/')) $events[$key]['videos']++;
    }
    $out = [];
    foreach ($events as $event) {
        $event['device'] = implode(', ', array_keys($event['devices']));
        unset($event['devices']);
        $out[] = $event;
    }
    usort($out, static fn(array $a, array $b): int => ((int)$b['received_at']) <=> ((int)$a['received_at']));
    return $out;
}
function og_native_require_admin(array $user): void {
    if (($user['role'] ?? '') !== 'admin') og_json(['ok'=>false,'error'=>'Administrator access required'], 403);
}
function og_native_response(array $user, string $section, array $payload): never {
    og_json(array_merge([
        'ok' => true,
        'section' => $section,
        'generated_at' => time(),
        'user' => og_public_user($user),
    ], $payload));
}

$user = og_api_user();
if (!$user) og_json(['ok'=>false,'error'=>'Account session required'], 401);
$method = strtoupper((string)($_SERVER['REQUEST_METHOD'] ?? 'GET'));
$input = $method === 'POST' ? og_form_input() : $_GET;
$section = strtolower(trim((string)($input['section'] ?? 'dashboard')));
$action = strtolower(trim((string)($input['action'] ?? '')));

if ($method === 'POST') {
    if ($action !== 'toggle_user') og_json(['ok'=>false,'error'=>'Unsupported native action'], 400);
    og_native_require_admin($user);
    $id = (int)($input['id'] ?? 0);
    if ($id < 1) og_json(['ok'=>false,'error'=>'A valid user ID is required'], 422);
    if ($id === (int)$user['id']) og_json(['ok'=>false,'error'=>'You cannot disable your active Administrator account'], 409);
    $st = og_db()->prepare('SELECT id,enabled FROM users WHERE id=?');
    $st->execute([$id]);
    $target = $st->fetch();
    if (!$target) og_json(['ok'=>false,'error'=>'User account not found'], 404);
    $newEnabled = ((int)$target['enabled']) === 1 ? 0 : 1;
    og_db()->beginTransaction();
    try {
        og_db()->prepare('UPDATE users SET enabled=? WHERE id=?')->execute([$newEnabled,$id]);
        og_db()->prepare('UPDATE api_tokens SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL')->execute([time(),$id]);
        og_audit((int)$user['id'], 'native_user_status_toggle', 'user_id=' . $id . ' enabled=' . $newEnabled);
        og_db()->commit();
    } catch (Throwable $error) {
        if (og_db()->inTransaction()) og_db()->rollBack();
        og_json(['ok'=>false,'error'=>'User status could not be updated'], 500);
    }
    og_json(['ok'=>true,'message'=>$newEnabled ? 'User enabled' : 'User disabled','enabled'=>(bool)$newEnabled]);
}

if ($section === 'dashboard') {
    $rows = og_native_scoped_rows($user);
    $events = og_native_events($rows);
    $owners = [];
    $devices = [];
    $photos = 0;
    $videos = 0;
    $latest = 0;
    foreach ($rows as $row) {
        $owners[$row['user_id'] === null ? 'legacy' : (string)$row['user_id']] = true;
        $devices[og_native_device_label($row)] = true;
        $mime = (string)($row['mime_type'] ?? '');
        if (str_starts_with($mime, 'image/')) $photos++;
        if (str_starts_with($mime, 'video/')) $videos++;
        $latest = max($latest, (int)($row['received_at'] ?? $row['created_at']));
    }
    $crypto = og_crypto_capabilities();
    og_native_response($user, $section, [
        'metrics' => [
            'incidents' => count($events),
            'objects' => count($rows),
            'owners' => count($owners),
            'devices' => count($devices),
            'photos' => $photos,
            'videos' => $videos,
            'latest_upload' => $latest,
            'crypto_ready' => (bool)($crypto['ready'] ?? false),
            'crypto_backend' => !empty($crypto['php_openssl']) ? 'PHP OpenSSL' : (!empty($crypto['openssl_cli']) ? 'OpenSSL CLI' : 'Unavailable'),
        ],
        'items' => array_slice($events, 0, 12),
    ]);
}

if ($section === 'incidents') {
    $rows = og_native_scoped_rows($user);
    $events = og_native_events($rows);
    $query = trim((string)($input['q'] ?? ''));
    $owner = trim((string)($input['owner'] ?? ''));
    $device = trim((string)($input['device'] ?? ''));
    $filtered = array_values(array_filter($events, static function(array $event) use ($query, $owner, $device): bool {
        if ($owner !== '' && (string)$event['owner_id'] !== $owner) return false;
        if ($device !== '' && stripos((string)$event['device'], $device) === false) return false;
        if ($query === '') return true;
        return stripos((string)$event['event'], $query) !== false
            || stripos((string)$event['owner'], $query) !== false
            || stripos((string)$event['device'], $query) !== false;
    }));
    og_native_response($user, $section, [
        'summary' => ['shown'=>min(count($filtered),200),'total'=>count($events)],
        'items' => array_slice($filtered, 0, 200),
    ]);
}

if ($section === 'incident') {
    $event = trim((string)($input['event'] ?? ''));
    $owner = trim((string)($input['owner'] ?? ''));
    if ($event === '' || !preg_match('/^[A-Za-z0-9._-]{1,160}$/', $event)) og_json(['ok'=>false,'error'=>'Valid incident ID required'], 422);
    if (($user['role'] ?? '') === 'admin') {
        if ($owner === 'legacy') {
            $sql = 'SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE event_id=? AND o.user_id IS NULL ORDER BY object_name';
            $args = [$event];
        } elseif ($owner !== '' && ctype_digit($owner)) {
            $sql = 'SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE event_id=? AND o.user_id=? ORDER BY object_name';
            $args = [$event,(int)$owner];
        } else {
            $sql = 'SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE event_id=? ORDER BY user_id,object_name';
            $args = [$event];
        }
    } else {
        $sql = 'SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE event_id=? AND o.user_id=? ORDER BY object_name';
        $args = [$event,(int)$user['id']];
    }
    $st = og_db()->prepare($sql);
    $st->execute($args);
    $rows = $st->fetchAll();
    if (!$rows) og_json(['ok'=>false,'error'=>'Incident not found'], 404);
    $objects = [];
    $devices = [];
    $bytes = 0;
    $captured = PHP_INT_MAX;
    $received = 0;
    foreach ($rows as $row) {
        $devices[og_native_device_label($row)] = true;
        $bytes += (int)($row['size_bytes'] ?? 0);
        $captured = min($captured, (int)$row['created_at']);
        $received = max($received, (int)($row['received_at'] ?? $row['created_at']));
        $objects[] = [
            'id' => (int)$row['id'],
            'name' => (string)$row['object_name'],
            'mime' => (string)$row['mime_type'],
            'captured_at' => (int)$row['created_at'],
            'received_at' => (int)($row['received_at'] ?? $row['created_at']),
            'size' => (int)$row['size_bytes'],
            'crypto_version' => (int)$row['crypto_version'],
            'sha256' => (string)$row['sha256'],
        ];
    }
    og_audit((int)$user['id'], 'native_incident_metadata_view', $event . ' objects=' . count($objects));
    og_native_response($user, $section, [
        'incident' => [
            'event' => $event,
            'owner_id' => $rows[0]['user_id'] === null ? 'legacy' : (string)$rows[0]['user_id'],
            'owner' => og_native_owner_label($rows[0]),
            'devices' => array_keys($devices),
            'captured_at' => $captured,
            'received_at' => $received,
            'bytes' => $bytes,
            'objects' => $objects,
        ],
    ]);
}

if ($section === 'owners') {
    og_native_require_admin($user);
    $groups = [];
    foreach (og_native_scoped_rows($user) as $row) {
        $id = $row['user_id'] === null ? 'legacy' : (string)$row['user_id'];
        if (!isset($groups[$id])) $groups[$id] = [
            'id'=>$id,'name'=>og_native_owner_label($row),'username'=>(string)($row['username'] ?? 'legacy'),
            'objects'=>0,'events'=>[],'devices'=>[],'last'=>0,
        ];
        $groups[$id]['objects']++;
        $groups[$id]['events'][(string)$row['event_id']] = true;
        $groups[$id]['devices'][og_native_device_label($row)] = true;
        $groups[$id]['last'] = max((int)$groups[$id]['last'], (int)($row['received_at'] ?? $row['created_at']));
    }
    $items = [];
    foreach ($groups as $group) {
        $items[] = [
            'id'=>$group['id'],'name'=>$group['name'],'username'=>$group['username'],
            'objects'=>(int)$group['objects'],'incidents'=>count($group['events']),
            'devices'=>count($group['devices']),'last'=>(int)$group['last'],
        ];
    }
    usort($items, static fn(array $a,array $b): int => ((int)$b['last']) <=> ((int)$a['last']));
    og_native_response($user, $section, ['items'=>array_slice($items,0,500)]);
}

if ($section === 'devices') {
    $groups = [];
    foreach (og_native_scoped_rows($user) as $row) {
        $label = og_native_device_label($row);
        $id = trim((string)($row['device_id'] ?? ''));
        if ($id === '') $id = $label;
        if (!isset($groups[$id])) $groups[$id] = [
            'id'=>$id,'name'=>$label,'objects'=>0,'events'=>[],'owners'=>[],'photos'=>0,'videos'=>0,'last'=>0,
        ];
        $groups[$id]['objects']++;
        $groups[$id]['events'][($row['user_id'] === null ? 'legacy' : (string)$row['user_id']) . '|' . (string)$row['event_id']] = true;
        $groups[$id]['owners'][og_native_owner_label($row)] = true;
        $mime = (string)($row['mime_type'] ?? '');
        if (str_starts_with($mime, 'image/')) $groups[$id]['photos']++;
        if (str_starts_with($mime, 'video/')) $groups[$id]['videos']++;
        $groups[$id]['last'] = max((int)$groups[$id]['last'], (int)($row['received_at'] ?? $row['created_at']));
    }
    $items = [];
    foreach ($groups as $group) {
        $items[] = [
            'id'=>$group['id'],'name'=>$group['name'],'objects'=>(int)$group['objects'],
            'incidents'=>count($group['events']),'owners'=>count($group['owners']),
            'photos'=>(int)$group['photos'],'videos'=>(int)$group['videos'],'last'=>(int)$group['last'],
        ];
    }
    usort($items, static fn(array $a,array $b): int => ((int)$b['last']) <=> ((int)$a['last']));
    og_native_response($user, $section, ['items'=>array_slice($items,0,500)]);
}

if ($section === 'users') {
    og_native_require_admin($user);
    $rows = og_db()->query('SELECT id,username,display_name,role,enabled,created_at,last_login_at FROM users ORDER BY created_at DESC LIMIT 500')->fetchAll();
    foreach ($rows as &$row) {
        $row['id'] = (int)$row['id'];
        $row['enabled'] = (bool)$row['enabled'];
        $row['created_at'] = (int)$row['created_at'];
        $row['last_login_at'] = $row['last_login_at'] === null ? null : (int)$row['last_login_at'];
        $row['self'] = (int)$row['id'] === (int)$user['id'];
    }
    unset($row);
    og_native_response($user, $section, ['items'=>$rows]);
}

if ($section === 'audit') {
    og_native_require_admin($user);
    $query = trim((string)($input['q'] ?? ''));
    $rows = og_db()->query('SELECT a.id,a.user_id,a.action,a.detail,a.created_at,u.username FROM audit_log a LEFT JOIN users u ON u.id=a.user_id ORDER BY a.id DESC LIMIT 500')->fetchAll();
    if ($query !== '') $rows = array_values(array_filter($rows, static fn(array $row): bool =>
        stripos((string)($row['username'] ?? 'system'), $query) !== false
        || stripos((string)$row['action'], $query) !== false
        || stripos((string)$row['detail'], $query) !== false
    ));
    foreach ($rows as &$row) {
        $row['id'] = (int)$row['id'];
        $row['created_at'] = (int)$row['created_at'];
        $row['username'] = (string)($row['username'] ?? 'system');
    }
    unset($row);
    og_native_response($user, $section, ['items'=>array_slice($rows,0,250)]);
}

if ($section === 'system') {
    og_native_require_admin($user);
    $crypto = og_crypto_capabilities();
    $db = og_db();
    og_native_response($user, $section, ['system'=>[
        'cloud_version'=>OG_VERSION,
        'crypto_ready'=>(bool)($crypto['ready'] ?? false),
        'crypto_backend'=>!empty($crypto['php_openssl']) ? 'PHP OpenSSL' : (!empty($crypto['openssl_cli']) ? 'OpenSSL CLI' : 'Unavailable'),
        'php_openssl'=>(bool)($crypto['php_openssl'] ?? false),
        'openssl_cli'=>(bool)($crypto['openssl_cli'] ?? false),
        'data_writable'=>(bool)($crypto['data_writable'] ?? false),
        'php_version'=>(string)($crypto['php_version'] ?? PHP_VERSION),
        'users'=>(int)$db->query('SELECT COUNT(*) FROM users')->fetchColumn(),
        'objects'=>(int)$db->query('SELECT COUNT(*) FROM objects')->fetchColumn(),
        'audit_events'=>(int)$db->query('SELECT COUNT(*) FROM audit_log')->fetchColumn(),
    ]]);
}

if ($section === 'updates') {
    og_native_require_admin($user);
    $manifest = is_file(OG_RELEASE) ? json_decode((string)file_get_contents(OG_RELEASE), true) : [];
    if (!is_array($manifest)) $manifest = [];
    og_native_response($user, $section, ['update'=>[
        'available'=>($manifest['available'] ?? false) === true,
        'mandatory'=>($manifest['forceUpdate'] ?? false) === true || ($manifest['updatePolicy'] ?? '') === 'mandatory',
        'package'=>(string)($manifest['packageName'] ?? 'com.fantest.ownerguard'),
        'version_name'=>(string)($manifest['versionName'] ?? 'Unavailable'),
        'version_code'=>(int)($manifest['versionCode'] ?? 0),
        'minimum_version_code'=>(int)($manifest['minimumVersionCode'] ?? 0),
        'min_sdk'=>(int)($manifest['minSdk'] ?? 0),
        'size'=>(int)($manifest['size'] ?? 0),
        'sha256'=>(string)($manifest['sha256'] ?? ''),
        'apk_url'=>(string)($manifest['apkUrl'] ?? ''),
        'release_notes'=>(string)($manifest['releaseNotes'] ?? ''),
    ]]);
}

og_json(['ok'=>false,'error'=>'Unknown native workspace section'], 404);
