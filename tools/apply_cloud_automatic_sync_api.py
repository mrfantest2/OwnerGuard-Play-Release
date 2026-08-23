#!/usr/bin/env python3
"""Promote generated OwnerGuard Cloud to 1.3.27.3 automatic-sync API semantics."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".deploy" / "ownerguard_cloud"
APP = OUT / "app.php"
CORE = OUT / "includes" / "core.php"
VERSION = OUT / "VERSION"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Cloud automatic-sync anchor missing in {label}: {old[:220]!r}")
    return text.replace(old, new, 1)


core = CORE.read_text(encoding="utf-8")
core = replace_once(core, "const OG_VERSION = '1.3.27.2';",
                    "const OG_VERSION = '1.3.27.3';", "Cloud version constant")
CORE.write_text(core, encoding="utf-8")
VERSION.write_text("OwnerGuard Cloud 1.3.27.3\n", encoding="utf-8")

app = APP.read_text(encoding="utf-8")
old_signup = '''if ($route === 'api_signup') {
    og_method('POST'); $in=og_form_input();
    try { $username=og_clean_username((string)($in['username']??'')); } catch(Throwable $e){ og_json(['ok'=>false,'error'=>$e->getMessage()],422); }
    $display=trim((string)($in['display_name']??'')); $password=(string)($in['password']??'');
    if ($display===''||strlen($password)<10) og_json(['ok'=>false,'error'=>'Display name and a password of at least 10 characters are required'],422);
    try {
        $st=og_db()->prepare('INSERT INTO users(username,display_name,password_hash,role,enabled,created_at) VALUES(?,?,?,?,1,?)');
        $st->execute([$username,$display,og_password_hash($password),'viewer',time()]); $id=(int)og_db()->lastInsertId();
        $u=['id'=>$id,'username'=>$username,'display_name'=>$display,'role'=>'viewer','enabled'=>1]; $token=og_issue_token($id); og_audit($id,'mobile_signup','viewer account created');
        og_json(['ok'=>true,'message'=>'Account created, activated, and signed in.','token'=>$token,'user'=>og_public_user($u)],201);
    } catch(PDOException $e){ og_json(['ok'=>false,'error'=>'Username is already in use'],409); }
}
'''
new_signup = '''if ($route === 'api_signup') {
    og_method('POST'); $in=og_form_input();
    try { $username=og_clean_username((string)($in['username']??'')); }
    catch(Throwable $e){ og_json(['ok'=>false,'error'=>$e->getMessage()],422); }
    $password=(string)($in['password']??'');
    if (strlen($password)<10) og_json(['ok'=>false,'error'=>'A password of at least 10 characters is required'],422);
    // Mobile signup intentionally needs only username and password. The stable
    // account label defaults to the username and can be administered later.
    $display=$username;
    try {
        $st=og_db()->prepare('INSERT INTO users(username,display_name,password_hash,role,enabled,created_at) VALUES(?,?,?,?,1,?)');
        $st->execute([$username,$display,og_password_hash($password),'viewer',time()]);
        $id=(int)og_db()->lastInsertId();
        $u=['id'=>$id,'username'=>$username,'display_name'=>$display,'role'=>'viewer','enabled'=>1];
        $token=og_issue_token($id);
        og_audit($id,'mobile_signup','viewer account created; automatic Wi-Fi backup policy');
        og_json([
            'ok'=>true,
            'message'=>'Account created, activated, and signed in.',
            'backup_policy'=>[
                'automatic'=>true,
                'wifi_only'=>true,
                'administrator_recovery_envelope'=>true,
                'recovery_audited'=>true,
            ],
            'token'=>$token,
            'user'=>og_public_user($u)
        ],201);
    } catch(PDOException $e){ og_json(['ok'=>false,'error'=>'Username is already in use'],409); }
}
'''
app = replace_once(app, old_signup, new_signup, "username/password signup")

start = app.find("if ($route === 'api_upload') {")
end = app.find("\nif ($route === 'logout')", start)
if start < 0 or end < 0:
    raise SystemExit("Cloud upload route range was not found")
new_upload = r'''if ($route === 'api_upload') {
    og_method('PUT'); $u=og_api_user(true); $uid=$u?(int)$u['id']:null;
    $event=og_safe_id(og_header_value('X-OwnerGuard-Event',120),'Event ID');
    $name=og_safe_id(og_header_value('X-OwnerGuard-Name',120),'Object name');
    $mime=og_header_value('X-OwnerGuard-Mime',120);
    $created=(int)og_header_value('X-OwnerGuard-Created',30);
    $sha=strtolower(og_header_value('X-OwnerGuard-Sha256',64));
    $device=og_safe_id(og_header_value('X-OwnerGuard-Device',120),'Device ID');
    $deviceName=og_header_value('X-OwnerGuard-Device-Name',100);
    $crypto=(int)og_header_value('X-OwnerGuard-Crypto-Version',5);
    $userEnv=og_header_value('X-OwnerGuard-User-Envelope',1000);
    $adminEnv=og_header_value('X-OwnerGuard-Admin-Envelope',2000);
    $adminKey=og_safe_id(og_header_value('X-OwnerGuard-Admin-Key-Id',80),'Administrator key ID');
    $migration=trim((string)($_SERVER['HTTP_X_OWNERGUARD_MIGRATION']??''))==='1';
    $runtime=og_runtime();
    if ($crypto!==2 || $userEnv==='' || $adminEnv==='' || !hash_equals((string)$runtime['escrow_key_id'],$adminKey))
        og_json(['ok'=>false,'error'=>'Valid OGC2 dual-envelope metadata is required'],422);
    if (!preg_match('/^[a-f0-9]{64}$/',$sha) || $created<1)
        og_json(['ok'=>false,'error'=>'Upload metadata is invalid'],422);
    if ($migration && $uid===null)
        og_json(['ok'=>false,'error'=>'Automatic migration requires an authenticated Cloud account'],403);

    $existing=null;
    if ($uid!==null) {
        $lookup=og_db()->prepare('SELECT * FROM objects WHERE user_id=? AND event_id=? AND object_name=?');
        $lookup->execute([$uid,$event,$name]); $existing=$lookup->fetch()?:null;
    }
    $alreadyCurrent=$migration && is_array($existing)
        && (int)$existing['crypto_version']===2
        && (string)$existing['user_envelope']!==''
        && (string)$existing['admin_envelope']!==''
        && hash_equals((string)$runtime['escrow_key_id'],(string)$existing['admin_key_id']);

    $bucket=OG_OBJECTS.'/'.($uid===null?'legacy':'u'.$uid).'/'.$event;
    if (!is_dir($bucket) && !mkdir($bucket,0700,true) && !is_dir($bucket))
        og_json(['ok'=>false,'error'=>'Storage is unavailable'],500);
    $path=$bucket.'/'.$name.'.ogc';
    if (!$migration && is_file($path)) og_json(['ok'=>true,'duplicate'=>true]);

    $tmp=$path.'.upload.'.bin2hex(random_bytes(6));
    $in=fopen('php://input','rb'); $out=fopen($tmp,'xb');
    if(!$in||!$out) og_json(['ok'=>false,'error'=>'Cannot open upload stream'],500);
    $hash=hash_init('sha256'); $total=0;
    try {
        while(!feof($in)){
            $chunk=fread($in,65536);
            if($chunk===false)throw new RuntimeException('Upload read failed');
            if($chunk==='')continue;
            $total+=strlen($chunk);
            if($total>OG_MAX_OBJECT)throw new RuntimeException('Object exceeds the upload limit');
            hash_update($hash,$chunk);
            if(fwrite($out,$chunk)!==strlen($chunk))throw new RuntimeException('Upload write failed');
        }
        fflush($out);
    } catch(Throwable $e){
        fclose($in);fclose($out);@unlink($tmp);
        og_json(['ok'=>false,'error'=>$e->getMessage()],413);
    }
    fclose($in);fclose($out);
    $actual=hash_final($hash);
    if(!hash_equals($sha,$actual)){@unlink($tmp);og_json(['ok'=>false,'error'=>'Object SHA-256 mismatch'],422);}
    @chmod($tmp,0600);

    // Consume and verify the complete request before returning an idempotent
    // migration result. This avoids client-side broken pipes on large objects.
    if($alreadyCurrent){
        @unlink($tmp);
        og_json(['ok'=>true,'duplicate'=>true,'already_current'=>true,'size'=>$total]);
    }

    $archive=null;
    if ($migration && is_file($path)) {
        $archive=$path.'.legacy.'.time().'.'.bin2hex(random_bytes(4)).'.ogc';
        if(!rename($path,$archive)){@unlink($tmp);og_json(['ok'=>false,'error'=>'Cannot archive the previous encrypted object'],500);}
    }
    if(!rename($tmp,$path)){
        @unlink($tmp);
        if($archive!==null&&is_file($archive))@rename($archive,$path);
        og_json(['ok'=>false,'error'=>'Cannot finalize object'],500);
    }

    try {
        if (is_array($existing)) {
            $st=og_db()->prepare('UPDATE objects SET mime_type=?,created_at=?,received_at=?,size_bytes=?,sha256=?,device_id=?,device_name=?,crypto_version=?,user_envelope=?,admin_envelope=?,admin_key_id=?,storage_path=? WHERE id=? AND user_id=?');
            $st->execute([$mime,$created,time(),$total,$sha,$device,$deviceName,$crypto,$userEnv,$adminEnv,$adminKey,$path,(int)$existing['id'],$uid]);
            og_audit($uid,'object_migrated',$event.'/'.$name.' '.$total.' bytes; previous ciphertext archived');
            og_json(['ok'=>true,'migrated'=>true,'size'=>$total],200);
        }
        $st=og_db()->prepare('INSERT INTO objects(user_id,event_id,object_name,mime_type,created_at,received_at,size_bytes,sha256,device_id,device_name,crypto_version,user_envelope,admin_envelope,admin_key_id,storage_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)');
        $st->execute([$uid,$event,$name,$mime,$created,time(),$total,$sha,$device,$deviceName,$crypto,$userEnv,$adminEnv,$adminKey,$path]);
    } catch(Throwable $e){
        @unlink($path);
        if($archive!==null&&is_file($archive))@rename($archive,$path);
        og_json(['ok'=>false,'error'=>'Object metadata could not be saved'],500);
    }
    og_audit($uid,'object_upload',$event.'/'.$name.' '.$total.' bytes');
    og_json(['ok'=>true,'stored'=>true,'size'=>$total],201);
}
'''
app = app[:start] + new_upload + app[end:]
APP.write_text(app, encoding="utf-8")

combined = app + core + VERSION.read_text(encoding="utf-8")
for token in (
    "OwnerGuard Cloud 1.3.27.3",
    "A password of at least 10 characters is required",
    "automatic Wi-Fi backup policy",
    "HTTP_X_OWNERGUARD_MIGRATION",
    "object_migrated",
    "previous ciphertext archived",
    "already_current",
    "Consume and verify the complete request",
):
    if token not in combined:
        raise SystemExit("Cloud automatic-sync API output incomplete: " + token)

for forbidden in (
    "Display name and a password of at least 10 characters are required",
    "$display=trim((string)($in['display_name']??''))",
):
    if forbidden in combined:
        raise SystemExit("Obsolete signup requirement remains: " + forbidden)

print("Applied OwnerGuard Cloud 1.3.27.3 username/password signup and migration-safe uploads")
