/* Bot Editor module: website-editor.js */
// ==================== 网站编辑器 ====================
(function(){
var BLOG_API=API_BASE+'/api/blog';
var _currentFile=null;
var _currentRevision=null;
var _openGeneration=0,_listGeneration=0;
function useCloudWebsite(){return !!window.ANNOUNCEMENT_CLOUD_REQUIRED}
function websiteUrl(action,name){if(!useCloudWebsite())return BLOG_API+'/'+action+(name?'?'+encodeURIComponent(name):'');return API_BASE+'/api/announcement-cloud?action=website-'+action+(name?'&name='+encodeURIComponent(name):'')}

function weToast(msg){
	var t=document.getElementById('weToast');
	t.textContent=msg;t.classList.add('show');
	setTimeout(function(){t.classList.remove('show')},2000);
}

window.openWebsiteEditor=function(){
	if(!_isAdmin){toast('需要管理员权限');return}
	document.getElementById('weOverlay').classList.add('show');
	syncPosts();
};

window.closeWebsiteEditor=function(){
	document.getElementById('weOverlay').classList.remove('show');
	_openGeneration++;
	_currentFile=null;
	_currentRevision=null;
	document.getElementById('weEditor').value='';
	document.getElementById('weEditor').style.display='none';
	document.getElementById('weEditorToolbar').style.display='none';
	document.getElementById('weEditorEmpty').style.display='';
	document.getElementById('weFileName').textContent='—';
};

window.syncPosts=function(){
	var generation=++_listGeneration;
	var list=document.getElementById('weFileList');
	list.innerHTML='<div class="we-empty-state">加载中...</div>';
	fetch(websiteUrl('list'),{headers:adminHeaders(),cache:'no-store'})
		.then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()})
		.then(function(files){
			if(generation!==_listGeneration)return;
			if(!Array.isArray(files)){list.innerHTML='<div class="we-empty-state">无数据</div>';return}
			if(files.length===0){list.innerHTML='<div class="we-empty-state">暂无 .md 文件</div>';return}
			list.innerHTML='';
			files.forEach(function(f){
				var fname=f.name||f;
				var div=document.createElement('div');
				div.className='we-file-item';
				div.textContent=fname;
				div.addEventListener('click',function(){openMdFile(fname)});
				list.appendChild(div);
			});
		})
		.catch(function(e){if(generation===_listGeneration)list.innerHTML='<div class="we-empty-state" style="color:#ef4444">加载失败: '+escHtml(e.message)+'</div>'});
};

function openMdFile(filename){
	var generation=++_openGeneration;
	_currentFile=filename;
	_currentRevision=null;
	document.getElementById('weFileName').textContent=filename;
	document.getElementById('weEditorEmpty').style.display='none';
	document.getElementById('weEditor').style.display='';
	document.getElementById('weEditorToolbar').style.display='';
	document.getElementById('weEditor').value='加载中...';
	document.getElementById('weEditor').disabled=true;
	document.querySelectorAll('.we-file-item').forEach(function(el){el.classList.remove('active')});
	var items=document.querySelectorAll('.we-file-item');
	items.forEach(function(el){if(el.textContent===filename)el.classList.add('active')});

	fetch(websiteUrl('read',filename),{headers:adminHeaders(),cache:'no-store'})
		.then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()})
		.then(function(d){
			if(generation!==_openGeneration||_currentFile!==filename)return;
			_currentRevision=d.revision==null?null:Number(d.revision);
			document.getElementById('weEditor').value=d.content||'';
			document.getElementById('weEditor').disabled=false;
			document.getElementById('weEditor').focus();
		})
		.catch(function(e){
			if(generation!==_openGeneration||_currentFile!==filename)return;
			document.getElementById('weEditor').value='// 加载失败: '+e.message;
			document.getElementById('weEditor').disabled=false;
		});
}

window.saveCurrentMd=function(){
	if(!_currentFile){weToast('请先选择文件');return}
	var savingFile=_currentFile;
	var content=document.getElementById('weEditor').value;
	var btn=document.getElementById('weSaveBtn');
	btn.disabled=true;btn.textContent='保存中...';
	fetch(websiteUrl('save'),{
		method:'POST',
		headers:adminHeaders({'Content-Type':'application/json'}),
		body:JSON.stringify({name:savingFile,content:content,revision:_currentRevision})
	})
	.then(function(r){if(!r.ok){var e=new Error(r.status===409?'文章已被其他人修改，请重新打开':'HTTP '+r.status);throw e}return r.json()})
	.then(function(saved){if(saved&&saved.revision!=null)_currentRevision=Number(saved.revision);weToast('已保存: '+savingFile);btn.disabled=false;btn.textContent='保存文章'})
	.catch(function(e){weToast('保存失败: '+e.message);btn.disabled=false;btn.textContent='保存文章'});
};

window.downloadCurrentMd=function(){
	if(!_currentFile){weToast('请先选择文件');return}
	var content=document.getElementById('weEditor').value;
	var blob=new Blob([content],{type:'text/markdown;charset=utf-8'});
	var url=URL.createObjectURL(blob);
	var a=document.createElement('a');a.href=url;a.download=_currentFile;
	document.body.appendChild(a);a.click();a.remove();
	setTimeout(function(){URL.revokeObjectURL(url)},1000);
};


window.uploadNewMd=function(){
	document.getElementById('weFileInput').click();
};

window.handleMdUpload=function(input){
	var file=input.files[0];if(!file)return;
	if(!file.name.toLowerCase().endsWith('.md')){weToast('只允许上传 .md 文件');input.value='';return}
	if(file.size>4*1024*1024){weToast('文件不能超过 4MB');input.value='';return}
	var reader=new FileReader();
	reader.onload=function(){
		var content=reader.result;
		fetch(websiteUrl('save'),{
			method:'POST',
			headers:adminHeaders({'Content-Type':'application/json'}),
			body:JSON.stringify({name:file.name,content:content})
		})
		.then(function(r){if(!r.ok)throw new Error(r.status===409?'同名文章已经存在，请先打开后编辑':'HTTP '+r.status);return r.json()})
		.then(function(){weToast('已上传: '+file.name);syncPosts()})
		.catch(function(e){weToast('上传失败: '+e.message)});
	};
	reader.readAsText(file,'utf-8');
	input.value='';
};

// 点击遮罩关闭
document.getElementById('weOverlay').addEventListener('click',function(e){if(e.target===this)closeWebsiteEditor()});
document.addEventListener('keydown',function(e){if(e.key==='Escape'&&document.getElementById('weOverlay').classList.contains('show'))closeWebsiteEditor()});
})();
