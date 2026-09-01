/* Bot Editor module: library.js */
// ==================== 曲库查询 ====================
var LIB_DATA=[],_libFilter="ALL",_DS_CFG={shapes:[],card:{}};
var _iosRenderBatch=48,_iosRenderLimit=48,_iosRenderKey="";
var _desktopRenderBatch=120,_desktopRenderLimit=120,_desktopRenderKey="",_libraryMoreObserver=null;
var _librarySongMeta=typeof WeakMap==="function"?new WeakMap():null;
var _libraryRenderFrame=0,_libraryPendingControl=null,_libraryHasRendered=false;
function _isIOSDevice(){return /iPad|iPhone|iPod/.test(navigator.userAgent)||((navigator.platform==='MacIntel')&&navigator.maxTouchPoints>1)}
var _libraryIsIOS=_isIOSDevice();
if(_libraryIsIOS)document.documentElement.classList.add("is-ios");
try{var dc=document.getElementById("dsConfig");if(dc){_DS_CFG=JSON.parse(dc.textContent);document.documentElement.style.setProperty("--card-w",_DS_CFG.card&&_DS_CFG.card.w||700);document.documentElement.style.setProperty("--card-h",_DS_CFG.card&&_DS_CFG.card.h||110)}}catch(e){}
var _dsFilters={font:'方正粗圆简体',fontSize:16,borderRadius:50,capsuleWidth:110,capsuleHeight:42,fontScaleX:1,unselectedBgColor:'#ffffff',unselectedBgOpacity:70,strokeColor:'#1e293b',strokeWidth:0,capsules:{ALL:{gradLeft:'#3b82f6',gradRight:'#60a5fa',selectedColor:'#ffffff',unselectedColor:'#64748b'},'4K':{gradLeft:'#3b82f6',gradRight:'#60a5fa',selectedColor:'#ffffff',unselectedColor:'#64748b'},'5K':{gradLeft:'#3b82f6',gradRight:'#60a5fa',selectedColor:'#ffffff',unselectedColor:'#64748b'},'6K':{gradLeft:'#3b82f6',gradRight:'#60a5fa',selectedColor:'#ffffff',unselectedColor:'#64748b'},Catch:{gradLeft:'#3b82f6',gradRight:'#60a5fa',selectedColor:'#ffffff',unselectedColor:'#64748b'}}};
(function(){try{if(_DS_CFG.filters){var f=_DS_CFG.filters;for(var k in f){if(k!=='capsules'&&k!=='selectedColor'&&k!=='unselectedColor'&&k!=='unselectedBg')_dsFilters[k]=f[k]}if(typeof f.unselectedBg==='string'){var m=f.unselectedBg.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);if(m){_dsFilters.unselectedBgColor='#'+(+m[1]).toString(16).padStart(2,'0')+(+m[2]).toString(16).padStart(2,'0')+(+m[3]).toString(16).padStart(2,'0');if(m[4]!==undefined)_dsFilters.unselectedBgOpacity=Math.round(+m[4]*100)}}if(f.capsules)for(var k in f.capsules){var oc=f.capsules[k];var nc=_dsFilters.capsules[k]=Object.assign({},_dsFilters.capsules[k],oc);if(!nc.selectedColor&&f.selectedColor)nc.selectedColor=f.selectedColor;if(!nc.unselectedColor&&f.unselectedColor)nc.unselectedColor=f.unselectedColor}}}catch(e){}})();
function _hexToRgb(h){h=h.replace('#','');if(h.length===3)h=h.split('').map(function(c){return c+c}).join('');var n=parseInt(h,16);return{r:(n>>16)&255,g:(n>>8)&255,b:n&255}}
function _unselectedBgCss(){var c=_hexToRgb(_dsFilters.unselectedBgColor||'#ffffff');return'rgba('+c.r+','+c.g+','+c.b+','+((_dsFilters.unselectedBgOpacity||0)/100)+')'}
function applyFiltersCSS(){var f=_dsFilters;var s=document.getElementById('filterCapsuleStyles')||document.createElement('style');s.id='filterCapsuleStyles';var ff=f.font==='默认'?"'Microsoft YaHei',sans-serif":"'"+f.font+"','Microsoft YaHei',sans-serif";var stroke=f.strokeWidth>0?';-webkit-text-stroke:'+f.strokeWidth+'px '+f.strokeColor+';paint-order:stroke fill':' ';var css='#libFilters .tpl-capsule{font-family:'+ff+';font-size:'+f.fontSize+'px;border-radius:'+f.borderRadius+'px;background:'+_unselectedBgCss()+';width:'+f.capsuleWidth+'px;height:'+f.capsuleHeight+'px;overflow:hidden;box-sizing:border-box;border:none;font-weight:600;letter-spacing:.5px;box-shadow:inset 0 0 0 1px rgba(203,213,225,0.6),0 1px 3px rgba(0,0,0,0.04);display:inline-flex;align-items:center;justify-content:center;cursor:pointer}';css+='#libFilters .tpl-capsule .ct{display:inline-block;transform:scaleX('+(f.fontScaleX||1)+')}';css+='#libFilters .tpl-capsule:hover{background:#fff;transform:translateY(-1px);box-shadow:inset 0 0 0 1px rgba(203,213,225,0.6),0 4px 12px rgba(0,0,0,0.1)}';['ALL','4K','5K','6K','Catch'].forEach(function(k){var c=f.capsules[k];if(!c)return;css+='#libFilters .tpl-capsule[data-flt="'+k+'"]{color:'+(c.unselectedColor||'#64748b')+'}';css+='#libFilters .tpl-capsule[data-flt="'+k+'"].active{color:'+(c.selectedColor||'#fff')+'!important;background:linear-gradient(90deg,'+c.gradLeft+','+c.gradRight+')!important;box-shadow:0 4px 14px '+c.gradLeft+'66,inset 0 1px 0 rgba(255,255,255,0.3);transform:translateY(-1px)'+stroke+'}'});css+='#libFilters .tpl-capsule.active:hover{filter:brightness(1.1)}';s.textContent=css;if(!s.parentNode)document.head.appendChild(s)}
applyFiltersCSS();
// 保持 v3：迁移云端时继续读取用户现有曲库缓存，再静默后台刷新新地址。
var _cacheVersion="v3-content-addressed";
var _LIBRARY_GITHUB_DATA="https://raw.githubusercontent.com/Liccha/song-library/master/data/songs.json";
var _LIBRARY_PRIMARY_DATA=String(window.SONG_LIBRARY_PRIMARY_DATA_URL||"").trim();
var _LIBRARY_RELEASE_POINTER=String(window.SONG_LIBRARY_RELEASE_POINTER_URL||"").trim();

// ---- 持久化资源缓存：限制磁盘条目和内存 Blob 数，避免 iOS 长时间浏览后内存失控 ----
var _assetCache=null,_assetCachePruned=false,_blobUrls={},_blobOrder=[],_assetPending={};
var _ASSET_CACHE_NAME='songbot-assets-v3',_MAX_CACHE_ENTRIES=256,_MAX_BLOB_URLS=96;
function openAssetCache(){
 if(_assetCache)return Promise.resolve(_assetCache);
 if(!('caches' in window))return Promise.reject('no-caches');
 return caches.open(_ASSET_CACHE_NAME).then(function(c){
  _assetCache=c;
  caches.delete('songbot-assets-v1').catch(function(){});
  caches.delete('songbot-assets-v2').catch(function(){});
  if(!_assetCachePruned){
   _assetCachePruned=true;
   c.keys().then(function(keys){
    var excess=keys.length-_MAX_CACHE_ENTRIES;
    if(excess>0)keys.slice(0,excess).forEach(function(key){c.delete(key).catch(function(){})});
   }).catch(function(){});
  }
  return c;
 });
}
function _rememberBlobUrl(url,blobUrl){
 _blobUrls[url]=blobUrl;_blobOrder.push(url);
 if(_blobOrder.length<=_MAX_BLOB_URLS)return;
 var checks=_blobOrder.length;
 while(_blobOrder.length>_MAX_BLOB_URLS&&checks-->0){
  var oldUrl=_blobOrder.shift(),oldBlob=_blobUrls[oldUrl];
  if(!oldBlob)continue;
  var inUse=Array.prototype.some.call(document.querySelectorAll('[src]'),function(el){
   return el.getAttribute('src')===oldBlob||el.src===oldBlob;
  });
  if(inUse){_blobOrder.push(oldUrl);continue}
  try{URL.revokeObjectURL(oldBlob)}catch(e){}
  delete _blobUrls[oldUrl];
 }
}
function _resolveAsset(url){
 if(_blobUrls[url])return Promise.resolve(_blobUrls[url]);
 if(_assetPending[url])return _assetPending[url];
 var task=openAssetCache().then(function(cache){
  return cache.match(url).then(function(hit){
   return hit||fetch(url,{referrerPolicy:'strict-origin-when-cross-origin'}).then(function(r){
    if(!r.ok&&r.type!=='opaque')throw new Error('HTTP '+r.status);
    cache.put(url,r.clone()).catch(function(){});
    return r;
   });
  }).then(function(r){
   if(!r||(!r.ok&&r.type!=='opaque'))throw new Error('invalid asset response');
   return r.blob();
  }).then(function(b){
   var bu=URL.createObjectURL(b);_rememberBlobUrl(url,bu);return bu;
  });
 });
 _assetPending[url]=task;
 task.then(function(){delete _assetPending[url]},function(){delete _assetPending[url]});
 return task;
}
function loadAsset(url,onDone,fallbackUrl){
 if(!url){onDone('');return}
 _resolveAsset(url).then(onDone,function(){
  if(fallbackUrl&&fallbackUrl!==url){
   _resolveAsset(fallbackUrl).then(onDone,function(){onDone(fallbackUrl)});
  }else onDone(url);
 });
}

async function _fetchJsonResponse(url,timeoutMs,cacheMode){
 var controller=typeof AbortController==='function'?new AbortController():null;
 var timer=controller?setTimeout(function(){controller.abort()},timeoutMs||6000):0;
 try{
  var options={cache:cacheMode||'no-cache',referrerPolicy:'strict-origin-when-cross-origin'};
  if(controller)options.signal=controller.signal;
  var response=await fetch(url,options);
  if(!response.ok)throw new Error('HTTP '+response.status);
  return response.json();
 }finally{if(timer)clearTimeout(timer)}
}

async function _fetchLibraryJson(url,timeoutMs,cacheMode){
 var data=await _fetchJsonResponse(url,timeoutMs,cacheMode);
 if(!Array.isArray(data)||!data.length)throw new Error('歌曲数据格式无效');
 return data;
}

async function _resolvePrimaryLibraryUrl(){
 if(!_LIBRARY_RELEASE_POINTER)return _LIBRARY_PRIMARY_DATA;
 var state=await _fetchJsonResponse(_LIBRARY_RELEASE_POINTER,1800,'no-cache');
 var release=String(state&&state.release||'').trim();
 if(!/^data\/releases\/songs-[a-f0-9]{16}\.json$/.test(release))throw new Error('曲库版本指针无效');
 return new URL('/'+release,_LIBRARY_RELEASE_POINTER).toString();
}

async function _fetchCurrentLibrary(){
 if(_LIBRARY_RELEASE_POINTER||_LIBRARY_PRIMARY_DATA){
  try{
   var primary=await _resolvePrimaryLibraryUrl();
   return await _fetchLibraryJson(primary,6000,_LIBRARY_RELEASE_POINTER? 'force-cache':'no-cache');
  }catch(e){
   if(_LIBRARY_PRIMARY_DATA){
    try{return await _fetchLibraryJson(_LIBRARY_PRIMARY_DATA,6000,'no-cache')}
    catch(primaryError){if(window.console&&console.warn)console.warn('[曲库] 国内主索引不可用，切换 GitHub 备份',primaryError&&primaryError.message||primaryError)}
   }else if(window.console&&console.warn)console.warn('[曲库] 云端不可用，切换 GitHub 备份',e&&e.message||e);
  }
 }
 return _fetchLibraryJson(_LIBRARY_GITHUB_DATA,12000);
}

async function loadLibrary(){
 document.getElementById("libStats").textContent="Loading...";
 var fromCache=false;
 try{
  var c2=localStorage.getItem("songlib_data");
  if(c2){try{var d=JSON.parse(c2);if(d.version===_cacheVersion&&d.data&&d.data.length){LIB_DATA=d.data;fromCache=true;document.getElementById("libStats").textContent=LIB_DATA.length+" songs (cached)";renderLibrary()}}catch(e){}}
   var json=await _fetchCurrentLibrary();
   if(json){
    json.sort(function(a,b){return b.id-a.id});
    LIB_DATA=json;
    try{localStorage.setItem("songlib_data",JSON.stringify({version:_cacheVersion,data:json,time:Date.now()}))}catch(e){}
    document.getElementById("libStats").textContent=json.length+" songs";
    renderLibrary(document.getElementById("libSearch").value);
   }else if(!fromCache)throw new Error("歌曲数据为空");
 }catch(e){if(!fromCache)document.getElementById("libStats").textContent="Failed: "+e.message}
}

// ===== 点赞系统（卡片 + 小窗共用，按歌曲 id 计数）=====
var _LIKES={},_LIKED=new Set(),_metaLoaded=false,_likeMutationVersion=0;
// 用 SVG 心形，避免 ♥ 字形在手机端被 emoji/字体替换导致变扁变窄
function _getLikeDevice(){
 try{
  var saved=localStorage.getItem('_sb_like_device');
  if(saved&&/^[A-Za-z0-9_-]{16,100}$/.test(saved))return saved;
  var fresh=(window.crypto&&typeof window.crypto.randomUUID==='function')?window.crypto.randomUUID():('device-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)+Math.random().toString(36).slice(2));
  localStorage.setItem('_sb_like_device',fresh);return fresh;
 }catch(e){return 'memory-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)+Math.random().toString(36).slice(2)}
}
var _LIKE_DEVICE=_getLikeDevice();
function _likeHeaders(){return{'Content-Type':'application/json','X-Like-Device':_LIKE_DEVICE}}
function _saveLikes(){try{localStorage.setItem('_sb_likes',JSON.stringify(_LIKES));localStorage.setItem('_sb_liked',JSON.stringify(Array.from(_LIKED)))}catch(e){}}
	var _likeReplayTimer=0,_likeReplayActive=false;
	function _enqueue(a,id){try{var q=JSON.parse(localStorage.getItem('_sb_likeQ')||'[]');q.push({a:a,id:id,ts:Date.now()});if(q.length>200)q=q.slice(-200);localStorage.setItem('_sb_likeQ',JSON.stringify(q))}catch(e){}}
	function _scheduleLikeReplay(){if(_likeReplayTimer)return;_likeReplayTimer=setTimeout(function(){_likeReplayTimer=0;_replayQueue()},30000)}
	function _replayQueue(){
	 if(_likeReplayActive||!navigator.onLine)return;
	 var q;try{q=JSON.parse(localStorage.getItem('_sb_likeQ')||'[]')}catch(e){return}
	 if(!q||!q.length)return;
	 var item=q[0];if(!item)return;
	 _likeReplayActive=true;
	 var m=item.a==='like'?'POST':'DELETE';
	 fetch(API_BASE+'/api/like',{method:m,headers:_likeHeaders(),body:JSON.stringify({id:item.id})})
	  .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()}).then(function(d){
	   if(d&&Number.isFinite(Number(d.count))){_LIKES[item.id]=Number(d.count);if(d.liked)_LIKED.add(item.id);else _LIKED.delete(item.id);updateHeartUI(item.id);_saveLikes()}
	   _likeReplayActive=false;
	   try{var q2=JSON.parse(localStorage.getItem('_sb_likeQ')||'[]');q2.shift();localStorage.setItem('_sb_likeQ',JSON.stringify(q2));_replayQueue()}catch(e){}
	  }).catch(function(){_likeReplayActive=false;_scheduleLikeReplay()});
	}
	var _HEART_SVG='<svg viewBox="0 0 32 29.6" aria-hidden="true"><path d="M23.6,0c-3.4,0-6.3,2.7-7.6,5.1C14.7,2.7,11.8,0,8.4,0C3.8,0,0,3.8,0,8.4c0,9.4,9.5,11.9,16,21.2c6.1-9.3,16-12.1,16-21.2C32,3.8,28.2,0,23.6,0z"/></svg>';
function loadMeta(){
 try{var d=JSON.parse(localStorage.getItem('_sb_likes')||'{}');_LIKES=d||{};var a=JSON.parse(localStorage.getItem('_sb_liked')||'[]');_LIKED=new Set(a||[])}catch(e){}
 _metaLoaded=true;updateAllHearts();
	var requestVersion=_likeMutationVersion;
 fetch(API_BASE+'/api/meta',{headers:{'X-Like-Device':_LIKE_DEVICE}}).then(function(r){return r.json()}).then(function(d){
	if(requestVersion!==_likeMutationVersion)return;
  _LIKES=d.likes||{};_LIKED=new Set(d.likedToday||[]);_metaLoaded=true;
  updateAllHearts();_saveLikes();_replayQueue();
 }).catch(function(){});
}
function updateAllHearts(){
 document.querySelectorAll('.heart[data-hid],.ld-heart[data-hid]').forEach(function(el){
  var id=parseInt(el.dataset.hid);if(isNaN(id))return;
  var n=el.querySelector('.heart-n');if(n)n.textContent=_LIKES[id]||0;
  el.classList.toggle('liked',_LIKED.has(id));
 });
}
function updateHeartUI(id){
 document.querySelectorAll('.heart[data-hid="'+id+'"],.ld-heart[data-hid="'+id+'"]').forEach(function(el){
  var n=el.querySelector('.heart-n');if(n)n.textContent=_LIKES[id]||0;
  el.classList.toggle('liked',_LIKED.has(id));
 });
}
function doLike(id){
 if(_LIKED.has(id))return;
	_likeMutationVersion++;
 _LIKED.add(id);_LIKES[id]=(_LIKES[id]||0)+1;updateHeartUI(id);_saveLikes();
 fetch(API_BASE+'/api/like',{method:'POST',headers:_likeHeaders(),body:JSON.stringify({id:id})})
  .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()}).then(function(d){_LIKES[id]=Number(d.count)||0;if(d.liked)_LIKED.add(id);else _LIKED.delete(id);_saveLikes();updateHeartUI(id)}).catch(function(){_enqueue('like',id);_scheduleLikeReplay()});
}
function doUnlike(id){
 if(!_LIKED.has(id))return;
	_likeMutationVersion++;
 _LIKED.delete(id);_LIKES[id]=Math.max(0,(_LIKES[id]||1)-1);updateHeartUI(id);_saveLikes();
 fetch(API_BASE+'/api/like',{method:'DELETE',headers:_likeHeaders(),body:JSON.stringify({id:id})})
  .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()}).then(function(d){_LIKES[id]=Number(d.count)||0;if(d.liked)_LIKED.add(id);else _LIKED.delete(id);_saveLikes();updateHeartUI(id)}).catch(function(){_enqueue('unlike',id);_scheduleLikeReplay()});
}
// 切换：点一下 +1，再点一下 -1（无需检测双击）
function heartToggle(id){ if(_LIKED.has(id)) doUnlike(id); else doLike(id); }
window.heartToggle=heartToggle;
window.addEventListener('online',_replayQueue);

var _libraryRenderGeneration=0,_librarySearchTimer=0,_libraryMarqueeTimer=0,_libraryCoverObserver=null;
var _libraryCoverGrid=null,_libraryCoverGeneration=0,_libraryCoverFallbackFrame=0;
function _loadCardCover(img,generation){
 if(!img||!img.isConnected||img.dataset.coverLoading==='1')return;
 var url=img.dataset.cover||'';if(!url)return;
 // 主列表封面使用浏览器原生图片加载：滚到可视区域就直接请求原地址。
 // 不经过 Cache API / Blob URL 的人工数量上限，避免长列表在 iOS 中被缓存队列卡住。
 if(generation!==_libraryRenderGeneration)return;
 img.dataset.coverLoading='1';
 img.onload=function(){
  if(generation!==_libraryRenderGeneration||!img.isConnected)return;
  img.dataset.coverLoaded='1';
  delete img.dataset.coverLoading;
  img.onload=null;img.onerror=null;
 };
 img.onerror=function(){
  var fallback=img.dataset.coverFallback||'';
  if(fallback&&img.dataset.coverTriedFallback!=='1'){
   img.dataset.coverTriedFallback='1';
   img.src=fallback;
   return;
  }
  // 两个来源都失败时保留地址，网络恢复或重新渲染后仍可正常重试。
  delete img.dataset.coverLoading;
  img.onerror=null;
 };
 img.src=url;
}
function _loadCoversNearViewport(){
 var grid=_libraryCoverGrid,generation=_libraryCoverGeneration;
 if(!grid||generation!==_libraryRenderGeneration)return;
 var margin=520,viewport=window.innerHeight||document.documentElement.clientHeight;
 grid.querySelectorAll('.lib-card .cv[data-cover]').forEach(function(img){
  if(img.getAttribute('src')||img.dataset.coverLoading==='1')return;
  var rect=img.getBoundingClientRect();
  if(rect.bottom>=-margin&&rect.top<=viewport+margin)_loadCardCover(img,generation);
 });
}
function _queueCoverFallback(){
 if(_libraryCoverFallbackFrame)return;
 var run=function(){_libraryCoverFallbackFrame=0;_loadCoversNearViewport()};
 if(window.requestAnimationFrame)_libraryCoverFallbackFrame=window.requestAnimationFrame(run);
 else _libraryCoverFallbackFrame=setTimeout(run,16);
}
function _observeCardCovers(grid,generation){
 if(_libraryCoverObserver){_libraryCoverObserver.disconnect();_libraryCoverObserver=null}
 _libraryCoverGrid=grid;_libraryCoverGeneration=generation;
 var covers=grid.querySelectorAll('.lib-card .cv[data-cover]');
 if(!covers.length)return;
 if('IntersectionObserver' in window){
  var observer=new IntersectionObserver(function(entries){
   entries.forEach(function(entry){if(entry.isIntersecting){observer.unobserve(entry.target);_loadCardCover(entry.target,generation)}});
  },{rootMargin:'480px 0px'});
  _libraryCoverObserver=observer;
  covers.forEach(function(img){observer.observe(img)});
 }else{
  // 旧浏览器没有观察器时仍保证封面可用，只是不做延迟加载。
  covers.forEach(function(img){_loadCardCover(img,generation)});
 }
 // iOS 快速甩动或加载更多时，观察器回调可能晚于可视区域变化；滚动兜底会补齐漏网封面。
 _queueCoverFallback();
}
window.addEventListener('scroll',_queueCoverFallback,{passive:true});
window.addEventListener('resize',_queueCoverFallback);
function _songLibraryMeta(song){
 var cached=_librarySongMeta&&_librarySongMeta.get(song);
 if(cached)return cached;
 var modes={"4K":false,"5K":false,"6K":false,Catch:false};
 (song.charts||[]).forEach(function(chart){
  var hasNormal=false,hasCatch=false;
  Object.keys(chart).forEach(function(key){
   var difficulty=chart[key];
   if(key==="mode"||!difficulty)return;
   if(difficulty.isMode3)hasCatch=true;
   else if(difficulty.level>0||difficulty.combo>0)hasNormal=true;
  });
  if(hasCatch)modes.Catch=true;
  if(hasNormal&&(chart.mode==="4K"||chart.mode==="5K"||chart.mode==="6K"))modes[chart.mode]=true;
 });
 var meta={
  modes:modes,
  search:(String(song.name||"")+" "+String(song.artist||"")+" "+(song.charters||[]).join(" ")).toLowerCase()
 };
 if(_librarySongMeta)_librarySongMeta.set(song,meta);
 return meta;
}
function _clearFilterBusy(control){
 if(!control)return;
 control.removeAttribute("aria-busy");
 control.setAttribute("aria-disabled","false");
}
function _scheduleLibraryRender(filter,reveal,control){
 if(_libraryRenderFrame){
  cancelAnimationFrame(_libraryRenderFrame);
  _libraryRenderFrame=0;
 }
 if(_libraryPendingControl&&_libraryPendingControl!==control)_clearFilterBusy(_libraryPendingControl);
 _libraryPendingControl=control||null;
 if(control){
  control.setAttribute("aria-busy","true");
  control.setAttribute("aria-disabled","true");
 }
 _libraryRenderFrame=requestAnimationFrame(function(){
  _libraryRenderFrame=requestAnimationFrame(function(){
   _libraryRenderFrame=0;
   try{
    renderLibrary(filter,{reveal:!!reveal});
   }catch(error){
    if(control){
     control.classList.add("is-error");
     setTimeout(function(){control.classList.remove("is-error")},600);
    }
    if(typeof toast==="function")toast("切换失败，请重试");
    if(window.console&&console.error)console.error(error);
   }finally{
    _clearFilterBusy(control);
    if(_libraryPendingControl===control)_libraryPendingControl=null;
   }
  });
 });
}
function _clipCardTextToHeart(grid){
 var bounds=[];
 grid.querySelectorAll(".lib-card").forEach(function(card){
  var heart=card.querySelector(".heart");
  var zoneLeft=(heart&&heart.offsetParent)?(heart.offsetLeft-6):card.clientWidth;
  card.querySelectorAll(".na, .ar").forEach(function(el){
   bounds.push({el:el,width:Math.max(10,zoneLeft-el.offsetLeft)});
  });
 });
 bounds.forEach(function(item){
  item.el.style.width=item.width+"px";
 });
}
function renderLibrary(filter,options){
 if(!LIB_DATA.length)return;
 var generation=++_libraryRenderGeneration;
 var today=new Date();today=today.getFullYear()+'-'+pad(today.getMonth()+1)+'-'+pad(today.getDate());
 var q=(filter||"").toLowerCase(),html="",count=0,hidden=0,rendered=0;
 var revealCards=!_libraryHasRendered||!!(options&&options.reveal);
 var iosBatch=_libraryIsIOS,renderKey=_libFilter+"\n"+q;
 if(iosBatch&&_iosRenderKey!==renderKey){_iosRenderKey=renderKey;_iosRenderLimit=_iosRenderBatch}
 if(!iosBatch&&_desktopRenderKey!==renderKey){_desktopRenderKey=renderKey;_desktopRenderLimit=_desktopRenderBatch}
 var renderLimit=iosBatch?_iosRenderLimit:_desktopRenderLimit;
 for(var i=0;i<LIB_DATA.length;i++){
 var s=LIB_DATA[i];
  if(!s.name||s.name.trim()===""){hidden++;continue}
  if(s.albumDate&&s.albumDate>today){hidden++;continue}
  var songMeta=_songLibraryMeta(s);
  if(_libFilter!=="ALL"&&!songMeta.modes[_libFilter]){hidden++;continue}
  if(q&&songMeta.search.indexOf(q)<0)continue;
  count++;
  if(rendered>=renderLimit)continue;
  var cov=s.cover||"",covFallback=s.coverFallback||"";
  var tags="";
  if(_libFilter==="ALL"){
   var cfg=_DS_CFG.tags||{};
   var has4K=songMeta.modes["4K"],has5K=songMeta.modes["5K"],has6K=songMeta.modes["6K"],hasCatch=songMeta.modes.Catch;
   [{v:has4K,label:"4键",bg:cfg.bg4k||"#dbeafe",cl:cfg.color4k||"#1e40af"},
    {v:has5K,label:"5键",bg:cfg.bg5k||"#dbeafe",cl:cfg.color5k||"#1e40af"},
    {v:has6K,label:"6键",bg:cfg.bg6k||"#dbeafe",cl:cfg.color6k||"#1e40af"},
    {v:hasCatch,label:"Catch",bg:cfg.bgCatch||"#fce7f3",cl:cfg.colorCatch||"#9d174d"}
   ].forEach(function(tp){if(tp.v)tags+="<span style=\"background:"+tp.bg+";color:"+tp.cl+"\">"+tp.label+"</span>"});
  }
  var enterClass=revealCards&&rendered<20?" lib-card-enter":"";
  var enterStyle=revealCards&&rendered<20?' style="--lib-enter-order:'+rendered+'"':"";
  html+="<div class=\"lib-card"+enterClass+"\" data-idx="+i+enterStyle+">"+'<div class="shape-layer">'+(_DS_CFG.shapes||[]).map(function(sh,j){return "<div class=\"sh"+j+"\"></div>"}).join("")+'</div>'+"<img class=\"cv\""+(cov?" data-cover=\""+escHtml(cov)+"\"":"")+(covFallback?" data-cover-fallback=\""+escHtml(covFallback)+"\"":"")+" width=\"80\" height=\"80\" decoding=\"async\">"+'<div class="card-clip">'+"<div class=\"na\">"+(_DS_CFG.name&&_DS_CFG.name.prefix||"")+escHtml(s.name)+(_DS_CFG.name&&_DS_CFG.name.suffix||"")+"</div>"+"<div class=\"ar\">"+(_DS_CFG.artist&&_DS_CFG.artist.prefix||"")+escHtml(s.artist||"")+(_DS_CFG.artist&&_DS_CFG.artist.suffix||"")+"</div>"+"<div class=\"bp\">"+(_DS_CFG.bpm&&_DS_CFG.bpm.prefix||"BPM:")+(s.bpm||"?")+(_DS_CFG.bpm&&_DS_CFG.bpm.suffix||"")+"</div>"+"<div class=\"du\">"+(_DS_CFG.duration&&_DS_CFG.duration.prefix||"")+s.duration+(_DS_CFG.duration&&_DS_CFG.duration.suffix||"")+"</div>"+"<div class=\"ch\">"+(_DS_CFG.charters&&_DS_CFG.charters.prefix||"")+escHtml((s.charters||[]).join(", "))+(_DS_CFG.charters&&_DS_CFG.charters.suffix||"")+"</div>"+"<div class=\"ta\">"+tags+"</div></div>"+'<div class="heart'+(_LIKED.has(s.id)?' liked':'')+'" data-hid="'+s.id+'">'+_HEART_SVG+'<span class="heart-n">'+(_LIKES[s.id]||0)+'</span></div>'+"</div>";
  rendered++;
 }
 var grid=document.getElementById("libGrid");grid.onclick=function(e){var h=e.target.closest(".heart");if(h){e.stopPropagation();var hid=parseInt(h.dataset.hid);if(!isNaN(hid))heartToggle(hid);return}var card=e.target.closest(".lib-card");if(card){var idx=parseInt(card.dataset.idx);if(!isNaN(idx)){playSong(idx,card);showSongDetail(idx)}}};
 grid.innerHTML=html||"<div style=\"text-align:center;color:#94a3b8;padding:40px\">No results</div>";
 _clipCardTextToHeart(grid);
 _libraryHasRendered=true;
 grid.querySelectorAll(".lib-card-enter").forEach(function(card){
  var cleanup=function(){
   card.classList.remove("lib-card-enter");
   card.style.removeProperty("--lib-enter-order");
  };
  card.addEventListener("animationend",cleanup,{once:true});
  setTimeout(cleanup,2200);
 });
 if(_libraryMoreObserver){_libraryMoreObserver.disconnect();_libraryMoreObserver=null}
 if(count>rendered){
  var more=document.createElement('button');more.type='button';more.textContent='加载更多（'+rendered+'/'+count+'）';more.style.cssText='grid-column:1/-1;margin:12px auto 24px;padding:10px 22px;border:1px solid #cbd5e1;border-radius:20px;background:#fff;color:#475569;font:inherit;cursor:pointer';
  more.addEventListener('click',function(){if(iosBatch)_iosRenderLimit+=_iosRenderBatch;else _desktopRenderLimit+=_desktopRenderBatch;renderLibrary(filter)});
  grid.appendChild(more);
  if(!iosBatch&&'IntersectionObserver' in window){
   _libraryMoreObserver=new IntersectionObserver(function(entries){if(entries.some(function(entry){return entry.isIntersecting})){_libraryMoreObserver.disconnect();more.click()}},{rootMargin:'500px 0px'});
   _libraryMoreObserver.observe(more);
  }
 }
 updateAllHearts();
 // 跑马灯：曲名/作者裁剪到「爱心占位左边界」，文字触及该边界即滚动（用 layout px，兼容手机 scale）
 clearTimeout(_libraryMarqueeTimer);
 _libraryMarqueeTimer=setTimeout(function(){
	 if(generation!==_libraryRenderGeneration)return;
	 var isMobile=window.innerWidth<=768;
	 var speed=isMobile?40:80;
	 grid.querySelectorAll(".lib-card").forEach(function(card){
	  var heart=card.querySelector('.heart');
	  // 爱心占位左边界(相对卡片，layout px)；无爱心则退回卡片内容右边界
	  var zoneLeft=(heart&&heart.offsetParent)?(heart.offsetLeft-6):card.clientWidth;
	  card.querySelectorAll('.na, .ar').forEach(function(el){
	   el.style.animation="none";el.style.whiteSpace="nowrap";el.style.overflow="hidden";el.style.textOverflow="clip";
	   var maxW=Math.max(10,zoneLeft-el.offsetLeft); // 该行到爱心左侧的可用宽度
	   var txt=(el.querySelector('span')?el.querySelector('span').textContent:el.textContent);
	   // 单份测量文字自然宽度
	   el.style.width="auto";
	   el.innerHTML='<span style="display:inline-block;white-space:nowrap">'+escHtml(txt)+'</span>';
	   var natural=el.firstChild.offsetWidth;
	   el.style.width=maxW+"px"; // 固定裁剪框：右界=爱心占位左侧，遮挡进入爱心区的文字
	   if(natural>maxW+2){ // 文字末端触及爱心左界才滚动
	    var gap=Math.round(maxW*0.3);
	    el.innerHTML='<div class="mq" style="display:inline-block;white-space:nowrap;will-change:transform">'
	     +'<span style="display:inline-block;white-space:nowrap">'+escHtml(txt)+'</span>'
	     +'<span style="display:inline-block;width:'+gap+'px"></span>'
	     +'<span style="display:inline-block;white-space:nowrap">'+escHtml(txt)+'</span></div>';
	    var mq=el.firstChild,span1W=mq.querySelector('span').offsetWidth;
	    var offset=Math.ceil(span1W+gap);
	    mq.style.setProperty("--marquee-step",(-offset)+"px");
	    mq.style.animation="scrollX "+Math.max(3,(span1W+gap)/speed)+"s linear infinite";
	   }
	  });
	 });
	},400);
 // 封面只在进入当前视口附近时加载；加载更多后的新卡片也会重新建立观察。
 _observeCardCovers(grid,generation);
 var total=LIB_DATA.length-hidden;
 document.getElementById("libStats").textContent=(filter?"Found "+count+"/"+total:total+" songs");
}


var _libraryFilters=document.getElementById("libFilters");
_libraryFilters.addEventListener("click",function(e){
 var el=e.target.closest('[data-flt]');
 if(!el||el.getAttribute("aria-disabled")==="true"||_libFilter===el.dataset.flt)return;
 _libFilter=el.dataset.flt;
 _libraryFilters.querySelectorAll(".tpl-capsule").forEach(function(control){
  var selected=control===el;
  control.classList.toggle("active",selected);
  control.setAttribute("aria-pressed",selected?"true":"false");
 });
 var q=document.getElementById("libSearch").value;
 clearTimeout(_librarySearchTimer);
 _scheduleLibraryRender(q,true,el);
});
_libraryFilters.addEventListener("keydown",function(e){
 var el=e.target.closest('[data-flt]');
 if(!el||(e.key!=="Enter"&&e.key!==" "))return;
 e.preventDefault();
 el.click();
});
document.getElementById("libSearch").addEventListener("input",function(){var q=this.value;clearTimeout(_librarySearchTimer);_librarySearchTimer=setTimeout(function(){renderLibrary(q)},120)});
document.getElementById("libTip").addEventListener("click",function(){var t=document.getElementById('toast');t.textContent='歌曲信息无法展示时，请使用科学上网';t.classList.add('show');setTimeout(function(){t.classList.remove('show')},3500)});

// ===== 管理员分离：设备指纹存服务端名单，暗号在搜索框回车激活（暗号只在后端校验，不出现在前端）=====
var _isAdmin=false;
function _deviceId(){try{var k='_sbDev',v=localStorage.getItem(k);if(!v){v=(window.crypto&&crypto.randomUUID)?crypto.randomUUID():(Date.now()+''+Math.random().toString(16).slice(2));localStorage.setItem(k,v)}return v}catch(e){return 'nodev'}}
function adminHeaders(extra){var h=extra||{};h['X-Admin-Device']=_deviceId();return h}
function _applyAdminUI(){var b=document.querySelector('[data-tab="announce"]'),w=document.getElementById('weOpenBtn');if(b)b.style.display=_isAdmin?'':'none';if(w)w.style.display=_isAdmin?'inline-block':'none'}
function _legacyAdminCheck(){return fetch(API_BASE+'/api/admin/check?d='+encodeURIComponent(_deviceId())).then(function(r){return r.json()})}
function _checkAdmin(){fetch(API_BASE+'/api/announcement-cloud?action=admin-check',{headers:adminHeaders()}).then(function(r){if(!r.ok)throw new Error('cloud unavailable');return r.json()}).then(function(d){if(d&&d.admin)return d;if(window.ANNOUNCEMENT_CLOUD_REQUIRED)return d;return _legacyAdminCheck()}).then(function(d){_isAdmin=!!(d&&d.admin);_applyAdminUI();if(_isAdmin)window.dispatchEvent(new Event('announcement-admin-ready'))}).catch(function(){if(window.ANNOUNCEMENT_CLOUD_REQUIRED){_isAdmin=false;_applyAdminUI();return}_legacyAdminCheck().then(function(d){_isAdmin=!!(d&&d.admin);_applyAdminUI()}).catch(function(){_isAdmin=false;_applyAdminUI()})})}
document.getElementById("libSearch").addEventListener("keydown",function(e){
 if(e.key!=='Enter')return;
 var v=this.value.trim();if(!/^[a-zA-Z]{6,40}$/.test(v))return; // 仅疑似暗号才发后端，普通搜索(含空格/中文/数字)不外传
 var self=this;
 fetch(API_BASE+'/api/announcement-cloud?action=admin-grant',{method:'POST',headers:{'Content-Type':'application/json','X-Admin-Device':_deviceId()},body:JSON.stringify({d:_deviceId(),p:v})})
  .then(function(r){if(!r.ok)throw new Error('cloud unavailable');return r.json()})
  .catch(function(){if(window.ANNOUNCEMENT_CLOUD_REQUIRED)throw new Error('cloud unavailable');return fetch(API_BASE+'/api/admin/grant',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({d:_deviceId(),p:v})}).then(function(r){if(!r.ok)throw new Error('legacy unavailable');return r.json()})})
  .then(function(d){if(d&&d.admin){_isAdmin=true;_applyAdminUI();clearTimeout(_librarySearchTimer);self.value='';renderLibrary('');window.dispatchEvent(new Event('announcement-admin-ready'));toast('管理员权限已激活')}})
  .catch(function(){}); // 普通英文搜索和错误暗号都按搜索处理，不提示、不封禁
});
_checkAdmin();
// 曲库现在是默认标签，页面加载即载入
if(!window._libLoaded){loadLibrary();loadMeta();window._libLoaded=true}

// ===== 防护(尽力威慑，非绝对)：管理员(本机已激活)不受限；普通访客禁右键/图片拖拽/常见开发者工具快捷键 =====
document.addEventListener('contextmenu',function(e){if(_isAdmin)return;var t=e.target;if(t&&(t.tagName==='INPUT'||t.tagName==='TEXTAREA'))return;e.preventDefault()});
document.addEventListener('dragstart',function(e){if(_isAdmin)return;if(e.target&&e.target.tagName==='IMG')e.preventDefault()});
document.addEventListener('keydown',function(e){
 if(_isAdmin)return;
 var k=(e.key||'').toLowerCase();
 if(k==='f12'){e.preventDefault();return}
 if((e.ctrlKey||e.metaKey)&&e.shiftKey&&(k==='i'||k==='j'||k==='c')){e.preventDefault();return}
 if((e.ctrlKey||e.metaKey)&&(k==='u'||k==='s')){e.preventDefault();return}
});
function playSong(idx,cardEl){
	 var s=LIB_DATA[idx];if(!s)return;
	 if(!s.preview)return;
	 if(!cardEl)cardEl=document.querySelector('.lib-card[data-idx="'+idx+'"]');
	 // 点击同一首 -> 暂停
	 if(_curIdx===idx&&_curAudio&&!_curAudio.paused){
	  stopCurrentSong();
	  return;
	 }
	 // 停止旧音频 & 清除高亮
	 stopCurrentSong();
	 // 高亮当前（立即，不等缓存）
	 if(cardEl){cardEl.classList.add("is-playing","has-played");cardEl.style.boxShadow="0 0 0 3px #3b82f6";var cv=cardEl.querySelector(".cv");if(cv)cv.style.animation="spin 8s linear infinite"}
	 var detailEl=document.getElementById("libDetail");if(detailEl)detailEl.classList.add("is-playing");
	 // 必须在点击事件的同步调用栈中 play()，否则 iOS 会丢失用户手势许可。
	 // 已有 Blob 缓存时直接使用；首次播放走原地址，同时在后台填充 Cache API。
	 var token=++_pendingPlay;
	 var src=_blobUrls[s.preview]||s.preview;
	 var a=document.createElement("audio");a.dataset.songbotPreview="1";a.src=src;a.loop=true;a.volume=0.5;a.preload="auto";a.style.display="none";
	 var fallbackPreview=s.previewFallback||"";
	 if(fallbackPreview&&fallbackPreview!==s.preview){
	  a.onerror=function(){
	   if(a.dataset.fallbackTried==='1'||token!==_pendingPlay||_curAudio!==a)return;
	   a.dataset.fallbackTried='1';a.dataset.fallbackSwitching='1';a.onerror=null;
	   a.src=_blobUrls[fallbackPreview]||fallbackPreview;a.load();
	   var retry=a.play();
	   if(retry&&retry.then)retry.then(function(){delete a.dataset.fallbackSwitching}).catch(function(){if(_curAudio===a){_curAudio=null;_curIdx=-1}_disposePreviewAudio(a);_clearPlayingCardState()});
	  };
	 }
	 document.body.appendChild(a);
	 _curAudio=a;_curIdx=idx;
	 var playResult=a.play();
	 if(playResult&&playResult.then){playResult.then(function(){if(token!==_pendingPlay||_curAudio!==a)_disposePreviewAudio(a)}).catch(function(){if(a.dataset.fallbackSwitching==='1')return;if(_curAudio===a){_curAudio=null;_curIdx=-1} _disposePreviewAudio(a);_clearPlayingCardState()})}
	 if(!_blobUrls[s.preview])loadAsset(s.preview,function(){},fallbackPreview);
	}
	var _curAudio=null,_curIdx=-1,_pendingPlay=0;
	function _disposePreviewAudio(a){
	 if(!a)return;
	 try{a.pause()}catch(e){}
	 try{a.loop=false;a.removeAttribute('src');a.src='';a.load()}catch(e){}
	 try{a.remove()}catch(e){}
	}
	function _clearPlayingCardState(){
	 document.querySelectorAll(".lib-card .cv").forEach(function(c){c.style.animation="none"});
	 document.querySelectorAll(".lib-card").forEach(function(c){c.classList.remove("is-playing");c.style.boxShadow=""});
	 var detailEl=document.getElementById("libDetail");if(detailEl)detailEl.classList.remove("is-playing");
	}
	window.stopCurrentSong=function(){
	 ++_pendingPlay;
	 // iOS Safari can complete a queued play() after the element is detached.
	 // Dispose every preview we created, not only the latest referenced element.
	 document.querySelectorAll('audio[data-songbot-preview="1"]').forEach(_disposePreviewAudio);
	 _curAudio=null;
	 _clearPlayingCardState();
	 _curIdx=-1;
	};
	window.addEventListener('pagehide',window.stopCurrentSong);
	// 难度键名排序权重（easy→normal→hard→master→special→其他）
var _DIFF_ORDER=["easy","ez","normal","nm","hard","hd","master","mx","ms","special","sp"];
function _diffSortKey(k){var i=_DIFF_ORDER.indexOf(k.toLowerCase());return i<0?99:Math.floor(i/2)}
function _diffLabel(k){
 var kl=k.toLowerCase();
 if(kl==="easy"||kl==="ez")return"简单";
 if(kl==="normal"||kl==="nm")return"普通";
 if(kl==="hard"||kl==="hd")return"困难";
 if(kl==="master"||kl==="mx"||kl==="ms")return"大师";
 if(kl==="special"||kl==="sp")return"特殊";
 return k;
}
function _diffCls(k){
 var kl=k.toLowerCase();
 if(kl==="easy"||kl==="ez")return"ld-easy";
 if(kl==="normal"||kl==="nm")return"ld-normal";
 if(kl==="hard"||kl==="hd")return"ld-hard";
 return"ld-master";
}

function _modeTagCls(m){if(m==='4K')return'ld-tag-4k';if(m==='5K')return'ld-tag-5k';if(m==='6K')return'ld-tag-6k';if(m==='Catch')return'ld-tag-catch';return'ld-tag-4k'}

function _applyPopupBg(covUrl){
 var dd=document.getElementById('libDetailContent');if(!dd||!covUrl)return;
 var src=_blobUrls[covUrl]||covUrl;
 if(!src)return;
 var bg=dd.querySelector('.ld-blur-bg');
 if(!bg){bg=document.createElement('div');bg.className='ld-blur-bg';dd.insertBefore(bg,dd.firstChild)}
 bg.style.backgroundImage='url('+src+')';
}

function showSongDetail(idx){
 var s=LIB_DATA[idx];if(!s)return;
 var covSrc=_blobUrls[s.cover]||s.cover||"";
 var covFallback=s.coverFallback||"";
 var charterStr=(s.charters||[]).join("、")||"—";

 var chartsHtml="";
 (s.charts||[]).forEach(function(c){
  var isCatch=Object.keys(c).some(function(k){return k!=="mode"&&c[k]&&c[k].isMode3});
  var modeLabel=isCatch?"Catch":(c.mode||"");
  var keys=Object.keys(c).filter(function(k){
   if(k==="mode")return false;
   var d=c[k];if(!d)return false;
   return isCatch?!!d.isMode3:(!d.isMode3&&(d.level||d.combo));
  });
  keys.sort(function(a,b){return _diffSortKey(a)-_diffSortKey(b)});
  if(!keys.length)return;
  chartsHtml+='<div class="ld-mode"><span class="ld-mode-tag '+_modeTagCls(modeLabel)+'">'+escHtml(modeLabel)+'</span><div class="ld-diffs">';
  keys.forEach(function(k){
   var d=c[k];
   var label,cls;
   if(isCatch){label=d.name||k;cls=_diffCls(k)}
   else{label=_diffLabel(k)+' Lv.'+(d.level||0)+'  键数：'+(d.combo||0);cls=_diffCls(k)}
   chartsHtml+='<span class="ld-diff '+cls+'">'+escHtml(label)+'</span>';
  });
  chartsHtml+='</div></div>';
 });

 var _pshHtml='';
 if(_DS_CFG.popup&&_DS_CFG.popup.shapes){(_DS_CFG.popup.shapes||[]).forEach(function(sh,i){_pshHtml+='<div class="ld-psh'+i+'"></div>'})}
 var dd=document.getElementById("libDetailContent");
 dd.style.background='';  // 重置，等待封面取色覆盖
 dd.innerHTML=
  _pshHtml
  +'<div class="ld-header">'
  +'<img class="ld-cover" src="'+escHtml(covSrc)+'"'+(covFallback?' data-cover-fallback="'+escHtml(covFallback)+'"':'')+'>'
  +'<div class="ld-name">'+escHtml(s.name)+'</div>'
  +'<div class="ld-artist">'+escHtml(s.artist||"")+'</div>'
  +'<div class="ld-charter"><span class="ld-charter-label">谱面：</span><span class="ld-charter-name">'+escHtml(charterStr)+'</span></div>'
  +'<div class="ld-heart'+(_LIKED.has(s.id)?' liked':'')+'" data-hid="'+s.id+'">'+_HEART_SVG+'<span class="heart-n">'+(_LIKES[s.id]||0)+'</span></div>'
  +'</div>'
  +chartsHtml
  +'<button class="ld-close" onclick="stopCurrentSong();document.getElementById(\'libDetail\').classList.remove(\'show\')">关闭</button>';

 var detailCover=dd.querySelector(".ld-cover"),detailHeader=dd.querySelector(".ld-header");
 if(detailCover){detailCover.onerror=function(){var fallback=this.dataset.coverFallback||'';if(fallback&&this.dataset.coverTriedFallback!=='1'){this.dataset.coverTriedFallback='1';this.src=fallback}else this.removeAttribute('src')}}
 if(detailCover&&detailHeader){
  var detailCoverStyle=getComputedStyle(detailCover);
  detailHeader.style.setProperty("--detail-play-left",(detailCover.offsetLeft-4)+"px");
  detailHeader.style.setProperty("--detail-play-top",(detailCover.offsetTop-4)+"px");
  detailHeader.style.setProperty("--detail-play-width",(detailCover.offsetWidth+8)+"px");
  detailHeader.style.setProperty("--detail-play-height",(detailCover.offsetHeight+8)+"px");
  detailHeader.style.setProperty("--detail-play-radius",detailCoverStyle.borderRadius||"0");
 }

 document.getElementById("libDetail").classList.add("show");
	 // 小窗爱心：定位(来自 _dsPopup.heart) + 单击/双击
	 var _hpp=(window._dsPopup||(_DS_CFG&&_DS_CFG.popup)||{}),_hp=_hpp.heart||{};
	 var _hEl=dd.querySelector('.ld-heart');
	 if(_hEl){
	  _hEl.style.right=(_hp.hRight!=null?_hp.hRight:20)+'px';_hEl.style.left='auto';
	  _hEl.style.top=(_hp.hY!=null?_hp.hY:8)+'px';
	  _hEl.style.fontSize=(_hp.hSize!=null?_hp.hSize:26)+'px';
	  _hEl.style.setProperty('--heart-color',_hp.hColor||'#cbd5e1');
	  if(_hp.hVisible===false)_hEl.style.display='none';
	  _hEl.onclick=function(){heartToggle(s.id)};
	 }
	 // 应用小窗文字边界：优先实时编辑值(window._dsPopup)，其次已保存配置(_DS_CFG.popup)
	 var pp=window._dsPopup||(_DS_CFG&&_DS_CFG.popup)||{naX:96,naRight:350,arX:96,arRight:350};
	 var nm=document.querySelector('.ld-name');if(nm){nm.style.left=(pp.naX||96)+'px';nm.style.right='auto';nm.style.width=((pp.naRight||350)-(pp.naX||96))+'px'}
	 var ar=document.querySelector('.ld-artist');if(ar){ar.style.left=(pp.arX||96)+'px';ar.style.right='auto';ar.style.width=((pp.arRight||350)-(pp.arX||96))+'px'}
 // 小窗歌名/作者/谱师走马灯：文字元素作为固定裁剪框(overflow:hidden)，
 // 歌名与作者避开右上角爱心；谱师位于标题区底部，使用标题区右边界。
 // 内层 .ld-mq 承载双份文字并 translateX，与主界面卡片共用同一套算法。
 setTimeout(function(){
  var isMobile=window.innerWidth<=768;
  var pixelSpeed=isMobile?40:80; // px/s，三类文字共用 → 滚动速度一致
  var reduceMotion=window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var heartLeft=(_hEl&&_hEl.offsetParent)?(_hEl.offsetLeft-6):Infinity; // 爱心占位左界(相对标题区)，曲名/作者不得越过
  document.querySelectorAll('.ld-name, .ld-artist, .ld-charter').forEach(function(el){
   var pp=window._dsPopup||(_DS_CFG&&_DS_CFG.popup)||{};
   var isName=el.classList.contains('ld-name');
   var isArtist=el.classList.contains('ld-artist');
   var isCharter=!isName&&!isArtist;
   var leftX=isName?pp.naX:(isArtist?pp.arX:pp.chX);if(leftX==null)leftX=96;
   var rightX;
   if(isName||isArtist){
    rightX=isName?pp.naRight:pp.arRight;if(rightX==null)rightX=350;
    rightX=Math.min(rightX,heartLeft); // 上方文字末端触及爱心占位左侧即截断/滚动
   }else{
    var header=el.parentElement;
    rightX=header&&header.clientWidth?header.clientWidth:500; // 谱师不与上方爱心重叠，保留完整可用宽度
   }
   var topY=isName?pp.naY:(isArtist?pp.arY:pp.chY);
   if(topY==null)topY=isName?4:(isArtist?28:50);
   var maxW=Math.max(10,rightX-leftX);
   var contentEl=el;
   if(isCharter){
    var charterLabel=el.querySelector('.ld-charter-label');
    contentEl=el.querySelector('.ld-charter-name');
    el.style.display='flex';el.style.alignItems='center';
    if(charterLabel){charterLabel.style.flex='0 0 auto'}
    if(contentEl){contentEl.style.flex='1 1 auto';contentEl.style.minWidth='0'}
   }
   if(!contentEl)return;
   var txt=contentEl.textContent;
   // 固定裁剪框：左右界即隐形文字框，超出部分由 overflow:hidden 裁掉
   // top 同步 _dsPopup，保证实际弹窗位置与设计器预览一致(生成CSS仅在注入保存后才生效)
   el.style.animation='none';
   el.style.left=leftX+'px';el.style.right='auto';el.style.top=topY+'px';
   el.style.width=maxW+'px';
   el.style.overflow='hidden';el.style.whiteSpace='nowrap';el.style.textOverflow='clip';
   contentEl.style.overflow='hidden';contentEl.style.whiteSpace='nowrap';contentEl.style.textOverflow='clip';
   var contentMaxW=isCharter?contentEl.getBoundingClientRect().width:maxW;
   // 测量文字自然宽度
   contentEl.innerHTML='<span style="display:inline-block;white-space:nowrap">'+escHtml(txt)+'</span>';
   var textW=contentEl.firstChild.getBoundingClientRect().width;
   // 仅当文字宽度超过裁剪框才滚动
   if(textW>contentMaxW+2&&!reduceMotion){
    var gapPx=Math.round(contentMaxW*0.3);
    contentEl.innerHTML='<div class="ld-mq" style="display:inline-block;white-space:nowrap;will-change:transform">'
     +'<span style="display:inline-block;white-space:nowrap">'+escHtml(txt)+'</span>'
     +'<span style="display:inline-block;width:'+gapPx+'px"></span>'
     +'<span style="display:inline-block;white-space:nowrap">'+escHtml(txt)+'</span>'
     +'</div>';
    var mq=contentEl.firstChild;
    var span1W=mq.querySelector('span').getBoundingClientRect().width;
    var offset=Math.ceil(span1W+gapPx);
    mq.style.setProperty('--marquee-step',(-offset)+'px');
    mq.style.animation='scrollX '+Math.max(3,(span1W+gapPx)/pixelSpeed)+'s linear infinite';
   }
  });
 },200);
 // 封面取色（useCoverBg 默认开启，dsConfig.popup.useCoverBg===false 时跳过）
 if(!_DS_CFG.popup||_DS_CFG.popup.useCoverBg!==false){
  if(s.cover){loadAsset(s.cover,function(bu){_applyPopupBg(bu||s.cover)},s.coverFallback)}
 }
}

window.showSongDetail=showSongDetail;
	document.getElementById("libDetail").addEventListener("click",function(e){if(e.target===this){stopCurrentSong();this.classList.remove("show")}});
