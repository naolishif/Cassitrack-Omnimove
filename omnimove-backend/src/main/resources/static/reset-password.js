const params = new URLSearchParams(window.location.search);
const TOKEN  = window.location.hash.slice(1) || '';

// Show error state immediately if the server said the token is invalid/expired
if (params.get('expired') === 'true' || !TOKEN) {
  document.getElementById('resetForm').style.display = 'none';
  document.getElementById('msgBox').innerHTML =
    "<div class='msg err'>" + t('reset_link_dead') + " " +
    "<a href='omnimove-login.html' style='color:#f87171'>" + t('btn_back_signin_short') + "</a></div>";
}

function showErr(id, msg) {
  const e = document.getElementById(id);
  e.textContent = msg;
  e.className = 'ferr show';
}
function clearErr(id) {
  const e = document.getElementById(id);
  e.textContent = '';
  e.className = 'ferr';
}

async function doReset(e) {
  e.preventDefault();
  const p1 = document.getElementById('p1').value;
  const p2 = document.getElementById('p2').value;
  clearErr('e1'); clearErr('e2');
  let ok = true;
  if (!p1) { showErr('e1', '⚠ ' + t('err_required')); ok = false; }
  else if (!omniPwdValid(p1)) { showErr('e1', '⚠ ' + t('pwd_rule_short')); ok = false; }
  if (!p2) { showErr('e2', '⚠ ' + t('err_required')); ok = false; }
  else if (p1 !== p2) { showErr('e2', '⚠ ' + t('pwd_err_match')); ok = false; }
  if (!ok) return;

  const btn = document.getElementById('btn');
  btn.disabled = true;
  btn.textContent = t('btn_sending');

  try {
    const r = await fetch('/omnimove/api/v1/auth/reset-password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: TOKEN, newPassword: p1, confirmPassword: p2 })
    });
    const d = await r.json();
    const box = document.getElementById('msgBox');
    if (r.ok) {
      box.innerHTML = "<div class='msg ok'>&#x2713; " + t('reset_done') + " " +
                      "<a href='omnimove-login.html' style='color:#22C55E'>" + t('tab_signin') + "</a></div>";
      document.getElementById('resetForm').style.display = 'none';
    } else {
      box.innerHTML = "<div class='msg err'>" + (d.message || t('reset_failed')) + "</div>";
      btn.disabled = false;
      btn.textContent = t('btn_set_pass');
    }
  } catch (ex) {
    console.error(ex);
    document.getElementById('msgBox').innerHTML =
        "<div class='msg err'>" + t('msg_server_short') + "</div>";
    btn.disabled = false;
    btn.textContent = t('btn_set_pass');
  }
}

document.getElementById('p2').addEventListener('input', function () {
  const p1 = document.getElementById('p1').value;
  if (this.value && p1 && this.value !== p1) showErr('e2', '⚠ ' + t('pwd_err_match'));
  else clearErr('e2');
});

// Strength bar and rule list, shared with the sign-up form and the account panel
const _pwdPaint = omniPwdWatch(document.getElementById('p1'),
                               document.getElementById('pwdBar'),
                               document.getElementById('pwdRules'));

// applyTranslations() only reaches elements carrying data-i18n; these are drawn
// by the module, so switching language has to repaint them by hand. Without this
// the rules kept the language the page happened to open in.
window._onLangChange = () => { if (_pwdPaint) _pwdPaint(); };
