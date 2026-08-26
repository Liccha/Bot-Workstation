const $=selector=>document.querySelector(selector);
const pairView=$('#pair'),appView=$('#app'),message=$('#pair-message'),toast=$('#toast');
const dialog=$('#editor-dialog'),fields=$('#editor-fields'),editorMessage=$('#editor-message');
let installEvent,token=localStorage.getItem('botstation.token')||'',editorState=null;

async function api(path,options={}){
  const headers={'Content-Type':'application/json',...(options.headers||{})};
  if(token)headers.Authorization=`Bearer ${token}`;
  const response=await fetch(path,{...options,headers});
  const data=await response.json().catch(()=>({}));
  if(!response.ok)throw new Error(data.error||`请求失败 (${response.status})`);
  return data;
}
function showToast(text){toast.textContent=text;toast.classList.add('show');clearTimeout(showToast.timer);showToast.timer=setTimeout(()=>toast.classList.remove('show'),2200)}
function setState(id,value){const el=$(`#${id}-state`);const labels={running:'已启用',starting:'启动中',stopped:'已停用',degraded:'状态异常',failed:'异常退出'};el.textContent=labels[value]||'未知';el.className=`state ${value||''}`}
async function refresh(){try{const state=await api('/api/status');setState('songbot',state.songBot);setState('napcat',state.napCat);$('#connection').textContent='已连接';refreshUpdate()}catch(error){if(/配对/.test(error.message))unpair();else showToast(error.message)}}
async function refreshUpdate(){try{const value=await api('/api/update');const card=$('#update-card');card.hidden=!value.available;$('#update-title').textContent=value.available?`发现新版本 ${value.latest}`:'已是最新版';$('#update-notes').textContent=value.notes||''}catch(_){$('#update-card').hidden=true}}
function enter(){pairView.hidden=true;appView.hidden=false;refresh()}
function unpair(){token='';localStorage.removeItem('botstation.token');appView.hidden=true;pairView.hidden=false;message.textContent='';$('#pair-code').value=''}

$('#pair-form').addEventListener('submit',async event=>{event.preventDefault();message.textContent='';const button=event.submitter;button.disabled=true;button.textContent='连接中…';try{const result=await api('/api/pair',{method:'POST',body:JSON.stringify({code:$('#pair-code').value})});token=result.token;localStorage.setItem('botstation.token',token);enter()}catch(error){message.textContent=error.message}finally{button.disabled=false;button.textContent='连接工作站'}});
document.querySelectorAll('[data-action]').forEach(button=>button.addEventListener('click',async()=>{button.disabled=true;try{await api('/api/action',{method:'POST',body:JSON.stringify({action:button.dataset.action})});showToast('操作已提交');await refresh()}catch(error){showToast(error.message)}finally{button.disabled=false}}));
document.querySelectorAll('[data-view]').forEach(button=>button.addEventListener('click',()=>selectView(button.dataset.view,button)));
function selectView(id,button){
  document.querySelectorAll('.view').forEach(view=>view.classList.toggle('active',view.id===id));
  document.querySelectorAll('[data-view]').forEach(item=>item.classList.toggle('selected',item===button));
  window.scrollTo({top:0,behavior:matchMedia('(prefers-reduced-motion:reduce)').matches?'auto':'smooth'});
  if(id==='songs'&&!$('#song-list').childElementCount)loadSongs();
  if(id==='stable'&&!$('#stable-list').childElementCount)loadStable();
}

const songFields=[['song_name','歌曲名'],['author','作者'],['charter','谱师'],['bpm','BPM'],['duration','时长'],['album','专辑'],['song_nickname','歌曲别名']];
const stableFields=[['title','曲名'],['artist','作者'],['bpm','BPM'],['length','时长'],['creator','谱师'],['update_time','更新时间'],['cover','封面']];
$('#song-search-form').addEventListener('submit',event=>{event.preventDefault();loadSongs()});
$('#stable-search-form').addEventListener('submit',event=>{event.preventDefault();loadStable()});

async function loadSongs(){
  const list=$('#song-list'),meta=$('#song-meta');list.replaceChildren(empty('正在读取…'));meta.textContent='';
  try{const data=await api(`/api/songs?q=${encodeURIComponent($('#song-query').value)}&limit=100`);renderRows(list,data.items,'song');meta.textContent=`显示 ${data.items.length} 条记录`}
  catch(error){list.replaceChildren(empty(error.message))}
}
async function loadStable(){
  const list=$('#stable-list'),meta=$('#stable-meta');list.replaceChildren(empty('正在读取…'));meta.textContent='';
  try{const data=await api(`/api/stable?q=${encodeURIComponent($('#stable-query').value)}&limit=120`);renderRows(list,data.items,'stable');meta.textContent=`显示 ${data.items.length} 条记录`}
  catch(error){list.replaceChildren(empty(error.message))}
}
function renderRows(list,items,type){
  list.replaceChildren();
  if(!items.length){list.append(empty('没有符合条件的记录'));return}
  items.forEach((row,index)=>{
    const button=document.createElement('button');button.type='button';button.className='data-card';
    const copy=document.createElement('span');copy.className='data-copy';const title=document.createElement('strong');const detail=document.createElement('span');const action=document.createElement('b');
    if(type==='song'){title.textContent=value(row,'song_name')||`歌曲 ${value(row,'id')}`;detail.textContent=[value(row,'author'),value(row,'charter'),value(row,'bpm')&&`BPM ${value(row,'bpm')}`].filter(Boolean).join(' · ');}
    else{title.textContent=value(row,'title')||`Stable ${value(row,'sid')}`;detail.textContent=[value(row,'artist'),value(row,'creator'),value(row,'bpm')&&`BPM ${value(row,'bpm')}`].filter(Boolean).join(' · ');}
    action.textContent='编辑';copy.append(title,detail);button.append(copy,action);button.addEventListener('click',()=>openEditor(type,row));
    button.classList.add('reveal');if(index===1)button.classList.add('delay-1');if(index>=2)button.classList.add('delay-2');list.append(button);
  });
}
function value(row,key){const actual=Object.keys(row).find(name=>name.toLowerCase()===key.toLowerCase());return actual==null?'':String(row[actual]??'')}
function empty(text){const item=document.createElement('div');item.className='empty-state';item.textContent=text;return item}

function openEditor(type,row){
  const definition=type==='song'?songFields:stableFields;const idKey=type==='song'?'id':'sid';const id=value(row,idKey);
  editorState={type,id};fields.replaceChildren();editorMessage.textContent='';$('#editor-title').textContent=type==='song'?'编辑歌曲':'编辑 Stable 记录';$('#editor-subtitle').textContent=`${idKey.toUpperCase()} ${id}`;
  definition.forEach(([key,label])=>{const wrap=document.createElement('div');wrap.className='field';const fieldLabel=document.createElement('label');const input=document.createElement('input');fieldLabel.textContent=label;input.name=key;input.value=value(row,key);input.maxLength=500;fieldLabel.htmlFor=`edit-${key}`;input.id=`edit-${key}`;wrap.append(fieldLabel,input);fields.append(wrap)});
  if(typeof dialog.showModal==='function')dialog.showModal();else dialog.setAttribute('open','');
}
function closeEditor(){if(typeof dialog.close==='function')dialog.close();else dialog.removeAttribute('open')}
$('#editor-close').addEventListener('click',closeEditor);$('#editor-cancel').addEventListener('click',closeEditor);
$('#editor-form').addEventListener('submit',async event=>{
  event.preventDefault();if(!editorState)return;const submit=event.submitter;submit.disabled=true;editorMessage.textContent='';
  const values={};new FormData(event.currentTarget).forEach((item,key)=>values[key]=String(item));
  const path=editorState.type==='song'?'/api/song':'/api/stable';const key=editorState.type==='song'?'id':'sid';
  try{await api(path,{method:'POST',body:JSON.stringify({[key]:editorState.id,values})});closeEditor();showToast('已保存并同步到底层数据');if(editorState.type==='song')await loadSongs();else await loadStable()}
  catch(error){editorMessage.textContent=error.message}finally{submit.disabled=false}
});

$('#refresh').addEventListener('click',refresh);$('#unpair').addEventListener('click',unpair);
window.addEventListener('beforeinstallprompt',event=>{event.preventDefault();installEvent=event;$('#install').hidden=false});
$('#install').addEventListener('click',async()=>{if(installEvent){installEvent.prompt();await installEvent.userChoice;installEvent=null}});
if('serviceWorker'in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});
if(token)enter();
