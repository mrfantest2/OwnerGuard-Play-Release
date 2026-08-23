#!/usr/bin/env python3
"""Apply OwnerGuard Cloud 1.3.27.1 first-run OpenSSL compatibility hotfix."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLOUD = ROOT / ".deploy" / "ownerguard_cloud"
CORE = CLOUD / "includes" / "core.php"
APP = CLOUD / "app.php"

if not CORE.is_file() or not APP.is_file():
    raise SystemExit("Cloud payload must be reconstructed before applying the OpenSSL hotfix")

core = CORE.read_text(encoding="utf-8")
core = core.replace("const OG_VERSION = '1.3.27';", "const OG_VERSION = '1.3.27.1';")

anchor = """function og_setup(string $username, string $display, string $password, string $escrowPassphrase): array {\n"""
helpers = r'''function og_password_hash(string $password): string {
    $algorithms = function_exists('password_algos') ? password_algos() : [];
    $algorithm = in_array('argon2id', $algorithms, true) && defined('PASSWORD_ARGON2ID') ? PASSWORD_ARGON2ID : PASSWORD_DEFAULT;
    $hash = password_hash($password, $algorithm);
    if (!is_string($hash) || $hash === '') throw new RuntimeException('PHP could not securely hash the account password');
    return $hash;
}
function og_openssl_errors(): array {
    $errors = [];
    while (($error = openssl_error_string()) !== false) {
        $error = trim($error);
        if ($error !== '' && !in_array($error, $errors, true)) $errors[] = $error;
    }
    return $errors;
}
function og_openssl_fallback_config(): string {
    og_ensure_data();
    $path = OG_DATA . '/openssl-ownerguard.cnf';
    $content = <<<'CONF'
[ req ]
default_bits = 3072
default_md = sha256
distinguished_name = req_distinguished_name
prompt = no

[ req_distinguished_name ]
CN = OwnerGuard Cloud Administrator Escrow
CONF;
    $content .= "\n";
    if (!is_file($path) || file_get_contents($path) !== $content) {
        if (file_put_contents($path, $content, LOCK_EX) === false) throw new RuntimeException('Cannot create the local OpenSSL compatibility configuration');
        @chmod($path, 0600);
    }
    return $path;
}
function og_openssl_config_candidates(): array {
    $phpDir = dirname(PHP_BINARY);
    $phpParent = dirname($phpDir);
    $ini = php_ini_loaded_file();
    $iniDir = is_string($ini) && $ini !== '' ? dirname($ini) : '';
    $raw = [
        getenv('OPENSSL_CONF') ?: '', getenv('SSLEAY_CONF') ?: '',
        $phpDir . DIRECTORY_SEPARATOR . 'openssl.cnf',
        $phpDir . DIRECTORY_SEPARATOR . 'extras' . DIRECTORY_SEPARATOR . 'ssl' . DIRECTORY_SEPARATOR . 'openssl.cnf',
        $phpParent . DIRECTORY_SEPARATOR . 'apache' . DIRECTORY_SEPARATOR . 'conf' . DIRECTORY_SEPARATOR . 'openssl.cnf',
        $iniDir !== '' ? $iniDir . DIRECTORY_SEPARATOR . 'openssl.cnf' : '',
        $iniDir !== '' ? $iniDir . DIRECTORY_SEPARATOR . 'extras' . DIRECTORY_SEPARATOR . 'ssl' . DIRECTORY_SEPARATOR . 'openssl.cnf' : '',
        'C:\\xampp\\apache\\conf\\openssl.cnf',
        'C:\\xampp\\php\\extras\\ssl\\openssl.cnf',
        'C:\\web\\apache\\conf\\openssl.cnf',
        'C:\\web\\php\\extras\\ssl\\openssl.cnf',
        '/etc/ssl/openssl.cnf', '/usr/lib/ssl/openssl.cnf',
    ];
    $out = [];
    foreach ($raw as $candidate) {
        if (is_string($candidate) && $candidate !== '' && is_file($candidate)) {
            $real = realpath($candidate) ?: $candidate;
            if (!in_array($real, $out, true)) $out[] = $real;
        }
    }
    return $out;
}
function og_create_escrow_key(): array {
    if (!extension_loaded('openssl') || !function_exists('openssl_pkey_new')) {
        throw new RuntimeException('The PHP OpenSSL extension is not enabled on the Steam Deck host');
    }
    $base = ['private_key_bits' => 3072, 'private_key_type' => OPENSSL_KEYTYPE_RSA, 'digest_alg' => 'sha256'];
    $attempts = [['label' => 'PHP default OpenSSL configuration', 'config' => null]];
    foreach (og_openssl_config_candidates() as $config) $attempts[] = ['label' => $config, 'config' => $config];
    $fallback = og_openssl_fallback_config();
    $attempts[] = ['label' => 'OwnerGuard local compatibility configuration', 'config' => $fallback];
    $failures = [];
    foreach ($attempts as $attempt) {
        og_openssl_errors();
        $options = $base;
        if (is_string($attempt['config'])) $options['config'] = $attempt['config'];
        $key = @openssl_pkey_new($options);
        $errors = og_openssl_errors();
        if ($key !== false) return [$key, $attempt['config']];
        $detail = $errors ? implode(' | ', array_slice($errors, 0, 2)) : 'no OpenSSL diagnostic was returned';
        $failures[] = $attempt['label'] . ': ' . $detail;
    }
    error_log('[OwnerGuard Cloud] OpenSSL escrow key generation failed: ' . implode(' ; ', array_slice($failures, -3)));
    throw new RuntimeException('OpenSSL could not create the Administrator escrow key after compatibility fallback. The server error log contains the OpenSSL diagnostic.');
}
'''
if "function og_create_escrow_key(): array" not in core:
    if anchor not in core:
        raise SystemExit("OwnerGuard Cloud setup function anchor was not found")
    core = core.replace(anchor, helpers + anchor, 1)

old_key = """    og_ensure_data();
    $key = openssl_pkey_new(['private_key_bits' => 3072, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
    if ($key === false) throw new RuntimeException('OpenSSL could not create the Administrator escrow key');
    $private = '';
    if (!openssl_pkey_export($key, $private, $escrowPassphrase, ['cipher' => 'aes-256-cbc'])) throw new RuntimeException('OpenSSL could not encrypt the Administrator escrow key');
    $details = openssl_pkey_get_details($key);
"""
new_key = """    og_ensure_data();
    [$key, $opensslConfig] = og_create_escrow_key();
    $private = '';
    $exportOptions = ['digest_alg' => 'sha256'];
    if (is_string($opensslConfig)) $exportOptions['config'] = $opensslConfig;
    if (defined('OPENSSL_CIPHER_AES_256_CBC')) $exportOptions['encrypt_key_cipher'] = constant('OPENSSL_CIPHER_AES_256_CBC');
    og_openssl_errors();
    if (!@openssl_pkey_export($key, $private, $escrowPassphrase, $exportOptions)) {
        $errors = og_openssl_errors();
        $detail = $errors ? ': ' . implode(' | ', array_slice($errors, 0, 3)) : '';
        error_log('[OwnerGuard Cloud] OpenSSL escrow key export failed' . $detail);
        throw new RuntimeException('OpenSSL could not encrypt the Administrator escrow key. The server error log contains the OpenSSL diagnostic.');
    }
    $details = openssl_pkey_get_details($key);
"""
if old_key in core:
    core = core.replace(old_key, new_key, 1)
elif "[$key, $opensslConfig] = og_create_escrow_key();" not in core:
    raise SystemExit("OwnerGuard Cloud OpenSSL key-generation block was not found")

core = core.replace("password_hash($password, PASSWORD_ARGON2ID)", "og_password_hash($password)")
core = core.replace("mb_substr($detail,0,1000)", "function_exists('mb_substr') ? mb_substr($detail,0,1000) : substr($detail,0,1000)")
CORE.write_text(core, encoding="utf-8")

app = APP.read_text(encoding="utf-8")
app = app.replace("password_hash($password,PASSWORD_ARGON2ID)", "og_password_hash($password)")
APP.write_text(app, encoding="utf-8")

(CLOUD / "VERSION").write_text("OwnerGuard Cloud 1.3.27.1\n", encoding="utf-8")
readme = CLOUD / "README_DEPLOY.txt"
text = readme.read_text(encoding="utf-8").replace(
    "OwnerGuard Cloud 1.3.27 — Steam Deck host",
    "OwnerGuard Cloud 1.3.27.1 — Steam Deck host",
)
notice = "Hotfix 1.3.27.1: self-contained OpenSSL configuration fallback for Windows/Steam Deck PHP hosts, secure password-hash fallback, and actionable setup diagnostics."
if notice not in text:
    text = text.rstrip() + "\n\n" + notice + "\n"
readme.write_text(text, encoding="utf-8")

(CLOUD / "CHANGELOG.txt").write_text(
    "OwnerGuard Cloud 1.3.27.1\n\n"
    "- Fixed first-run Administrator escrow RSA key generation when openssl.cnf is missing or undiscoverable.\n"
    "- Added XAMPP, C:\\web, PHP, Apache, Linux and environment OpenSSL configuration discovery.\n"
    "- Added a local non-secret OpenSSL compatibility configuration fallback under data/.\n"
    "- Added secure password hashing fallback when Argon2id is unavailable.\n"
    "- Logged detailed OpenSSL diagnostics server-side without exposing host paths on the setup page.\n",
    encoding="utf-8",
)

print("Applied OwnerGuard Cloud 1.3.27.1 OpenSSL setup hotfix")
