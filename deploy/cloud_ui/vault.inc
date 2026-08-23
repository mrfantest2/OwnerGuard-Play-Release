function og_ui_scope_rows(array $u,int $limit=2000):array{
    $limit=max(100,min(5000,$limit));
    if(($u['role']??'')==='admin'){
        $sql='SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id ORDER BY o.created_at DESC LIMIT '.$limit;
        $st=og_db()->query($sql);
    }else{
        $sql='SELECT o.*,u.username,u.display_name FROM objects o LEFT JOIN users u ON u.id=o.user_id WHERE o.user_id=? ORDER BY o.created_at DESC LIMIT '.$limit;
        $st=og_db()->prepare($sql);$st->execute([(int)$u['id']]);
    }
    return $st->fetchAll();
}
function og_ui_owner_label(array $row):string{
    $display=trim((string)($row['display_name']??''));if($display!=='')return $display;
    $username=trim((string)($row['username']??''));return $username!==''?$username:'Legacy vault';
}
function og_ui_device_label(array $row):string{
    $name=trim((string)($row['device_name']??''));if($name!=='')return $name;
    $id=trim((string)($row['device_id']??''));return $id!==''?$id:'Unknown device';
}
function og_ui_events(array $rows):array{
    $events=[];
    foreach($rows as$r){
        $key=($r['user_id']??'legacy').'|'.(string)$r['event_id'];
        if(!isset($events[$key]))$events[$key]=[
            'event'=>(string)$r['event_id'],'username'=>og_ui_owner_label($r),'owner_id'=>$r['user_id']??null,
            'created'=>(int)$r['created_at'],'received'=>(int)($r['received_at']??$r['created_at']),
            'device'=>og_ui_device_label($r),'devices'=>[],'objects'=>0,'media'=>[]
        ];
        $events[$key]['objects']++;
        $events[$key]['created']=min((int)$events[$key]['created'],(int)$r['created_at']);
        $events[$key]['received']=max((int)$events[$key]['received'],(int)($r['received_at']??$r['created_at']));
        $events[$key]['devices'][og_ui_device_label($r)]=true;
        $mime=(string)($r['mime_type']??'application/octet-stream');$events[$key]['media'][$mime]=true;
        $events[$key]['device']=implode(', ',array_keys($events[$key]['devices']));
    }
    return array_values($events);
}
function og_render_dashboard_page():never{
    $u=og_require_web_user();$rows=og_ui_scope_rows($u);$events=og_ui_events($rows);
    $owners=[];$devices=[];$photos=0;$videos=0;$latest=0;
    foreach($rows as$r){$owners[(string)($r['user_id']??'legacy')]=true;$devices[og_ui_device_label($r)]=true;$mime=(string)($r['mime_type']??'');if(str_starts_with($mime,'image/'))$photos++;if(str_starts_with($mime,'video/'))$videos++;$latest=max($latest,(int)($r['received_at']??$r['created_at']));}
    $recent=array_slice($events,0,8);$crypto=og_crypto_capabilities();
    $body='<div class="grid dashboard-grid"><div class="card metric"><span class="metric-icon">◆</span><b>'.count($events).'</b><div class="muted">Encrypted incidents</div></div><div class="card metric"><span class="metric-icon">▤</span><b>'.count($rows).'</b><div class="muted">Encrypted objects</div></div><div class="card metric"><span class="metric-icon">◎</span><b>'.count($owners).'</b><div class="muted">Vault owners</div></div><div class="card metric"><span class="metric-icon">▣</span><b>'.count($devices).'</b><div class="muted">Source devices</div></div></div>';
    $body.='<div class="split"><div class="card"><div class="card-head"><div><h2>Vault readiness</h2><p class="muted">Operational status for encrypted evidence and recovery.</p></div><span class="status-badge '.($crypto['ready']?'good':'danger').'">'.($crypto['ready']?'Ready':'Blocked').'</span></div><div class="status-list"><div><span>Encryption format</span><b>OGC2 dual-envelope</b></div><div><span>Latest upload</span><b>'.og_h(og_ui_time($latest)).'</b></div><div><span>Photos retained</span><b>'.$photos.'</b></div><div><span>Videos retained</span><b>'.$videos.'</b></div></div><div class="note">Administrators can inspect metadata without recovery. Plaintext media remains protected by the audited recovery workflow.</div></div><div class="card"><h2>Quick actions</h2><div class="quick-actions"><a href="incidents.php"><span>◆</span><b>Browse incidents</b><small>Search all encrypted events</small></a><a href="devices.php"><span>▣</span><b>Review devices</b><small>Group evidence by source</small></a>'.(($u['role']??'')==='admin'?'<a href="owners.php"><span>◎</span><b>Vault owners</b><small>Review owner-level totals</small></a><a href="system.php"><span>◉</span><b>System status</b><small>Check cryptography and storage</small></a>':'').'</div></div></div>';
    $body.='<div class="card"><div class="card-head"><div><h2>Recent incidents</h2><p class="muted">Latest encrypted evidence received by OwnerGuard Cloud.</p></div><a class="action-link" href="incidents.php">View all incidents</a></div>'.og_ui_event_table($recent,true).'</div>';
    og_layout('Dashboard',$body,$u);
}
function og_render_incidents_page():never{
    $u=og_require_web_user();$rows=og_ui_scope_rows($u);$events=og_ui_events($rows);
    $q=trim((string)($_GET['q']??''));$owner=(string)($_GET['owner']??'');$device=trim((string)($_GET['device']??''));
    $filtered=array_values(array_filter($events,static function(array$e)use($q,$owner,$device):bool{
        if($owner!==''){$candidate=$e['owner_id']===null?'legacy':(string)$e['owner_id'];if($candidate!==$owner)return false;}
        if($device!==''&&stripos((string)$e['device'],$device)===false)return false;
        if($q==='')return true;
        return stripos((string)$e['event'],$q)!==false||stripos((string)$e['username'],$q)!==false||stripos((string)$e['device'],$q)!==false;
    }));
    $body='<div class="card compact"><form class="toolbar" method="get" action="incidents.php"><label>Search vault<input name="q" value="'.og_h($q).'" placeholder="Incident, owner, or device"></label>';
    if($owner!=='')$body.='<input type="hidden" name="owner" value="'.og_h($owner).'">';if($device!=='')$body.='<input type="hidden" name="device" value="'.og_h($device).'">';
    $body.='<div class="toolbar-action"><button>Search</button></div></form><div class="filter-summary"><span>Showing <b>'.count($filtered).'</b> of '.count($events).' incidents</span>'.(($owner!==''||$device!==''||$q!=='')?'<a href="incidents.php">Clear filters</a>':'').'</div></div><div class="card">'.og_ui_event_table($filtered,true).'</div><div class="note">Incident lists expose metadata only. Opening or downloading plaintext photos and videos remains a separate recovery action.</div>';
    og_layout('All incidents',$body,$u);
}
function og_render_owners_page():never{
    $u=og_require_web_user('admin');$rows=og_ui_scope_rows($u,5000);$groups=[];
    foreach($rows as$r){$id=$r['user_id']===null?'legacy':(string)$r['user_id'];if(!isset($groups[$id]))$groups[$id]=['id'=>$id,'name'=>og_ui_owner_label($r),'username'=>(string)($r['username']??'legacy'),'objects'=>0,'events'=>[],'devices'=>[],'last'=>0];$groups[$id]['objects']++;$groups[$id]['events'][(string)$r['event_id']]=true;$groups[$id]['devices'][og_ui_device_label($r)]=true;$groups[$id]['last']=max($groups[$id]['last'],(int)($r['received_at']??$r['created_at']));}
    uasort($groups,static fn(array$a,array$b):int=>$b['last']<=>$a['last']);
    $body='<div class="card"><div class="card-head"><div><h2>Vault owners</h2><p class="muted">Owner-level inventory without decrypting media.</p></div><span class="pill">'.count($groups).' owners</span></div><div class="table-shell scroll"><table><thead><tr><th>Owner</th><th>Incidents</th><th>Objects</th><th>Devices</th><th>Latest activity</th><th>Action</th></tr></thead><tbody>';
    foreach($groups as$g)$body.='<tr><td><b>'.og_h($g['name']).'</b><br><span class="muted">@'.og_h($g['username']).'</span></td><td>'.count($g['events']).'</td><td>'.(int)$g['objects'].'</td><td>'.count($g['devices']).'</td><td>'.og_h(og_ui_time((int)$g['last'])).'</td><td><a class="action-link" href="incidents.php?owner='.rawurlencode((string)$g['id']).'">View incidents</a></td></tr>';
    $body.='</tbody></table></div></div>';
    og_layout('Vault owners',$body,$u);
}
function og_render_devices_page():never{
    $u=og_require_web_user();$rows=og_ui_scope_rows($u,5000);$groups=[];
    foreach($rows as$r){$label=og_ui_device_label($r);$key=trim((string)($r['device_id']??''));if($key==='')$key=$label;if(!isset($groups[$key]))$groups[$key]=['id'=>$key,'label'=>$label,'objects'=>0,'events'=>[],'owners'=>[],'photos'=>0,'videos'=>0,'last'=>0];$groups[$key]['objects']++;$groups[$key]['events'][(string)($r['user_id']??'legacy').'|'.(string)$r['event_id']]=true;$groups[$key]['owners'][og_ui_owner_label($r)]=true;$mime=(string)($r['mime_type']??'');if(str_starts_with($mime,'image/'))$groups[$key]['photos']++;if(str_starts_with($mime,'video/'))$groups[$key]['videos']++;$groups[$key]['last']=max($groups[$key]['last'],(int)($r['received_at']??$r['created_at']));}
    uasort($groups,static fn(array$a,array$b):int=>$b['last']<=>$a['last']);
    $body='<div class="device-grid">';
    foreach($groups as$g)$body.='<article class="card device-card"><div class="device-title"><span class="device-glyph">▣</span><div><h2>'.og_h($g['label']).'</h2><div class="mono muted">'.og_h($g['id']).'</div></div></div><div class="status-list"><div><span>Incidents</span><b>'.count($g['events']).'</b></div><div><span>Objects</span><b>'.(int)$g['objects'].'</b></div><div><span>Owners</span><b>'.count($g['owners']).'</b></div><div><span>Media</span><b>'.(int)$g['photos'].' photos · '.(int)$g['videos'].' videos</b></div><div><span>Latest activity</span><b>'.og_h(og_ui_time((int)$g['last'])).'</b></div></div><a class="action-link full" href="incidents.php?device='.rawurlencode((string)$g['label']).'">View device incidents</a></article>';
    if(!$groups)$body.='<div class="card empty"><b>No devices found</b><span class="muted">Devices appear after encrypted evidence is uploaded.</span></div>';
    $body.='</div>';
    og_layout('Devices',$body,$u);
}
function og_render_vault_page():never{og_render_dashboard_page();}
