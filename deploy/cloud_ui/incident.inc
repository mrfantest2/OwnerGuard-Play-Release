function og_render_incident_page():never{
    $u=og_require_web_user();
    $event=og_safe_id((string)($_GET['event']??''),'Event ID');
    $owner=(string)($_GET['owner']??'');
    if(($u['role']??'')==='admin'){
        if($owner==='legacy'){$sql='SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE event_id=? AND o.user_id IS NULL ORDER BY object_name';$args=[$event];}
        elseif($owner!==''&&ctype_digit($owner)){$sql='SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE event_id=? AND o.user_id=? ORDER BY object_name';$args=[$event,(int)$owner];}
        else{$sql='SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE event_id=? ORDER BY user_id,object_name';$args=[$event];}
    }else{$sql='SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE event_id=? AND o.user_id=? ORDER BY object_name';$args=[$event,(int)$u['id']];}
    $st=og_db()->prepare($sql);$st->execute($args);$rows=$st->fetchAll();
    if(!$rows){http_response_code(404);og_layout('Incident not found',og_error_card('No accessible encrypted objects were found.'),$u);}
    $ownerLabel=og_ui_owner_label($rows[0]);$devices=[];$photos=0;$videos=0;$bytes=0;
    foreach($rows as$r){$devices[og_ui_device_label($r)]=true;$mime=(string)($r['mime_type']??'');if(str_starts_with($mime,'image/'))$photos++;if(str_starts_with($mime,'video/'))$videos++;$bytes+=(int)($r['size_bytes']??0);}
    $captured=min(array_map(static fn(array$r):int=>(int)$r['created_at'],$rows));$received=max(array_map(static fn(array$r):int=>(int)($r['received_at']??$r['created_at']),$rows));
    og_audit((int)$u['id'],'incident_metadata_view',$event.' owner='.$ownerLabel.' objects='.count($rows));
    $body='<div class="breadcrumb"><a href="incidents.php">All incidents</a><span>/</span><b>'.og_h($event).'</b></div>';
    $body.='<div class="card success"><b>Vault metadata is available without recovery</b><p class="muted">Administrators can browse incident ownership, device, timestamps, media types, sizes, encryption state, and integrity hashes without entering the escrow passphrase. Photos and videos remain encrypted; recovery is required only when plaintext media is opened.</p></div>';
    $body.='<div class="grid"><div class="card metric"><b>'.og_h($ownerLabel).'</b><div class="muted">Vault owner</div></div><div class="card metric"><b>'.count($rows).'</b><div class="muted">Encrypted objects</div></div><div class="card metric"><b>'.count($devices).'</b><div class="muted">Source devices</div></div><div class="card metric"><b>'.number_format($bytes).'</b><div class="muted">Encrypted bytes</div></div></div>';
    $body.='<div class="card"><div class="card-head"><div><h2>'.og_h($event).'</h2><p class="muted">Captured '.og_h(og_ui_time($captured)).' · Last received '.og_h(og_ui_time($received)).'</p></div><span class="status-badge good">Encrypted OGC2</span></div><div class="incident-summary"><span>Devices: <b>'.og_h(implode(', ',array_keys($devices))).'</b></span><span>Media: <b>'.$photos.' photos · '.$videos.' videos</b></span></div><div class="table-shell scroll"><table><thead><tr><th>Object</th><th>Media type</th><th>Captured</th><th>Received</th><th>Size</th><th>Crypto</th><th>SHA-256 integrity</th><th>Action</th></tr></thead><tbody>';
    foreach($rows as$r){$sha=(string)($r['sha256']??'');$body.='<tr><td><b>'.og_h((string)$r['object_name']).'</b></td><td>'.og_h((string)$r['mime_type']).'</td><td>'.og_h(og_ui_time((int)$r['created_at'])).'</td><td>'.og_h(og_ui_time((int)($r['received_at']??$r['created_at']))).'</td><td>'.number_format((int)$r['size_bytes']).' B</td><td><span class="pill">OGC'.(int)$r['crypto_version'].'</span></td><td><span class="mono">'.og_h($sha).'</span></td><td><a class="action-link" href="decrypt.php?id='.(int)$r['id'].'">Recover media</a></td></tr>';}
    $body.='</tbody></table></div></div><div class="note">Browsing this page never decrypts or writes plaintext media. Recovery remains an explicit, audited action.</div>';
    og_layout('Incident metadata',$body,$u);
}
