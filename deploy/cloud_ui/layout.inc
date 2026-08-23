function og_ui_time(int $value):string{
    if($value<=0)return '—';
    if($value>20000000000)$value=(int)floor($value/1000);
    return date('Y-m-d H:i:s',$value);
}
function og_ui_route_active(array $routes):bool{
    global $route;
    return in_array((string)$route,$routes,true);
}
function og_ui_nav_item(string $href,string $label,string $icon,array $routes):string{
    $active=og_ui_route_active($routes);
    return '<a class="drawer-link'.($active?' active':'').'" href="'.og_h($href).'"'.($active?' aria-current="page"':'').' title="'.og_h($label).'"><span class="drawer-icon" aria-hidden="true">'.og_h($icon).'</span><span class="drawer-label">'.og_h($label).'</span></a>';
}
function og_ui_current_href():string{
    $uri=(string)($_SERVER['REQUEST_URI']??'');
    $path=(string)(parse_url($uri,PHP_URL_PATH)??'');
    $file=basename($path);
    $allowed=['index.php','dashboard.php','incidents.php','owners.php','devices.php','incident.php','decrypt.php','users.php','audit.php','system.php','updates.php','healthcheck.php'];
    if(!in_array($file,$allowed,true))$file='index.php';
    $query=(string)(parse_url($uri,PHP_URL_QUERY)??'');
    return $file.($query!==''?'?'.$query:'');
}
function og_ui_header_actions(?array $user):string{
    global $route;
    if(!$user)return '';
    $actions='';
    if(in_array((string)$route,['incidents','audit'],true)){
        $actions.='<button class="header-action" type="button" data-page-search title="Focus page search"><span aria-hidden="true">⌕</span><span class="header-action-label">Search</span></button>';
    }
    if(in_array((string)$route,['incident','decrypt'],true)){
        $actions.='<a class="header-action" href="incidents.php" title="Back to all incidents"><span aria-hidden="true">←</span><span class="header-action-label">Incidents</span></a>';
    }
    if((string)$route==='updates'){
        $manifest=is_file(OG_RELEASE)?json_decode((string)file_get_contents(OG_RELEASE),true):[];
        $apk=(string)($manifest['apkUrl']??'');
        if(str_starts_with($apk,'/ownerguard_cloud/releases/')&&str_ends_with($apk,'.apk')){
            $actions.='<a class="header-action primary" href="'.og_h($apk).'" title="Download published APK"><span aria-hidden="true">⇩</span><span class="header-action-label">Download APK</span></a>';
        }
    }
    $actions.='<a class="header-action" href="'.og_h(og_ui_current_href()).'" title="Refresh this page"><span aria-hidden="true">↻</span><span class="header-action-label">Refresh</span></a>';
    return '<div class="header-actions" aria-label="Page actions">'.$actions.'</div>';
}
function og_ui_drawer(array $user):string{
    $admin=($user['role']??'')==='admin';
    $crypto=og_crypto_capabilities();
    $manifest=is_file(OG_RELEASE)?json_decode((string)file_get_contents(OG_RELEASE),true):[];
    $android=(string)($manifest['versionName']??'Unavailable');
    $name=trim((string)($user['display_name']??''));if($name==='')$name=(string)($user['username']??'User');
    $nav='<div class="drawer-section"><div class="drawer-section-label">Overview</div>'.og_ui_nav_item('dashboard.php','Dashboard','⌂',['index','dashboard']).'</div>';
    $nav.='<div class="drawer-section"><div class="drawer-section-label">Vault</div>'.og_ui_nav_item('incidents.php','All incidents','◆',['incidents','incident','decrypt']);
    if($admin)$nav.=og_ui_nav_item('owners.php','Vault owners','◎',['owners']);
    $nav.=og_ui_nav_item('devices.php','Devices','▣',['devices']).'</div>';
    if($admin){$nav.='<div class="drawer-section"><div class="drawer-section-label">Administration</div>'.og_ui_nav_item('users.php','Users','♙',['users','settings']).og_ui_nav_item('audit.php','Audit log','≡',['audit']).og_ui_nav_item('system.php','System status','◉',['system','healthcheck']).og_ui_nav_item('updates.php','Update management','⇧',['updates']).'</div>';}
    return '<aside class="app-drawer" id="app-drawer" aria-label="OwnerGuard navigation"><div class="drawer-brand"><span class="brand-mark">⬢</span><span class="drawer-brand-copy"><b>OwnerGuard Cloud</b><small>Secure incident workspace</small></span><button class="drawer-close" type="button" data-drawer-close aria-label="Close navigation">×</button></div><nav class="drawer-nav">'.$nav.'</nav><div class="drawer-footer"><div class="drawer-health"><span class="health-row"><i class="status-dot"></i><span>Cloud online</span></span><span class="health-row"><i class="status-dot'.($crypto['ready']?'':' bad-dot').'"></i><span>Cryptography '.($crypto['ready']?'ready':'blocked').'</span></span><span class="health-row"><i class="status-dot"></i><span>Mandatory APK '.og_h($android).'</span></span></div><div class="drawer-account"><div class="account-copy"><b>'.og_h($name).'</b><small>Signed in as '.og_h((string)($user['role']??'user')).'</small></div><a class="drawer-signout" href="logout.php">Sign out</a></div></div></aside><button class="drawer-scrim" type="button" data-drawer-close aria-label="Close navigation"></button>';
}
function og_layout_drawer(string $title,string $body,?array $user=null):never{
    global $route;
    og_security_headers(false);
    $drawer=$user?og_ui_drawer($user):'';
    $bodyClass=$user?'has-drawer':'auth-shell';
    $menu=$user?'<button class="menu-toggle" type="button" data-drawer-toggle aria-controls="app-drawer" aria-expanded="false" aria-label="Open navigation"><span></span><span></span><span></span></button>':'';
    $brand=$user?'':'<div class="public-brand"><span class="brand-mark">⬢</span><span><b>OwnerGuard Cloud</b><small>Secure incident recovery · v'.og_h(OG_VERSION).'</small></span></div>';
    $actions=og_ui_header_actions($user);
    echo '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#081321"><title>'.og_h($title).' · OwnerGuard Cloud</title><link rel="stylesheet" href="assets/ui.css?v=drawer131"><script defer src="assets/ui.js?v=drawer131"></script></head><body class="'.$bodyClass.'" data-route="'.og_h((string)$route).'">'.$drawer.'<div class="app-content"><header class="app-header"><div class="app-header-inner">'.$menu.$brand.'<div class="header-title"><h1>'.og_h($title).'</h1></div>'.$actions.'</div></header><main class="wrap">'.$body.'</main></div></body></html>';exit;
}
function og_ui_event_table(array $events,bool $withAction=true):string{
    if(!$events)return '<div class="empty"><b>No incidents found</b><span class="muted">Encrypted uploads will appear here automatically.</span></div>';
    $h='<div class="table-shell'.($withAction?' scroll':'').'"><table><thead><tr><th>Incident</th><th>Owner</th><th>Device</th><th>Objects</th><th>Captured</th><th>Received</th>'.($withAction?'<th>Action</th>':'').'</tr></thead><tbody>';
    foreach($events as$e){$ownerParam=array_key_exists('owner_id',$e)?($e['owner_id']===null?'legacy':(string)$e['owner_id']):'';$href='incident.php?event='.rawurlencode((string)$e['event']).($ownerParam!==''?'&owner='.rawurlencode($ownerParam):'');$h.='<tr><td><b>'.og_h((string)$e['event']).'</b></td><td>'.og_h((string)$e['username']).'</td><td>'.og_h((string)$e['device']).'</td><td><span class="pill">'.(int)$e['objects'].'</span></td><td>'.og_h(og_ui_time((int)$e['created'])).'</td><td>'.og_h(og_ui_time((int)$e['received'])).'</td>'.($withAction?'<td><a class="action-link" href="'.og_h($href).'">View metadata</a></td>':'').'</tr>';}
    return $h.'</tbody></table></div>';
}
