/* Bot Editor module: core.js */
// ==================== TAB SWITCHING ====================
document.querySelectorAll('.tab-bar button').forEach(function(btn){
 btn.addEventListener('click',function(){
  document.querySelectorAll('.tab-bar button').forEach(function(b){b.classList.remove('active')});
  btn.classList.add('active');
  document.querySelectorAll('.tab-content').forEach(function(t){t.classList.remove('active')});
  document.getElementById('tab'+btn.dataset.tab.charAt(0).toUpperCase()+btn.dataset.tab.slice(1)).classList.add('active');
  if(btn.dataset.tab==='library'&&!window._libLoaded){loadLibrary();loadMeta();window._libLoaded=true}
 });
});

// ==================== 通用配置 ====================
var IS_LOCAL_HOST = location.hostname==='localhost'
	|| location.hostname==='127.0.0.1'
	|| location.hostname==='::1'
	|| location.hostname==='[::1]';
// All browser APIs are same-origin. Public deployments are served by Vercel
// functions; localhost is served by SongBot's local compatibility endpoints.
var API_BASE = '';
var _trackUrl = API_BASE+'/api/visit';
var _visitDevice=(function(){
	try{
		var key='sb_visit_device',value=localStorage.getItem(key);
		if(!/^[A-Za-z0-9_-]{16,100}$/.test(value||'')){
			var bytes=new Uint8Array(18);crypto.getRandomValues(bytes);
			value=Array.prototype.map.call(bytes,function(b){return b.toString(16).padStart(2,'0')}).join('');
			localStorage.setItem(key,value);
		}
		return value;
	}catch(_){return ''}
})();
fetch(_trackUrl,{method:'POST',headers:_visitDevice?{'X-Visit-Device':_visitDevice}:{}}).catch(function(){});

function toast(msg){var t=document.getElementById('toast');t.textContent=msg;t.classList.add('show');setTimeout(function(){t.classList.remove('show')},2000)}
function pad(n){return n<10?'0'+n:''+n}
function escHtml(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
