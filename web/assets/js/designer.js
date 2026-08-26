/* Bot Editor module: designer.js */
// ==================== 卡片设计器 (WYSIWYG v3) ====================
	// 初始 HTML 一律隐藏；只有确认是本机地址后才开放，避免正式站首屏闪现。
	(function(){
		var b=document.querySelector('[data-tab="design"]');
		var t=document.getElementById("tabDesign");
		if(IS_LOCAL_HOST){
			if(b)b.removeAttribute("hidden");
			if(t)t.removeAttribute("hidden");
		}else{
			if(b)b.setAttribute("hidden","");
			if(t)t.setAttribute("hidden","");
		}
	})();

	var _ds={
		card:{w:700,h:110,bg:"rgba(255,255,255,0.85)",brTL:12,brTR:12,brBL:12,brBR:12,gap:8,mobileGap:12},
		cover:{x:14,y:14,w:80,h:80,radius:50,ringW:3,ringColor:"#e2e8f0",shadow:"0 0 0 2px rgba(59,130,246,.15)",visible:true},
		name:{x:110,y:14,font:"默认",size:13,color:"#1e293b",weight:"bold",visible:true,prefix:"",suffix:""},
		artist:{x:110,y:36,font:"默认",size:11,color:"#64748b",weight:"normal",visible:true,prefix:"",suffix:""},
		bpm:{x:110,y:55,font:"默认",size:10,color:"#94a3b8",visible:true,prefix:"BPM:",suffix:""},
		duration:{x:180,y:55,font:"默认",size:10,color:"#94a3b8",visible:true,prefix:"",suffix:""},
		charters:{x:250,y:55,font:"默认",size:10,color:"#94a3b8",visible:true,prefix:"",suffix:""},
		tags:{x:110,y:72,font:"默认",size:9,color:"#1e40af",bg:"#dbeafe",visible:true,tagFont:"默认",tagSize:9,tagColor:"#1e40af",tagBg:"#dbeafe",bg4k:"#dbeafe",bg5k:"#dbeafe",bg6k:"#dbeafe",bgCatch:"#fce7f3",color4k:"#1e40af",color5k:"#1e40af",color6k:"#1e40af",colorCatch:"#9d174d"},
		heart:{right:16,y:10,size:26,color:"#cbd5e1",visible:true},
		shapes:[]
	};
	var _dsSel=null,_dsDragType="",_dsStartX=0,_dsStartY=0,_dsOrig={},_dsFonts=[],_dsSaving=false;

	(function initDesigner2(){
		if(!document.querySelector('[data-tab="design"]'))return;
		if(!IS_LOCAL_HOST)return;
		var API=API_BASE+"/api/design/save";
		var card=document.getElementById("dsCard");
		var elList=document.getElementById("dsElList"),props=document.getElementById("dsProps");

		fetch(API_BASE+"/api/fonts/list").then(function(r){return r.json()}).then(function(f){_dsFonts=f}).catch(function(){});

		function readCSS(){
			try{var dc2=document.getElementById("dsConfig");if(dc2){var cfg=JSON.parse(dc2.textContent);
			if(cfg.card){var c2=cfg.card;_ds.card.w=c2.w||700;_ds.card.h=c2.h||110;_ds.card.bg=c2.bg||_ds.card.bg;_ds.card.brTL=c2.brTL||12;_ds.card.brTR=c2.brTR||12;_ds.card.brBL=c2.brBL||12;_ds.card.brBR=c2.brBR||12;_ds.card.gap=c2.gap||8;_ds.card.mobileGap=c2.mobileGap||12}
			if(cfg.cover){var cv=cfg.cover;_ds.cover=cv}
			if(cfg.shapes)_ds.shapes=cfg.shapes;
			["name","artist","bpm","duration","charters","tags"].forEach(function(k){if(cfg[k]){var o=cfg[k];for(var p in o)_ds[k][p]=o[p]}})
			if(cfg.tags){var tg=cfg.tags;for(var p in tg)_ds.tags[p]=tg[p]}
			if(cfg.heart){var ht=cfg.heart;for(var p in ht)_ds.heart[p]=ht[p]}
			}}catch(e){}
		}
		readCSS();

function cardCSS(){
			return "width:"+_ds.card.w+"px;height:"+_ds.card.h+"px;background:"+_ds.card.bg
				+";border-radius:"+_ds.card.brTL+"px "+_ds.card.brTR+"px "+_ds.card.brBR+"px "+_ds.card.brBL+"px";
		}

		// Ensure shape has proper defaults
		function normShape(sh){
			if(!sh.type)sh.type="rect";
			if(!sh.vertices||!sh.vertices.length){
				if(sh.type==="polygon")sh.vertices=[{x:0,y:0},{x:1,y:0},{x:0.5,y:1}];
				else sh.vertices=[];
			}
			if(sh.type==="circle")sh.w=sh.h=Math.max(sh.w||60,sh.h||60);
			return sh;
		}

		// Legacy templates used an enormous width to make horizontal bars reach
		// the card edge. Keep their visual result without allocating giant layers.
		function stretchesToRight(sh){return !!(sh&&((sh.stretchRight===true)||(Number(sh.w)>=10000)));}
		function shapeBoxCss(sh){
			return "position:absolute;left:"+sh.x+"px;top:"+sh.y+"px;"
				+(stretchesToRight(sh)?"right:0;width:auto;":"width:"+sh.w+"px;")
				+"height:"+sh.h+"px;";
		}

		function buildPreview(){
			card.style.cssText=cardCSS();
			card.innerHTML="";
			// Corner handles
			["TL","TR","BL","BR"].forEach(function(c){
				var h=document.createElement("div");h.className="ds-corner-handle";h.dataset.corner=c;
				h.addEventListener("pointerdown",function(e){startDrag(e,"corner_"+c)});
				card.appendChild(h);
			});
			// Shapes
			(_ds.shapes||[]).forEach(function(sh,i){
				normShape(sh);
				if((sh.zIndex||0)>0)return; // bg layer first
				renderShape(sh,i);
			});
			// Cover
			if(_ds.cover.visible!==false){
				var w=document.createElement("div");w.className="ds-el-cover";
				w.style.cssText="left:"+_ds.cover.x+"px;top:"+_ds.cover.y+"px;width:"+_ds.cover.w+"px;height:"+_ds.cover.h+"px";
				var img=document.createElement("img");
				img.src="https://raw.githubusercontent.com/Liccha/song-library/master/covers/1.jpg";
				img.style.cssText="border-radius:"+_ds.cover.radius+"%;border:"+_ds.cover.ringW+"px solid "+_ds.cover.ringColor+";box-shadow:"+_ds.cover.shadow;
				img.onerror=function(){this.style.display="none"};img.draggable=false;
				w.appendChild(img);
				["nw","ne","sw","se"].forEach(function(p){var rh=document.createElement("div");rh.className="ds-resize-handle "+p;w.appendChild(rh)});
				w.addEventListener("pointerdown",function(e){if(e.target===img||e.target===w){selectEl("cover");startDrag(e,"move_cover")}});
				card.appendChild(w);
			}
			// Shapes (foreground)
			(_ds.shapes||[]).forEach(function(sh,i){
				if((sh.zIndex||0)<=0)return;
				renderShape(sh,i);
			});
			// Text elements
			["name","artist","bpm","duration","charters","tags"].forEach(function(t){
				if(_ds[t].visible===false)return;
				var el=document.createElement("div");el.className="ds-el";el.dataset.el=t;
				var prefix=_ds[t].prefix||"",suffix=_ds[t].suffix||"";
				var sample={name:"踏上旅途 (Take the Journey)",artist:"HOYO-MiX",bpm:"128",duration:"02:14",charters:"谱师A, 谱师B",tags:""}[t];
				el.style.cssText="left:"+_ds[t].x+"px;top:"+_ds[t].y+"px;font-family:"+(_ds[t].font==="默认"?"inherit":_ds[t].font)+";font-size:"+_ds[t].size+"px;color:"+_ds[t].color+";line-height:1.4;font-weight:"+(_ds[t].weight||"normal");
				if(t==="tags")el.style.cssText+=";background:"+(_ds[t].bg||"#dbeafe")+";padding:1px 6px;border-radius:4px";
				el.textContent=t==="tags"?"4K 5K":prefix+sample+suffix;
				el.addEventListener("pointerdown",function(e){e.stopPropagation();selectEl(t);startDrag(e,"move_el")});
				card.appendChild(el);
			});
			// 爱心（固定贴卡片右侧，只调距右/距顶/大小/颜色，不自由拖拽横向位置）
			if(_ds.heart.visible!==false){
				var hEl=document.createElement("div");hEl.className="ds-el";hEl.dataset.el="heart";
				hEl.style.cssText="right:"+_ds.heart.right+"px;left:auto;top:"+_ds.heart.y+"px;font-size:"+_ds.heart.size+"px;color:"+_ds.heart.color+";line-height:1;display:flex;flex-direction:column;align-items:center";
				hEl.innerHTML=_HEART_SVG+"<span style=\"font-size:12px;color:#64748b\">0</span>";
				hEl.addEventListener("pointerdown",function(e){e.stopPropagation();selectEl("heart")});
				card.appendChild(hEl);
			}
			card.addEventListener("pointerdown",function(e){
				if(e.target===card||e.target.classList.contains("ds-corner-handle"))selectEl(null);
			});
		}

		function renderShape(sh,i){
			var d=document.createElement("div");d.className="ds-shape";d.dataset.el="shape_"+i;
			d.style.cssText=shapeBoxCss(sh)+"background:"+(sh.color||"#e2e8f0")+";opacity:"+(sh.opacity||1)+";z-index:"+(sh.zIndex||0);
			if(sh.type==="circle"){d.style.cssText+=";border-radius:50%"}
			else if(sh.type==="rect"){if(sh.radius)d.style.cssText+=";border-radius:"+sh.radius+"px"}
			else if(sh.type==="polygon"&&sh.vertices&&sh.vertices.length>=3){
				var pts=sh.vertices.map(function(v){return Math.round(v.x*sh.w)+"px "+Math.round(v.y*sh.h)+"px"}).join(",");
				d.style.cssText+=";clip-path:polygon("+pts+")";
			}
			d.addEventListener("pointerdown",function(e){e.stopPropagation();selectEl("shape_"+i);
				if(e.target.classList.contains("ds-vtx"))return;
				startDrag(e,"move_shape");
			});
			card.appendChild(d);
			// Vertex handles for polygon
			if(sh.type==="polygon"&&sh.vertices){
				// Vertex dots
				sh.vertices.forEach(function(v,vi){
					var vh=document.createElement("div");vh.className="ds-vtx";
					vh.style.cssText="position:absolute;width:8px;height:8px;background:#f59e0b;border:1.5px solid #fff;border-radius:50%;z-index:25;cursor:grab;left:"+(Math.round(v.x*sh.w)-4)+"px;top:"+(Math.round(v.y*sh.h)-4)+"px";
					vh.addEventListener("pointerdown",function(e){e.stopPropagation();selectEl("shape_"+i);startDrag(e,"move_vertex_"+i+"_"+vi)});
					d.appendChild(vh);
				});
				// Midpoint dots (lighter, for adding vertices)
				for(var vi=0;vi<sh.vertices.length;vi++){
					var v1=sh.vertices[vi],v2=sh.vertices[(vi+1)%sh.vertices.length];
					var mx=(v1.x+v2.x)/2,my=(v1.y+v2.y)/2;
					var mh=document.createElement("div");mh.className="ds-mid";
					mh.style.cssText="position:absolute;width:5px;height:5px;background:#94a3b8;border:1px solid #fff;border-radius:50%;z-index:24;cursor:pointer;left:"+(Math.round(mx*sh.w)-2)+"px;top:"+(Math.round(my*sh.h)-2)+"px";
					mh.addEventListener("pointerdown",function(e){e.stopPropagation();
						sh.vertices.splice(vi+1,0,{x:mx,y:my});selectEl("shape_"+i);buildPreview();
					});
					d.appendChild(mh);
				}
			}else{
				// Non-polygon: corner resize handles
				["nw","ne","sw","se"].forEach(function(p){
					var rh=document.createElement("div");rh.className="ds-resize-handle "+p;d.appendChild(rh);
				});
			}
		}

		function selectEl(name){
			_dsSel=name;
			card.querySelectorAll(".ds-el,.ds-el-cover,.ds-shape,.ds-vtx").forEach(function(el){el.classList.remove("selected")});
			if(name&&name.startsWith("shape_")){
				var idx=parseInt(name.split("_")[1]);
				card.querySelectorAll(".ds-shape").forEach(function(el,i){if(i===idx)el.classList.add("selected")});
			}else if(name==="cover"){var c=card.querySelector(".ds-el-cover");if(c)c.classList.add("selected")}
			else if(name){var e=card.querySelector('.ds-el[data-el="'+name+'"]');if(e)e.classList.add("selected")}
			buildElList();buildProps();
		}

		function buildElList(){
			var h='<div class="el-row'+(null===_dsSel?' sel':'')+'" data-el=""><div class="dot" style="background:#94a3b8"></div> 卡片</div>';
			[{id:"popup",label:"小窗",color:"#8b5cf6"},{id:"cover",label:"封面",color:"#f59e0b"},{id:"name",label:"歌曲名",color:_ds.name.color},{id:"artist",label:"作者",color:_ds.artist.color},{id:"bpm",label:"BPM",color:_ds.bpm.color},{id:"duration",label:"时长",color:_ds.duration.color},{id:"charters",label:"谱师",color:_ds.charters.color},{id:"tags",label:"标签",color:_ds.tags.color},{id:"heart",label:"爱心",color:_ds.heart.color}].forEach(function(it){
				h+='<div class="el-row'+(it.id===_dsSel?' sel':'')+'" data-el="'+it.id+'"><div class="dot" style="background:'+it.color+'"></div> '+it.label+' '+(it.id==="cover"||it.id==="popup"?"":'<span style="font-size:9px;color:#94a3b8">'+(it.id==="name"?_ds[it.id].font+" "+_ds[it.id].size+"px":_ds[it.id].visible===false?"隐藏":"✓")+'</span>')+'</div>';
			});
			(_ds.shapes||[]).forEach(function(sh,i){
				h+='<div class="el-row'+("shape_"+i===_dsSel?' sel':'')+'" data-el="shape_'+i+'"><div class="dot" style="background:'+(sh.color||"#e2e8f0")+'"></div> 图形'+(i+1)+' <span style="font-size:9px;color:#94a3b8">'+(sh.type||"rect")+(sh.type==="polygon"?"("+sh.vertices.length+"点)":"")+'</span></div>';
			});
			h+='<div class="el-row" style="color:#3b82f6;cursor:pointer" data-el="new_shape">+ 新增图形</div>';
			elList.innerHTML=h;
			elList.querySelectorAll(".el-row").forEach(function(r){r.addEventListener("click",function(){
				if(this.dataset.el==="new_shape"){_ds.shapes.push({type:"rect",x:10,y:10,w:80,h:60,color:"#cbd5e1",opacity:1,zIndex:0,radius:0,vertices:[]});selectEl("shape_"+(+_ds.shapes.length-1))}
				else selectEl(this.dataset.el||null);
			})});
		}

		function buildProps(){
			props.innerHTML="";var n=_dsSel;
			if(n==="popup"){
				props.innerHTML="<h3>小窗文字边界</h3>"
					+pr2("歌名左边距","pp_nameLeft",50,300,_dsPopup.nameLeft||131)+pr2("歌名右边距","pp_nameRight",0,200,_dsPopup.nameRight||20)
					+pr2("作者左边距","pp_artistLeft",50,300,_dsPopup.artistLeft||131)+pr2("作者右边距","pp_artistRight",0,200,_dsPopup.artistRight||20);
			}else if(!n){
				props.innerHTML="<h3>卡片属性</h3>"
					+pr("宽度","card.w",200,1200)+pr("高度","card.h",40,200)
					+pr("PC间距","card.gap",2,40)+pr("手机间距","card.mobileGap",4,150)
					+pr("圆角TL","card.brTL",0,60)+pr("圆角TR","card.brTR",0,60)
					+pr("圆角BL","card.brBL",0,60)+pr("圆角BR","card.brBR",0,60);
			}else if(n==="cover"){
				props.innerHTML="<h3>封面属性</h3>"
					+pch("封面可见","cover.visible")+pr("X位置","cover.x",-40,200)+pr("Y位置","cover.y",-40,80)
					+pr("宽度","cover.w",30,200)+pr("高度","cover.h",30,200)
					+pr("圆角%","cover.radius",0,50)+pr("边框粗细","cover.ringW",0,10)
					+pc("边框颜色","cover.ringColor")+pi("光晕CSS","cover.shadow");
			}else if(n&&n.startsWith("shape_")){
				var idx=parseInt(n.split("_")[1]);var sh=_ds.shapes[idx];if(!sh)return;normShape(sh);
				props.innerHTML="<h3>图形"+(idx+1)+" 属性</h3>"
					+ps3("形状类型","shape_type_"+idx,[["rect","矩形"],["circle","圆形"],["polygon","多边形"]],sh.type||"rect")
					+pn("X位置","shape_x_"+idx,sh.x)+pn("Y位置","shape_y_"+idx,sh.y)
					+pn(sh.type==="circle"?"直径":"宽度","shape_w_"+idx,sh.w)
					+(sh.type==="circle"?"":pn("高度","shape_h_"+idx,sh.h))
					+pc2("填充色","shape_color_"+idx,sh.color||"#e2e8f0")
					+pr2("透明度","shape_opacity_"+idx,0,100,Math.round((sh.opacity||1)*100))
					+(sh.type==="rect"?pr2("圆角","shape_radius_"+idx,0,200,sh.radius||0):"")
					+pr2("层级","shape_z_"+idx,-5,20,sh.zIndex||0);
				if(sh.type==="polygon"&&sh.vertices){
					props.innerHTML+='<div class="ds-prop"><label>顶点 ('+sh.vertices.length+')</label></div>';
					sh.vertices.forEach(function(v,vi){
						props.innerHTML+='<div class="ds-prop"><div class="row" style="gap:4px;margin:1px 0"><span style="font-size:9px;color:#94a3b8;width:20px">#'+(vi+1)+'</span><input type="number" data-sid="shape_vx_'+idx+'_'+vi+'" value="'+Math.round(v.x*100)+'" style="width:45px;font-size:10px" title="X%"><input type="number" data-sid="shape_vy_'+idx+'_'+vi+'" value="'+Math.round(v.y*100)+'" style="width:45px;font-size:10px" title="Y%"><button class="btn secondary" data-sid="shape_delv_'+idx+'_'+vi+'" style="padding:1px 5px;font-size:9px">×</button></div></div>';
					});
					props.innerHTML+='<div class="ds-prop"><button class="btn secondary" data-sid="shape_addv_'+idx+'" style="width:100%;font-size:11px;padding:3px">+ 添加顶点</button></div>';
				}
				props.innerHTML+='<div class="ds-prop"><button class="btn secondary" id="dsDelShape" style="width:100%;font-size:11px;padding:4px">删除此图形</button></div>';
			}else if(n==="heart"){
				props.innerHTML="<h3>爱心 属性（固定贴右侧）</h3>"
					+pch("爱心可见","heart.visible")+pr("距右边距","heart.right",0,400)+pr("距顶","heart.y",-40,180)
					+pr("大小","heart.size",12,60)+pc("颜色","heart.color");
			}else{
				var labels={name:"歌曲名",artist:"作者",bpm:"BPM",duration:"时长",charters:"谱师",tags:"标签"};
				props.innerHTML="<h3>"+labels[n]+" 属性</h3>"
					+pch(n+"可见",n+".visible")+pr("X位置",n+".x",-100,800)+pr("Y位置",n+".y",-40,180)
					+pr("字号",n+".size",8,36)+pc("颜色",n+".color");
				if(n!=="tags"){props.innerHTML+=ps("字体",n+".font");props.innerHTML+=ps2("粗细",n+".weight")}
				else{props.innerHTML+=pc("标签背景色",n+".bg");props.innerHTML+=ps("标签字体",n+".tagFont");props.innerHTML+=pr("标签字号",n+".tagSize",6,20);props.innerHTML+=pc("标签字色",n+".tagColor");props.innerHTML+="<div style=\"font-size:10px;color:#94a3b8;padding:4px 0\">各标签颜色</div>";props.innerHTML+=pc("4K背景",n+".bg4k")+pc("5K背景",n+".bg5k")+pc("6K背景",n+".bg6k")+pc("Catch背景",n+".bgCatch")+pc("4K字色",n+".color4k")+pc("5K字色",n+".color5k")+pc("6K字色",n+".color6k")+pc("Catch字色",n+".colorCatch")}
				props.innerHTML+=pi((n==="tags"?"":"前")+"缀文字",n+".prefix")+pi((n==="tags"?"":"后")+"缀文字",n+".suffix");
			}
			bindPropEvents();
		}

		function getVal(path){var p=path.split(".");var o=_ds;for(var i=1;i<p.length;i++){o=o[p[i]];if(o===undefined)return""}return o}
		function setVal(path,val){var p=path.split(".");var o=_ds;for(var i=1;i<p.length-1;i++)o=o[p[i]];o[p[p.length-1]]=val}

		function pr(label,id,min,max){var path="_ds."+id,v=getVal(path),vid=id.replace(/\./g,"_");return '<div class="ds-prop"><label>'+label+' <span class="val" id="dsv_'+vid+'">'+v+'</span></label><input type="range" data-path="'+path+'" min="'+min+'" max="'+max+'" value="'+v+'"></div>'}
		function pr2(label,id,min,max,val){return '<div class="ds-prop"><label>'+label+' <span class="val" id="dsv_'+id+'">'+val+'</span></label><input type="range" data-sid="'+id+'" min="'+min+'" max="'+max+'" value="'+val+'"></div>'}
		function pn(label,id,val){return '<div class="ds-prop"><div class="row"><label style="flex:1">'+label+'</label><input type="number" data-sid="'+id+'" value="'+val+'" style="width:60px;font-size:11px;border:1px solid #cbd5e1;border-radius:4px;padding:1px 4px;text-align:right"></div></div>'}
		function pc(label,id){return '<div class="ds-prop"><div class="row"><label style="flex:1">'+label+'</label><input type="color" data-path="_ds.'+id+'" value="'+getVal("_ds."+id)+'"></div></div>'}
		function pc2(label,id,val){return '<div class="ds-prop"><div class="row"><label style="flex:1">'+label+'</label><input type="color" data-sid="'+id+'" value="'+val+'"></div></div>'}
		function pi(label,id){return '<div class="ds-prop"><label>'+label+'</label><input type="text" data-path="_ds.'+id+'" value="'+String(getVal("_ds."+id)).replace(/"/g,"&quot;")+'" style="width:100%;font-size:11px;border:1px solid #cbd5e1;border-radius:4px;padding:2px 4px"></div>'}
		function ps(label,id){var h="<div class='ds-prop'><label>"+label+"</label><select data-path='_ds."+id+"'><option value='默认'>默认</option>";_dsFonts.forEach(function(f){h+="<option value='"+f.name+"'>"+f.name+"</option>"});h+="</select></div>";return h}
		function ps2(label,id){var h="<div class='ds-prop'><label>"+label+"</label><select data-path='_ds."+id+"'>";[["normal","正常"],["bold","粗体"],["300","细体"]].forEach(function(o){h+="<option value='"+o[0]+"'>"+o[1]+"</option>"});h+="</select></div>";return h}
		function ps3(label,id,opts,val){var h="<div class='ds-prop'><label>"+label+"</label><select data-sid='"+id+"'>";opts.forEach(function(o){h+="<option value='"+o[0]+"'"+(o[0]===val?" selected":"")+">"+o[1]+"</option>"});h+="</select></div>";return h}
		function pch(label,id){var name=id.split(".")[0];return '<div class="ds-prop"><label style="display:flex;align-items:center;gap:6px;cursor:pointer"><input type="checkbox" data-path="_ds.'+id+'" '+(getVal("_ds."+name+".visible")!==false?"checked":"")+'> '+label+'</label></div>'}


		function bindPropEvents(){
			props.querySelectorAll("input[type=range]").forEach(function(el){
				var path=el.dataset.path,sid=el.dataset.sid;
				if(path){
					var vid=document.getElementById("dsv_"+path.replace("_ds.","").replace(/\./g,"_"));
					if(vid)vid.textContent=getVal(path);
					el.addEventListener("input",function(){setVal(path,parseInt(this.value));if(vid)vid.textContent=this.value;buildPreview()});
				}else if(sid){
					el.addEventListener("input",function(){handleShapeProp(sid,parseInt(this.value));buildPreview()});
				}
			});
			props.querySelectorAll("input[type=number]").forEach(function(el){
				el.addEventListener("input",function(){handleShapeProp(this.dataset.sid,parseInt(this.value)||0);buildPreview()});
			});
			props.querySelectorAll("input[type=color]").forEach(function(el){
				if(el.dataset.path)el.addEventListener("input",function(){setVal(this.dataset.path,this.value);buildPreview()});
				else if(el.dataset.sid)el.addEventListener("input",function(){handleShapeProp(this.dataset.sid,this.value);buildPreview()});
			});
			props.querySelectorAll("input[type=text]").forEach(function(el){
				el.addEventListener("input",function(){setVal(this.dataset.path,this.value);buildPreview()});
			});
			props.querySelectorAll("select").forEach(function(el){
				if(el.dataset.path)el.addEventListener("change",function(){setVal(this.dataset.path,this.value);buildPreview()});
				else if(el.dataset.sid)el.addEventListener("change",function(){handleShapeProp(this.dataset.sid,this.value);buildPreview()});
			});
			props.querySelectorAll("input[type=checkbox]").forEach(function(el){
				el.addEventListener("change",function(){setVal(this.dataset.path,this.checked);buildPreview()});
			});
			var db=document.getElementById("dsDelShape");if(db)db.addEventListener("click",function(){
				var m=_dsSel.match(/^shape_(\d+)$/);if(m){_ds.shapes.splice(parseInt(m[1]),1);selectEl(null)}
			});
			// Vertex delete buttons
			props.querySelectorAll("button[data-sid*='shape_delv']").forEach(function(btn){
				btn.addEventListener("click",function(){
					var m=this.dataset.sid.match(/^shape_delv_(\d+)_(\d+)$/);if(!m)return;
					var si=parseInt(m[1]),vi=parseInt(m[2]);
					if(_ds.shapes[si]&&_ds.shapes[si].vertices.length>3){_ds.shapes[si].vertices.splice(vi,1);buildPreview();buildProps()}
				});
			});
			// Vertex add button
			props.querySelectorAll("button[data-sid*='shape_addv']").forEach(function(btn){
				btn.addEventListener("click",function(){
					var m=this.dataset.sid.match(/^shape_addv_(\d+)$/);if(!m)return;
					var si=parseInt(m[1]),sh=_ds.shapes[si];if(!sh)return;
					var vs=sh.vertices;if(vs.length<2)return;
					// Find longest edge and add vertex at midpoint
					var maxD=0,mi=0;
					for(var i=0;i<vs.length;i++){var a=vs[i],b=vs[(i+1)%vs.length];var d=Math.pow(b.x-a.x,2)+Math.pow(b.y-a.y,2);if(d>maxD){maxD=d;mi=i}}
					var a=vs[mi],b=vs[(mi+1)%vs.length];
					vs.splice(mi+1,0,{x:(a.x+b.x)/2,y:(a.y+b.y)/2});
					buildPreview();buildProps();
				});
			});
		}

		function handleShapeProp(sid,val){
			var m=sid.match(/^shape_(.+)_(\d+)$/);if(!m)return;
			// Handle vertex sub-properties: shape_vx_0_1
			var m2=sid.match(/^shape_v([xy])_(\d+)_(\d+)$/);
			if(m2){
				var si=parseInt(m2[2]),vi=parseInt(m2[3]);
				if(_ds.shapes[si]&&_ds.shapes[si].vertices&&_ds.shapes[si].vertices[vi]){
					_ds.shapes[si].vertices[vi][m2[1]]=val/100;
				}
				return;
			}
			var key=m[1],idx=parseInt(m[2]);if(!_ds.shapes[idx])return;
			var sh=_ds.shapes[idx];
			if(key==="opacity")sh[key]=val/100;
			else if(key==="x"||key==="y"||key==="w"||key==="h"||key==="z"||key==="radius")sh[key]=key==="w"||key==="h"?Math.max(1,parseInt(val)||0):parseInt(val)||0;
			else sh[key]=val;
			if(key==="type"&&val==="circle"){sh.h=sh.w;sh.vertices=[]}
			if(key==="type"&&val==="polygon"&&(!sh.vertices||sh.vertices.length<3)){sh.vertices=[{x:0,y:0},{x:1,y:0},{x:0.5,y:1}]}
			if(key==="type"&&val!=="polygon")sh.vertices=[];

			// 小窗边界
			props.querySelectorAll('[data-sid^="pp_"]').forEach(function(el){
				el.addEventListener('input',function(){
					var k=this.dataset.sid.replace('pp_','');
					_dsPopup[k]=parseInt(this.value)||0;
				})
			});
		}


		var _dsPreviewQueued=false;
		function queuePreview(){
			if(_dsPreviewQueued)return;
			_dsPreviewQueued=true;
			requestAnimationFrame(function(){_dsPreviewQueued=false;buildPreview();});
		}
		function startDrag(e,type){
			e.preventDefault();
			if(e.currentTarget&&e.currentTarget.setPointerCapture&&e.pointerId!==undefined){try{e.currentTarget.setPointerCapture(e.pointerId)}catch(ignore){}}
			_dsDragType=type;_dsStartX=e.clientX;_dsStartY=e.clientY;
			if(type==="move_el"&&_dsSel){_dsOrig.x=getVal("_ds."+_dsSel+".x");_dsOrig.y=getVal("_ds."+_dsSel+".y")}
			else if(type==="move_cover"){_dsOrig.x=_ds.cover.x;_dsOrig.y=_ds.cover.y}
			else if(type==="move_shape"&&_dsSel){
				var m=_dsSel.match(/^shape_(\d+)$/);if(m){var sh=_ds.shapes[parseInt(m[1])];_dsOrig.x=sh.x;_dsOrig.y=sh.y}
			}else if(type.startsWith("move_vertex_")){
				var p=type.split("_");var si=parseInt(p[2]),vi=parseInt(p[3]);
				if(_ds.shapes[si]&&_ds.shapes[si].vertices&&_ds.shapes[si].vertices[vi]){
					_dsOrig.vx=_ds.shapes[si].vertices[vi].x;
					_dsOrig.vy=_ds.shapes[si].vertices[vi].y;
				}
			}else if(type.startsWith("corner_")){var c=type.split("_")[1];["TL","TR","BL","BR"].forEach(function(x){_dsOrig["br"+x]=_ds.card["br"+x]})}
		}

		document.addEventListener("pointermove",function(e){
			if(!_dsDragType)return;
			e.preventDefault();
			var dx=e.clientX-_dsStartX,dy=e.clientY-_dsStartY;
			if(_dsDragType&&_dsDragType.startsWith("pp_")){
				var dx=e.clientX-_dsStartX;
				var parts=_dsDragType.split("_"),id=parts[1]+"_"+parts[2],side=parts[3];
				var el=document.getElementById(id),nv=_dsOrig.x+(side==="left"?dx:-dx);
				nv=Math.max(0,Math.min(400,nv));if(el)el.style[side]=nv+"px";
				if(side==="left")_dsPopup[(id==="ppName"?"nameLeft":"artistLeft")]=nv;
				else _dsPopup[(id==="ppName"?"nameRight":"artistRight")]=nv;
				buildProps()
			}else if(_dsDragType==="move_el"){setVal("_ds."+_dsSel+".x",Math.round(_dsOrig.x+dx));setVal("_ds."+_dsSel+".y",Math.round(_dsOrig.y+dy))}
			else if(_dsDragType==="move_cover"){_ds.cover.x=Math.round(_dsOrig.x+dx);_ds.cover.y=Math.round(_dsOrig.y+dy)}
			else if(_dsDragType==="move_shape"){var m=_dsSel.match(/^shape_(\d+)$/);if(m){var sh=_ds.shapes[parseInt(m[1])];sh.x=Math.round(_dsOrig.x+dx);sh.y=Math.round(_dsOrig.y+dy)}}
			else if(_dsDragType.startsWith("move_vertex_")){
				var p=_dsDragType.split("_");var si=parseInt(p[2]),vi=parseInt(p[3]),sh=_ds.shapes[si];
				if(sh&&sh.vertices&&sh.vertices[vi]){
					var nvx=_dsOrig.vx+dx/sh.w,nvy=_dsOrig.vy+dy/sh.h;
					nvx=Math.max(0,Math.min(1,nvx));nvy=Math.max(0,Math.min(1,nvy));
					sh.vertices[vi].x=Math.round(nvx*100)/100;sh.vertices[vi].y=Math.round(nvy*100)/100;
				}
			}
			else if(_dsDragType.startsWith("corner_")){var c=_dsDragType.split("_")[1],nv=_dsOrig["br"+c]+Math.round((Math.abs(dx)>Math.abs(dy)?dx:dy)/2);nv=Math.max(0,Math.min(60,nv));_ds.card["br"+c]=nv}
			queuePreview();
		},{passive:false});
		document.addEventListener("pointerup",function(){_dsDragType=""});
		document.addEventListener("pointercancel",function(){_dsDragType=""});

		var _loadedFonts={};
		function loadFont(name){if(_loadedFonts[name]||name==="默认")return;_loadedFonts[name]=true;var f=_dsFonts.find(function(x){return x.name===name});if(!f)return;var s=document.createElement("style");s.textContent="@font-face{font-family:'"+name+"';src:url('"+API_BASE+"/api/fonts/file?"+f.file+"') format('"+(f.file.endsWith(".ttf")?"truetype":"opentype")+"');font-display:swap}";document.head.appendChild(s)}
		var _origBP=buildProps;buildProps=function(){_origBP();if(_dsSel&&_dsSel!=="cover"&&!_dsSel.startsWith("shape_")&&_dsSel!=="tags"){var fn=getVal("_ds."+_dsSel+".font");if(fn&&fn!=="默认")loadFont(fn)}if(_dsSel==="tags"){var tfn=getVal("_ds.tags.tagFont");if(tfn&&tfn!=="默认")loadFont(tfn)}};

		function dsToast(msg){var t=document.getElementById("dsToast");t.textContent=msg;t.classList.add("show");setTimeout(function(){t.classList.remove("show")},2000)}

		document.getElementById("dsSave").addEventListener("click",function(){
			if(_dsSaving)return;_dsSaving=true;
			var btn=this;btn.disabled=true;btn.textContent="保存中...";
			var css="";
			css+=".lib-card{position:relative;background:"+_ds.card.bg+";border-radius:"+_ds.card.brTL+"px "+_ds.card.brTR+"px "+_ds.card.brBR+"px "+_ds.card.brBL+"px;border:1px solid #e2e8f0;padding:0;display:block;cursor:pointer;transition:all .15s;min-height:"+_ds.card.h+"px}\n";
			css+=".lib-card:hover{box-shadow:0 4px 12px rgba(0,0,0,.08)}\n";
			css+=".lib-grid{gap:"+_ds.card.gap+"px}";css+="@media(max-width:768px){.lib-grid{gap:"+(_ds.card.mobileGap||_ds.card.gap)+"px}}";
			css+=".lib-card .cv{position:absolute;left:"+_ds.cover.x+"px;top:"+_ds.cover.y+"px;width:"+_ds.cover.w+"px;height:"+_ds.cover.h+"px;object-fit:cover;border-radius:"+_ds.cover.radius+"%;border:"+_ds.cover.ringW+"px solid "+_ds.cover.ringColor+";box-shadow:"+_ds.cover.shadow+";flex-shrink:0;background:#fff"+(typeof _ds.cover.visible==="boolean"&&!_ds.cover.visible?";display:none":"")+"}\n";
			["name","artist","bpm","duration","charters","tags"].forEach(function(t){
				var cls=t.substring(0,2);var f=_ds[t].font==="默认"?"inherit":"'"+_ds[t].font+"'";
				css+=".lib-card ."+cls+"{position:absolute;left:"+_ds[t].x+"px;top:"+_ds[t].y+"px;font-family:"+f+";font-size:"+_ds[t].size+"px;color:"+_ds[t].color+";line-height:1.4;font-weight:"+(_ds[t].weight||"normal")+";white-space:nowrap;overflow:hidden;text-overflow:clip";
				if(t==="tags")css+=";display:flex;gap:5px;flex-wrap:wrap;background:transparent;padding:0;overflow:visible;white-space:normal";
				if(_ds[t].visible===false)css+=";display:none";
				css+="}\n";
			});
			var tf=_ds.tags.tagFont==="默认"?"inherit":"'"+_ds.tags.tagFont+"'";
			css+=".lib-card .ta span{font-family:"+tf+";font-size:"+_ds.tags.tagSize+"px;color:"+_ds.tags.tagColor+"}\n";
			(_ds.shapes||[]).forEach(function(sh,i){
				normShape(sh);
				css+=".lib-card .sh"+i+"{"+shapeBoxCss(sh)+"background:"+(sh.color||"#e2e8f0")+";opacity:"+(sh.opacity||1)+";z-index:"+(sh.zIndex||0);
				if(sh.type==="circle")css+=";border-radius:50%";
				else if(sh.type==="rect"&&sh.radius)css+=";border-radius:"+sh.radius+"px";
				else if(sh.type==="polygon"&&sh.vertices&&sh.vertices.length>=3){
					var pts=sh.vertices.map(function(v){return Math.round(v.x*sh.w)+"px "+Math.round(v.y*sh.h)+"px"}).join(",");
					css+=";clip-path:polygon("+pts+")";
				}
				css+="}\n";
			});
			// 卡片爱心（位置/大小/颜色，liked 态由静态 .heart.liked 覆盖为红色）
			css+=".lib-card .heart{position:absolute;right:"+_ds.heart.right+"px;left:auto;top:"+_ds.heart.y+"px;z-index:5;cursor:pointer;display:flex;flex-direction:column;align-items:center;line-height:1;font-size:"+_ds.heart.size+"px;--heart-color:"+_ds.heart.color+";color:var(--heart-color)"+(_ds.heart.visible===false?";display:none":"")+"}\n";
			// 弹窗基础（必须包含，否则 .show 无效）
			css+=".lib-detail{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(15,23,42,.5);z-index:1000;justify-content:center;align-items:flex-start;padding-top:32px;overflow-y:auto}\n";
			css+=".lib-detail.show{display:flex}\n";
			css+=".ld-diffs{display:flex;gap:6px;flex-wrap:wrap;margin-top:4px}\n";
			css+=".ld-mode{margin-bottom:10px}\n";
			css+=".ld-close{margin-top:14px;width:100%;padding:8px;border:1px solid #cbd5e1;border-radius:8px;background:rgba(255,255,255,0.6);cursor:pointer;font-size:13px;font-family:inherit;color:#475569}\n";
			css+=".ld-close:hover{background:rgba(255,255,255,0.9)}\n";
			// 小窗CSS
			var pp=_dsPopup;
			var _naF=pp.naFont==='默认'?'inherit':"'"+pp.naFont+"'";
			var _arF=pp.arFont==='默认'?'inherit':"'"+pp.arFont+"'";
			var _chF=pp.chFont==='默认'?'inherit':"'"+pp.chFont+"'";
			var _tgF=pp.tagFont==='默认'?'inherit':"'"+pp.tagFont+"'";
			var _dfF=pp.diffFont==='默认'?"'方正粗圆简体',inherit":"'"+pp.diffFont+"'";
         css+='.lib-detail .dd{background:'+pp.bg+';border-radius:'+pp.borderRadius+'px;padding:'+pp.padding+'px;width:'+pp.width+'px;max-width:96vw;box-shadow:0 20px 60px rgba(0,0,0,.2);position:relative;z-index:0;isolation:isolate;overflow:hidden;transition:background .4s}\n';
         css+='.ld-blur-bg{position:absolute;inset:0;background-size:cover;background-position:center;filter:blur(5px) brightness(0.65);opacity:0.35;z-index:0;pointer-events:none;border-radius:5px}.ld-blur-bg::after{content:"";position:absolute;inset:0;background:rgba(255,255,255,0.7)}\n';
         css+='.lib-detail .dd>:not(.ld-blur-bg){position:relative;z-index:1}\n';
				css+='.ld-header{position:relative;height:'+pp.headerH+'px;margin-bottom:16px}\n';
			css+='.ld-cover{position:absolute;left:'+pp.cvX+'px;top:'+pp.cvY+'px;width:'+pp.cvW+'px;height:'+pp.cvH+'px;border-radius:'+pp.cvRadius+'px;object-fit:cover;background:#f1f5f9}\n';
			css+='.ld-name{position:absolute;left:'+pp.naX+'px;top:'+pp.naY+'px;right:0;font-family:'+_naF+';font-size:'+pp.naSz+'px;color:'+pp.naColor+';font-weight:'+pp.naWeight+';line-height:1.35;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}\n';
			css+='.ld-artist{position:absolute;left:'+pp.arX+'px;top:'+pp.arY+'px;right:0;font-family:'+_arF+';font-size:'+pp.arSz+'px;color:'+pp.arColor+';overflow:hidden;white-space:nowrap}\n';
			css+='.ld-charter{position:absolute;left:'+pp.chX+'px;top:'+pp.chY+'px;right:0;font-family:'+_chF+';font-size:'+pp.chSz+'px;color:'+pp.chColor+';overflow:hidden;white-space:nowrap}\n';
			css+='.ld-mode-tag{font-family:'+_tgF+';font-size:'+pp.tagSz+'px;font-weight:700;padding:2px 8px;border-radius:'+pp.tagRadius+'px;display:inline-block;margin-bottom:5px}\n';
			css+='.ld-tag-4k{background:'+pp.tag4kBg+';color:'+pp.tag4kColor+'}\n';
			css+='.ld-tag-5k{background:'+pp.tag5kBg+';color:'+pp.tag5kColor+'}\n';
			css+='.ld-tag-6k{background:'+pp.tag6kBg+';color:'+pp.tag6kColor+'}\n';
			css+='.ld-tag-catch{background:'+pp.tagCatchBg+';color:'+pp.tagCatchColor+'}\n';
			css+='.ld-diff{font-family:'+_dfF+';font-size:'+pp.diffSz+'px;font-weight:700;padding:3px 12px;border-radius:'+pp.diffRadius+'px;white-space:nowrap}\n';
			css+='.ld-easy{background:'+pp.easyBg+';color:'+pp.easyColor+'}\n';
			css+='.ld-normal{background:'+pp.normalBg+';color:'+pp.normalColor+'}\n';
			css+='.ld-hard{background:'+pp.hardBg+';color:'+pp.hardColor+'}\n';
			var pgrad='linear-gradient(90deg,'+pp.g1+','+pp.g2+','+pp.g3+','+pp.g4+','+pp.g5+')';
			css+='.ld-master,.ld-catch{background:'+pgrad+';color:'+pp.masterColor+'}\n';
			(pp.shapes||[]).forEach(function(sh,i){
				css+='.ld-psh'+i+'{position:absolute;left:'+sh.x+'px;top:'+sh.y+'px;width:'+sh.w+'px;height:'+sh.h+'px;background:'+(sh.color||'#e2e8f0')+';opacity:'+(sh.opacity||1)+';z-index:'+(sh.zIndex||0)+';pointer-events:none';
				if(sh.type==='circle')css+=';border-radius:50%';
				else if(sh.type==='rect'&&sh.radius)css+=';border-radius:'+sh.radius+'px';
				css+='}\n';
			});
			var cfg={card:_ds.card,cover:_ds.cover,name:_ds.name,artist:_ds.artist,bpm:_ds.bpm,duration:_ds.duration,charters:_ds.charters,tags:_ds.tags,heart:_ds.heart,shapes:_ds.shapes,popup:_dsPopup,filters:_dsFilters};
			fetch(API,{method:"POST",headers:adminHeaders({"Content-Type":"application/json"}),body:JSON.stringify({css:css,maxWidth:_ds.card.w+40,config:JSON.stringify(cfg)})}).then(function(r){
				if(r.ok){dsToast("已注入! 刷新曲库页查看效果")}else{dsToast("保存失败")}
				btn.disabled=false;btn.textContent="注入保存";_dsSaving=false;
			}).catch(function(){dsToast("保存失败");btn.disabled=false;btn.textContent="注入保存";_dsSaving=false});
		});

		document.getElementById("dsReset").addEventListener("click",function(){location.reload()});
		document.querySelector('[data-tab="design"]').addEventListener("click",function(){if(_dsMode==='popup'){buildPopupPreview();buildPopupElList();buildPopupProps()}else if(_dsMode==='filters'){buildFiltersPreview();buildFiltersElList();buildFiltersProps()}else{buildPreview();buildElList();buildProps()}});

		// ======== 小窗可视化设计器 ========
		var _dsMode='card',_dsPSel=null,_pDragType='',_pDragTarget=null,_pDX=0,_pDY=0,_pDO={};
		var _dsPopup={
			bg:'#ffffff',borderRadius:16,width:500,padding:24,useCoverBg:true,
			headerH:100,
			cvX:0,cvY:0,cvW:82,cvH:82,cvRadius:10,
			naX:96,naY:4,naRight:350,naFont:'默认',naSz:16,naColor:'#1e293b',naWeight:'700',
			arX:96,arY:28,arRight:350,arFont:'默认',arSz:13,arColor:'#475569',
			chX:96,chY:50,chFont:'默认',chSz:12,chColor:'#94a3b8',
			tagFont:'默认',tagSz:11,tagRadius:6,
			tag4kBg:'#dbeafe',tag4kColor:'#1e40af',
			tag5kBg:'#dcfce7',tag5kColor:'#15803d',
			tag6kBg:'#fef9c3',tag6kColor:'#854d0e',
			tagCatchBg:'#fce7f3',tagCatchColor:'#9d174d',
			diffFont:'方正粗圆简体',diffSz:12,diffRadius:100,
			easyBg:'#e6fae6',easyColor:'#006400',
			normalBg:'#fffad2',normalColor:'#826400',
			hardBg:'#ffe1e1',hardColor:'#b40000',
			masterColor:'#503264',
			g1:'#ffd7d7',g2:'#fff5c3',g3:'#d7ffd7',g4:'#d7f5ff',g5:'#ebd7ff',
			heart:{hRight:20,hY:8,hSize:26,hColor:'#cbd5e1',hVisible:true},
			shapes:[]
		};
		(function(){try{var dc=document.getElementById('dsConfig');if(!dc)return;var cfg=JSON.parse(dc.textContent);if(cfg.popup){var s=cfg.popup;for(var k in s){if(k!=='shapes')_dsPopup[k]=s[k]}if(s.shapes)_dsPopup.shapes=s.shapes}}catch(e){}})();
		// 兼容旧配置：确保小窗爱心字段齐全（旧版可能存的是 hX）
		if(!_dsPopup.heart||typeof _dsPopup.heart!=='object')_dsPopup.heart={};
		if(_dsPopup.heart.hRight==null)_dsPopup.heart.hRight=20;
		if(_dsPopup.heart.hY==null)_dsPopup.heart.hY=8;
		if(_dsPopup.heart.hSize==null)_dsPopup.heart.hSize=26;
		if(_dsPopup.heart.hColor==null)_dsPopup.heart.hColor='#cbd5e1';
		if(_dsPopup.heart.hVisible==null)_dsPopup.heart.hVisible=true;
		window._dsPopup=_dsPopup; // 暴露给 showSongDetail(它在 initDesigner2 IIFE 之外)，否则读不到实时编辑值

		function _pElCoords(id){var p=_dsPopup;if(id==='cover')return{x:p.cvX,y:p.cvY};if(id==='name')return{x:p.naX,y:p.naY};if(id==='artist')return{x:p.arX,y:p.arY};if(id==='charter')return{x:p.chX,y:p.chY};return{x:0,y:0}}
		function _pElSetCoords(id,x,y){var p=_dsPopup;if(id==='cover'){p.cvX=x;p.cvY=y}else if(id==='name'){p.naX=x;p.naY=y}else if(id==='artist'){p.arX=x;p.arY=y}else if(id==='charter'){p.chX=x;p.chY=y}}

		function buildPopupPreview(){
			document.getElementById('dsCard').style.display='none';
			var pEl=document.getElementById('dsPCard'),p=_dsPopup;
			pEl.style.cssText='display:block;width:'+p.width+'px;background:'+p.bg+';border-radius:'+p.borderRadius+'px;padding:'+p.padding+'px;box-shadow:0 20px 60px rgba(0,0,0,.18);position:relative;overflow:hidden;cursor:default;user-select:none;margin:0 auto';
			var grad='linear-gradient(90deg,'+p.g1+','+p.g2+','+p.g3+','+p.g4+','+p.g5+')';
			var dff=p.diffFont==='默认'?"'方正粗圆简体',inherit":"'"+p.diffFont+"'";
			var html='';
			// BG shapes
			(p.shapes||[]).forEach(function(sh,i){
				normShape(sh);
				html+='<div data-psh="'+i+'" style="position:absolute;left:'+sh.x+'px;top:'+sh.y+'px;width:'+sh.w+'px;height:'+sh.h+'px;background:'+(sh.color||'#e2e8f0')+';opacity:'+(sh.opacity||1)+';z-index:'+(sh.zIndex||0)+';cursor:move'+(_dsPSel===i?';outline:2px dashed #3b82f6;outline-offset:2px':'');
				if(sh.type==='circle')html+=';border-radius:50%';
				else if(sh.type==='rect'&&sh.radius)html+=';border-radius:'+sh.radius+'px';
				html+='"></div>';
			});
			// Header: absolutely positioned draggable elements
			html+='<div style="position:relative;z-index:1;height:'+p.headerH+'px;margin-bottom:16px">';
			var cvSel=_dsPSel==='cover';
			html+='<div data-pdrag="cover" style="position:absolute;left:'+p.cvX+'px;top:'+p.cvY+'px;width:'+p.cvW+'px;height:'+p.cvH+'px;border-radius:'+p.cvRadius+'px;background:#c7d2fe;cursor:move'+(cvSel?';outline:2px dashed #f59e0b;outline-offset:2px':'')+'"></div>';
			var naFont=p.naFont==='默认'?'inherit':"'"+p.naFont+"'";
			html+='<div data-pdrag="name" style="position:absolute;left:'+p.naX+'px;width:'+(p.naRight-p.naX)+'px;top:'+p.naY+'px;font-family:'+naFont+';font-size:'+p.naSz+'px;color:'+p.naColor+';font-weight:'+p.naWeight+';cursor:move;white-space:nowrap;line-height:1.35'+(_dsPSel==='name'?';outline:2px dashed #3b82f6;outline-offset:2px':'')+'">踏上旅途 (Take the Journey)</div>';
			var arFont=p.arFont==='默认'?'inherit':"'"+p.arFont+"'";
			html+='<div data-pdrag="artist" style="position:absolute;left:'+p.arX+'px;width:'+(p.arRight-p.arX)+'px;top:'+p.arY+'px;font-family:'+arFont+';font-size:'+p.arSz+'px;color:'+p.arColor+';cursor:move;white-space:nowrap'+(_dsPSel==='artist'?';outline:2px dashed #3b82f6;outline-offset:2px':'')+'">HOYO-MiX / Anthony Lynch</div>';
			var chFont=p.chFont==='默认'?'inherit':"'"+p.chFont+"'";
			html+='<div data-pdrag="charter" style="position:absolute;left:'+p.chX+'px;top:'+p.chY+'px;font-family:'+chFont+';font-size:'+p.chSz+'px;color:'+p.chColor+';cursor:move;white-space:nowrap'+(_dsPSel==='charter'?';outline:2px dashed #3b82f6;outline-offset:2px':'')+'">谱面：Furina</div>';
			var hh=p.heart||{};
			if(hh.hVisible!==false)html+='<div style="position:absolute;right:'+hh.hRight+'px;left:auto;top:'+hh.hY+'px;font-size:'+hh.hSize+'px;color:'+hh.hColor+';display:flex;flex-direction:column;align-items:center;line-height:1'+(_dsPSel==='heart'?';outline:2px dashed #3b82f6;outline-offset:2px':'')+'">'+_HEART_SVG+'<span style="font-size:12px;color:#64748b">0</span></div>';
			html+='</div>';
			// Charts
			html+='<div style="position:relative;z-index:1">';
			function tagSt(mode){
				var t={'4K':{bg:p.tag4kBg,cl:p.tag4kColor},'5K':{bg:p.tag5kBg,cl:p.tag5kColor},'6K':{bg:p.tag6kBg,cl:p.tag6kColor},'Catch':{bg:p.tagCatchBg,cl:p.tagCatchColor}}[mode]||{bg:'#f1f5f9',cl:'#64748b'};
				var tff=p.tagFont==='默认'?'inherit':"'"+p.tagFont+"'";
				return'background:'+t.bg+';color:'+t.cl+';font-family:'+tff+';font-size:'+p.tagSz+'px;font-weight:700;padding:2px 8px;border-radius:'+p.tagRadius+'px;display:inline-block;margin-bottom:5px';
			}
			function dSpan(t,bg,cl){return'<span style="font-family:'+dff+';font-size:'+p.diffSz+'px;font-weight:700;padding:3px 12px;border-radius:'+p.diffRadius+'px;background:'+bg+';color:'+cl+';white-space:nowrap">'+t+'</span>'}
			html+='<div style="margin-bottom:10px"><span style="'+tagSt('4K')+'">4K</span><div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:4px">';
			html+=dSpan('简单 Lv.4  键数：436',p.easyBg,p.easyColor)+dSpan('普通 Lv.6  键数：697',p.normalBg,p.normalColor)+dSpan('困难 Lv.8  键数：958',p.hardBg,p.hardColor)+dSpan('大师 Lv.12  键数：1580',grad,p.masterColor);
			html+='</div></div>';
			html+='<div style="margin-bottom:10px"><span style="'+tagSt('Catch')+'">Catch</span><div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:4px">';
			html+=dSpan('Salad Lv.4',grad,p.masterColor)+dSpan('Rain Lv.15',grad,p.masterColor);
			html+='</div></div></div>';
			html+='<button style="margin-top:14px;width:100%;padding:8px;border:1px solid #cbd5e1;border-radius:8px;background:rgba(255,255,255,0.6);cursor:default;font-size:13px;font-family:inherit;color:#475569;position:relative;z-index:1">关闭</button>';
			pEl.innerHTML=html;
			// Shape drag
			pEl.querySelectorAll('[data-psh]').forEach(function(el){
				el.addEventListener('pointerdown',function(e){
					e.stopPropagation();var i=parseInt(el.dataset.psh);
					_dsPSel=i;_pDragType='shape';_pDragTarget=i;_pDX=e.clientX;_pDY=e.clientY;
					_pDO={x:_dsPopup.shapes[i].x,y:_dsPopup.shapes[i].y};
					if(e.currentTarget&&e.currentTarget.setPointerCapture&&e.pointerId!==undefined){try{e.currentTarget.setPointerCapture(e.pointerId)}catch(ignore){}}
					e.preventDefault();buildPopupElList();buildPopupProps();
				});
			});
			// Element drag (header elements)
			pEl.querySelectorAll('[data-pdrag]').forEach(function(el){
				el.addEventListener('pointerdown',function(e){
					e.stopPropagation();var id=el.dataset.pdrag;
					_dsPSel=id;_pDragType='el';_pDragTarget=id;_pDX=e.clientX;_pDY=e.clientY;
					_pDO=_pElCoords(id);
					if(e.currentTarget&&e.currentTarget.setPointerCapture&&e.pointerId!==undefined){try{e.currentTarget.setPointerCapture(e.pointerId)}catch(ignore){}}
					e.preventDefault();buildPopupElList();buildPopupProps();
				});
			});
			pEl.addEventListener('pointerdown',function(e){if(e.target===pEl){_dsPSel=null;_pDragType='';buildPopupElList();buildPopupProps()}});
		}

		var _pPreviewQueued=false;
		function queuePopupPreview(){
			if(_pPreviewQueued)return;
			_pPreviewQueued=true;
			requestAnimationFrame(function(){_pPreviewQueued=false;buildPopupPreview()});
		}
		document.addEventListener('pointermove',function(e){
			if(!_pDragType)return;
			e.preventDefault();
			var dx=e.clientX-_pDX,dy=e.clientY-_pDY;
			if(_pDragType==='el'){_pElSetCoords(_pDragTarget,Math.round(_pDO.x+dx),Math.round(_pDO.y+dy))}
			else if(_pDragType==='shape'){var sh=_dsPopup.shapes[_pDragTarget];if(!sh)return;sh.x=Math.round(_pDO.x+dx);sh.y=Math.round(_pDO.y+dy)}
			queuePopupPreview();
		},{passive:false});
		document.addEventListener('pointerup',function(){_pDragType='';_pDragTarget=null});
		document.addEventListener('pointercancel',function(){_pDragType='';_pDragTarget=null});

		function ppGet(path){var a=path.split('.');var o=_dsPopup;for(var i=1;i<a.length;i++){if(o==null)return'';o=o[a[i]]}return o===undefined?'':o}
		function ppSet(path,v){var a=path.split('.');var o=_dsPopup;for(var i=1;i<a.length-1;i++)o=o[a[i]];o[a[a.length-1]]=v}
		function ppr2(lbl,id,type,mn,mx){var v=ppGet(id),vid=id.replace(/\./g,'_');if(type==='color')return'<div class="ds-prop"><div class="row"><label style="flex:1">'+lbl+'</label><input type="color" data-ppath="'+id+'" value="'+v+'"></div></div>';return'<div class="ds-prop"><label>'+lbl+' <span class="val" id="ppv_'+vid+'">'+v+'</span></label><input type="range" data-ppath="'+id+'" min="'+mn+'" max="'+mx+'" value="'+v+'"></div>'}
		function pprFont2(lbl,id){var v=ppGet(id);var h="<div class='ds-prop'><label>"+lbl+"</label><select data-ppath='"+id+"'><option value='默认'>默认</option>";_dsFonts.forEach(function(f){h+="<option value='"+f.name+"'"+(v===f.name?' selected':'')+">"+f.name+"</option>"});return h+"</select></div>"}
		function pprW2(lbl,id){var v=ppGet(id);var h="<div class='ds-prop'><label>"+lbl+"</label><select data-ppath='"+id+"'>";[['700','粗体'],['normal','正常'],['300','细体']].forEach(function(o){h+="<option value='"+o[0]+"'"+(v===o[0]?' selected':'')+">"+o[1]+"</option>"});return h+"</select></div>"}

		function buildPopupElList(){
			var sel=_dsPSel;
			function row(id,lbl,dot){return'<div class="el-row'+(sel===id?' sel':'')+'" data-pel="'+id+'"><div class="dot" style="'+dot+'"></div> '+lbl+'</div>'}
			var h=row('container','弹窗容器','background:#3b82f6')
				+row('header','标题区高度','background:#8b5cf6')
				+row('cover','封面','background:#f59e0b')
				+row('name','曲名','background:'+_dsPopup.naColor)
				+row('artist','作者','background:'+_dsPopup.arColor)
				+row('charter','谱师','background:'+_dsPopup.chColor)
				+row('heart','爱心','background:#ef4444')
				+row('tagStyle','标签通用字体','background:#64748b')
				+row('tag4k','4K标签','background:'+_dsPopup.tag4kBg+';border:1px solid '+_dsPopup.tag4kColor)
				+row('tag5k','5K标签','background:'+_dsPopup.tag5kBg+';border:1px solid '+_dsPopup.tag5kColor)
				+row('tag6k','6K标签','background:'+_dsPopup.tag6kBg+';border:1px solid '+_dsPopup.tag6kColor)
				+row('tagCatch','Catch标签','background:'+_dsPopup.tagCatchBg+';border:1px solid '+_dsPopup.tagCatchColor)
				+row('diff','难度徽章通用','background:#a855f7')
				+row('easy','简单','background:'+_dsPopup.easyBg+';border:1px solid '+_dsPopup.easyColor)
				+row('normal','普通','background:'+_dsPopup.normalBg+';border:1px solid '+_dsPopup.normalColor)
				+row('hard','困难','background:'+_dsPopup.hardBg+';border:1px solid '+_dsPopup.hardColor)
				+row('master','大师/Catch','background:linear-gradient(90deg,#ffd7d7,#ebd7ff)');
			(_dsPopup.shapes||[]).forEach(function(sh,i){h+='<div class="el-row'+(sel===i?' sel':'')+'" data-pshi="'+i+'"><div class="dot" style="background:'+(sh.color||'#e2e8f0')+'"></div> 图形'+(i+1)+'</div>'});
			h+='<div class="el-row" style="color:#3b82f6;cursor:pointer" id="pAddShape">+ 新增图形</div>';
			document.getElementById('dsElList').innerHTML=h;
			document.querySelectorAll('#dsElList [data-pel]').forEach(function(r){r.addEventListener('click',function(){_dsPSel=this.dataset.pel;buildPopupElList();buildPopupProps()})});
			document.querySelectorAll('#dsElList [data-pshi]').forEach(function(r){r.addEventListener('click',function(){_dsPSel=parseInt(this.dataset.pshi);buildPopupElList();buildPopupProps()})});
			var ab=document.getElementById('pAddShape');if(ab)ab.addEventListener('click',function(){
				_dsPopup.shapes.push({type:'rect',x:10,y:10,w:100,h:40,color:'#cbd5e1',opacity:1,zIndex:0,radius:0,vertices:[]});
				_dsPSel=_dsPopup.shapes.length-1;buildPopupPreview();buildPopupElList();buildPopupProps();
			});
		}

		function buildPopupProps(){
			var props=document.getElementById('dsProps');props.innerHTML='';var sel=_dsPSel;
			if(sel===null||sel==='container'){
				props.innerHTML='<h3>弹窗容器</h3>'+ppr2('背景色','popup.bg','color')
					+'<div class="ds-prop"><div class="row"><label style="flex:1;font-size:10px;color:#64748b">封面平均色为背景</label><input type="checkbox" data-ppath="popup.useCoverBg"'+(ppGet('popup.useCoverBg')?' checked':'')+'></div></div>'
					+ppr2('圆角','popup.borderRadius','range',0,40)+ppr2('宽度','popup.width','range',300,800)+ppr2('内边距','popup.padding','range',8,48);
			}else if(sel==='header'){
				props.innerHTML='<h3>标题区高度</h3>'+ppr2('高度','popup.headerH','range',60,200);
			}else if(sel==='cover'){
				props.innerHTML='<h3>封面</h3>'+ppr2('X','popup.cvX','range',-50,400)+ppr2('Y','popup.cvY','range',-50,150)+ppr2('宽度','popup.cvW','range',30,200)+ppr2('高度','popup.cvH','range',30,200)+ppr2('圆角','popup.cvRadius','range',0,100);
			}else if(sel==='heart'){
				props.innerHTML='<h3>爱心（固定贴右侧）</h3>'
					+'<div class="ds-prop"><div class="row"><label style="flex:1;font-size:10px;color:#64748b">显示爱心</label><input type="checkbox" data-ppath="popup.heart.hVisible"'+(ppGet('popup.heart.hVisible')?' checked':'')+'></div></div>'
					+ppr2('距右边距','popup.heart.hRight','range',0,300)+ppr2('Y','popup.heart.hY','range',0,180)+ppr2('大小','popup.heart.hSize','range',12,60)+ppr2('颜色','popup.heart.hColor','color');
			}else if(sel==='name'){
				props.innerHTML='<h3>曲名</h3>'+ppr2('左端X','popup.naX','range',0,400)+ppr2('右端X','popup.naRight','range',50,450)+ppr2('Y','popup.naY','range',0,180)+ppr2('字号','popup.naSz','range',10,32)+ppr2('颜色','popup.naColor','color')+pprFont2('字体','popup.naFont')+pprW2('粗细','popup.naWeight');
			}else if(sel==='artist'){
				props.innerHTML='<h3>作者</h3>'+ppr2('左端X','popup.arX','range',0,400)+ppr2('右端X','popup.arRight','range',50,450)+ppr2('Y','popup.arY','range',0,180)+ppr2('字号','popup.arSz','range',8,24)+ppr2('颜色','popup.arColor','color')+pprFont2('字体','popup.arFont');
			}else if(sel==='charter'){
				props.innerHTML='<h3>谱师</h3>'+ppr2('X','popup.chX','range',0,400)+ppr2('Y','popup.chY','range',0,180)+ppr2('字号','popup.chSz','range',8,20)+ppr2('颜色','popup.chColor','color')+pprFont2('字体','popup.chFont');
			}else if(sel==='tagStyle'){
				props.innerHTML='<h3>标签通用</h3>'+pprFont2('字体','popup.tagFont')+ppr2('字号','popup.tagSz','range',8,18)+ppr2('圆角','popup.tagRadius','range',0,12);
			}else if(sel==='tag4k'){
				props.innerHTML='<h3>4K标签</h3>'+ppr2('背景','popup.tag4kBg','color')+ppr2('文字色','popup.tag4kColor','color');
			}else if(sel==='tag5k'){
				props.innerHTML='<h3>5K标签</h3>'+ppr2('背景','popup.tag5kBg','color')+ppr2('文字色','popup.tag5kColor','color');
			}else if(sel==='tag6k'){
				props.innerHTML='<h3>6K标签</h3>'+ppr2('背景','popup.tag6kBg','color')+ppr2('文字色','popup.tag6kColor','color');
			}else if(sel==='tagCatch'){
				props.innerHTML='<h3>Catch标签</h3>'+ppr2('背景','popup.tagCatchBg','color')+ppr2('文字色','popup.tagCatchColor','color');
			}else if(sel==='diff'){
				props.innerHTML='<h3>难度徽章通用</h3>'+pprFont2('字体','popup.diffFont')+ppr2('字号','popup.diffSz','range',8,18)+ppr2('圆角','popup.diffRadius','range',0,100);
			}else if(sel==='easy'){
				props.innerHTML='<h3>简单</h3>'+ppr2('背景','popup.easyBg','color')+ppr2('文字色','popup.easyColor','color');
			}else if(sel==='normal'){
				props.innerHTML='<h3>普通</h3>'+ppr2('背景','popup.normalBg','color')+ppr2('文字色','popup.normalColor','color');
			}else if(sel==='hard'){
				props.innerHTML='<h3>困难</h3>'+ppr2('背景','popup.hardBg','color')+ppr2('文字色','popup.hardColor','color');
			}else if(sel==='master'){
				props.innerHTML='<h3>大师/Catch 彩虹渐变</h3>'+ppr2('文字色','popup.masterColor','color')+'<div class="ds-prop"><label style="font-size:10px;color:#94a3b8">渐变色（左→右）</label></div>'+ppr2('色1','popup.g1','color')+ppr2('色2','popup.g2','color')+ppr2('色3','popup.g3','color')+ppr2('色4','popup.g4','color')+ppr2('色5','popup.g5','color');
			}else if(typeof sel==='number'){
				var sh=_dsPopup.shapes[sel];if(!sh)return;normShape(sh);
				props.innerHTML='<h3>图形'+(sel+1)+'</h3>'
					+ps3('类型','psh_type_'+sel,[['rect','矩形'],['circle','圆形']],sh.type||'rect')
					+pn('X','psh_x_'+sel,sh.x)+pn('Y','psh_y_'+sel,sh.y)
					+pn(sh.type==='circle'?'直径':'宽','psh_w_'+sel,sh.w)+(sh.type==='circle'?'':pn('高','psh_h_'+sel,sh.h))
					+pc2('颜色','psh_color_'+sel,sh.color||'#e2e8f0')
					+pr2('透明度','psh_opacity_'+sel,0,100,Math.round((sh.opacity||1)*100))
					+(sh.type==='rect'?pr2('圆角','psh_radius_'+sel,0,200,sh.radius||0):'')
					+pr2('层级','psh_z_'+sel,-5,20,sh.zIndex||0)
					+'<div class="ds-prop"><button id="pDelSh" style="width:100%;font-size:11px;padding:4px;border:1px solid #cbd5e1;border-radius:4px;background:#f8fafc;cursor:pointer;font-family:inherit">删除此图形</button></div>';
				document.getElementById('pDelSh').addEventListener('click',function(){_dsPopup.shapes.splice(sel,1);_dsPSel=null;buildPopupPreview();buildPopupElList();buildPopupProps()});
			}
			bindPopupPropEvents();
		}

		function bindPopupPropEvents(){
			var props=document.getElementById('dsProps');
			props.querySelectorAll('input[type=range]').forEach(function(el){
				var pp=el.dataset.ppath,sid=el.dataset.sid;
				if(pp){var vid=document.getElementById('ppv_'+pp.replace(/\./g,'_'));if(vid)vid.textContent=ppGet(pp);el.addEventListener('input',function(){ppSet(pp,parseInt(this.value));if(vid)vid.textContent=this.value;buildPopupPreview()})}
				else if(sid){el.addEventListener('input',function(){handlePShProp(sid,parseInt(this.value));buildPopupPreview()})}
			});
			props.querySelectorAll('input[type=number]').forEach(function(el){el.addEventListener('input',function(){handlePShProp(this.dataset.sid,parseInt(this.value)||0);buildPopupPreview()})});
			props.querySelectorAll('input[type=color]').forEach(function(el){
				if(el.dataset.ppath)el.addEventListener('input',function(){ppSet(this.dataset.ppath,this.value);buildPopupPreview()});
				else if(el.dataset.sid)el.addEventListener('input',function(){handlePShProp(this.dataset.sid,this.value);buildPopupPreview()});
			});
			props.querySelectorAll('input[type=checkbox]').forEach(function(el){
				if(el.dataset.ppath)el.addEventListener('change',function(){ppSet(this.dataset.ppath,this.checked);buildPopupPreview()});
			});
			props.querySelectorAll('select').forEach(function(el){
				if(el.dataset.ppath)el.addEventListener('change',function(){ppSet(this.dataset.ppath,this.value);buildPopupPreview()});
				else if(el.dataset.sid)el.addEventListener('change',function(){handlePShProp(this.dataset.sid,this.value);buildPopupPreview()});
			});
		}

		function handlePShProp(sid,val){
			var m=sid.match(/^psh_(.+)_(\d+)$/);if(!m)return;
			var key=m[1],idx=parseInt(m[2]),sh=_dsPopup.shapes[idx];if(!sh)return;
			if(key==='z')key='zIndex';
			if(key==='opacity')sh[key]=val/100;
			else if(key==='type'){sh[key]=val;if(val==='circle')sh.h=sh.w}
			else sh[key]=typeof val==='string'?val:(parseInt(val)||0);
		}

		// ======== 筛选胶囊可视化设计器 ========
		var _dsFSel='general';
		function ffGet(path){var a=path.split('.');var o=_dsFilters;for(var i=1;i<a.length;i++){if(o==null)return'';o=o[a[i]]}return o===undefined?'':o}
		function ffSet(path,v){var a=path.split('.');var o=_dsFilters;for(var i=1;i<a.length-1;i++){if(o==null)return;o=o[a[i]]}o[a[a.length-1]]=v}
		function ffPr2(lbl,id,type,mn,mx,step){
			var v=ffGet(id),vid='ffv_'+id.replace(/\./g,'_');
			if(type==='color')return'<div class="ds-prop"><div class="row"><label style="flex:1">'+lbl+'</label><input type="color" data-fpath="'+id+'" value="'+v+'"></div></div>';
			if(type==='text')return'<div class="ds-prop"><label>'+lbl+'</label><input type="text" data-fpath="'+id+'" value="'+String(v).replace(/"/g,'&quot;')+'" style="width:100%;font-size:11px;border:1px solid #cbd5e1;border-radius:4px;padding:2px 4px"></div>';
			if(type==='font'){var h="<div class='ds-prop'><label>"+lbl+"</label><select data-fpath='"+id+"'><option value='默认'"+(v==='默认'?' selected':'')+">默认</option>";_dsFonts.forEach(function(f){h+="<option value='"+f.name+"'"+(v===f.name?' selected':'')+">"+f.name+"</option>"});return h+"</select></div>"}
			var st=step||1;var disp=(st<1&&(v%1!==0))?v:Math.round(v);
			return'<div class="ds-prop"><label>'+lbl+' <span class="val" id="'+vid+'">'+disp+'</span></label><input type="range" data-fpath="'+id+'" min="'+mn+'" max="'+mx+'" step="'+st+'" value="'+v+'"></div>'
		}
		function buildFiltersPreview(){
			document.getElementById('dsCard').style.display='none';
			document.getElementById('dsPCard').style.display='none';
			var el=document.getElementById('dsFCard');el.style.display='flex';el.style.cssText='display:flex;flex-direction:column;align-items:center;gap:20px;padding:40px;max-width:96vw;margin:0 auto';
			var f=_dsFilters,ff=f.font==='默认'?"'Microsoft YaHei',sans-serif":"'"+f.font+"','Microsoft YaHei',sans-serif";
			var stroke=f.strokeWidth>0?';-webkit-text-stroke:'+f.strokeWidth+'px '+f.strokeColor+';paint-order:stroke fill':'';
			var labels={ALL:'ALL','4K':'4键','5K':'5键','6K':'6键',Catch:'Catch'};
			var html='<div style="display:flex;gap:10px;flex-wrap:wrap;justify-content:center">';
			['ALL','4K','5K','6K','Catch'].forEach(function(k){
				var c=f.capsules[k],isActive=(_dsFSel===k);
				var bg=isActive?'linear-gradient(90deg,'+c.gradLeft+','+c.gradRight+')':_unselectedBgCss();
				var color=isActive?(c.selectedColor||'#fff'):(c.unselectedColor||'#64748b');
				var shadow=isActive?'0 4px 14px '+c.gradLeft+'66,inset 0 1px 0 rgba(255,255,255,0.3)':'inset 0 0 0 1px rgba(203,213,225,0.6),0 1px 3px rgba(0,0,0,0.04)';
				var st=(isActive&&f.strokeWidth>0)?stroke:'';
				html+='<div data-fsel="'+k+'" style="font-family:'+ff+';font-size:'+f.fontSize+'px;font-weight:600;letter-spacing:.5px;width:'+f.capsuleWidth+'px;height:'+f.capsuleHeight+'px;overflow:hidden;box-sizing:border-box;border-radius:'+f.borderRadius+'px;color:'+color+';background:'+bg+';box-shadow:'+shadow+';cursor:pointer;display:inline-flex;align-items:center;justify-content:center;transform:'+(isActive?'translateY(-1px)':'none')+st+'"><span style="display:inline-block;transform:scaleX('+(f.fontScaleX||1)+')">'+labels[k]+'</span></div>';
			});
			html+='</div><div style="color:#94a3b8;font-size:12px;text-align:center">点击胶囊切换选中态预览（蓝框为当前选中编辑项）</div>';
			el.innerHTML=html;
			el.querySelectorAll('[data-fsel]').forEach(function(c){c.addEventListener('click',function(){_dsFSel=this.dataset.fsel;buildFiltersPreview();buildFiltersElList();buildFiltersProps()})});
		}
		function buildFiltersElList(){
			var sel=_dsFSel,f=_dsFilters;
			function row(id,lbl,dot){return'<div class="el-row'+(sel===id?' sel':'')+'" data-fel="'+id+'"><div class="dot" style="'+dot+'"></div> '+lbl+'</div>'}
			function grad(k){var c=f.capsules[k];return'background:linear-gradient(90deg,'+c.gradLeft+','+c.gradRight+')'}
			var h=row('general','通用样式','background:#3b82f6')
				+row('ALL','ALL',grad('ALL'))
				+row('4K','4键',grad('4K'))
				+row('5K','5键',grad('5K'))
				+row('6K','6键',grad('6K'))
				+row('Catch','Catch',grad('Catch'));
			document.getElementById('dsElList').innerHTML=h;
			document.querySelectorAll('#dsElList [data-fel]').forEach(function(r){r.addEventListener('click',function(){_dsFSel=this.dataset.fel;buildFiltersPreview();buildFiltersElList();buildFiltersProps()})});
		}
		function buildFiltersProps(){
			var props=document.getElementById('dsProps'),sel=_dsFSel,f=_dsFilters;
			if(sel==='general'||!sel){
				props.innerHTML='<h3>通用样式</h3>'
					+ffPr2('字体','filters.font','font')
					+ffPr2('字号','filters.fontSize','range',8,48)
					+ffPr2('字体瘦身','filters.fontScaleX','range',0.7,1,0.05)
					+ffPr2('胶囊宽度','filters.capsuleWidth','range',60,200)
					+ffPr2('胶囊高度','filters.capsuleHeight','range',24,80)
					+ffPr2('圆角','filters.borderRadius','range',0,100)
					+'<div class="ds-prop"><label style="font-size:10px;color:#94a3b8;padding:4px 0">未选中背景</label></div>'
					+ffPr2('颜色','filters.unselectedBgColor','color')
					+ffPr2('透明度','filters.unselectedBgOpacity','range',0,100)
					+'<div class="ds-prop"><label style="font-size:10px;color:#94a3b8;padding:4px 0">文字描边</label></div>'
					+ffPr2('描边色','filters.strokeColor','color')
					+ffPr2('描边粗度','filters.strokeWidth','range',0,5,0.1);
			}else{
				var labels={ALL:'ALL','4K':'4键','5K':'5键','6K':'6键',Catch:'Catch'};
				var c=f.capsules[sel];if(!c){sel='general';return buildFiltersProps()}
				props.innerHTML='<h3>'+labels[sel]+' 胶囊</h3>'
					+'<div class="ds-prop"><label style="font-size:10px;color:#94a3b8">渐变（左→右）</label></div>'
					+ffPr2('左端色','filters.capsules.'+sel+'.gradLeft','color')
					+ffPr2('右端色','filters.capsules.'+sel+'.gradRight','color')
					+'<div class="ds-prop"><label style="font-size:10px;color:#94a3b8;padding:4px 0">文字颜色</label></div>'
					+ffPr2('未选中字色','filters.capsules.'+sel+'.unselectedColor','color')
					+ffPr2('选中字色','filters.capsules.'+sel+'.selectedColor','color');
			}
			var fn=f.font;if(fn&&fn!=='默认')loadFont(fn);
			bindFiltersPropEvents();
		}
		function bindFiltersPropEvents(){
			var props=document.getElementById('dsProps');
			props.querySelectorAll('input[type=range]').forEach(function(el){
				var fp=el.dataset.fpath;if(!fp)return;
				var vid=document.getElementById('ffv_'+fp.replace(/\./g,'_'));if(vid)vid.textContent=ffGet(fp);
				el.addEventListener('input',function(){ffSet(fp,parseFloat(this.value));if(vid)vid.textContent=this.value;buildFiltersPreview();applyFiltersCSS()});
			});
			props.querySelectorAll('input[type=color]').forEach(function(el){
				var fp=el.dataset.fpath;if(!fp)return;
				el.addEventListener('input',function(){ffSet(fp,this.value);buildFiltersPreview();buildFiltersElList();applyFiltersCSS()});
			});
			props.querySelectorAll('select').forEach(function(el){
				var fp=el.dataset.fpath;if(!fp)return;
				el.addEventListener('change',function(){ffSet(fp,this.value);buildFiltersPreview();applyFiltersCSS()});
			});
			props.querySelectorAll('input[type=text]').forEach(function(el){
				var fp=el.dataset.fpath;if(!fp)return;
				el.addEventListener('input',function(){ffSet(fp,this.value);buildFiltersPreview();applyFiltersCSS()});
			});
		}

		(function(){
			var cb=document.getElementById('dsModeCard'),pb=document.getElementById('dsModePopup'),fb=document.getElementById('dsModeFilter');
			if(!cb||!pb)return;
			function showCard(){cb.classList.add('active');pb.classList.remove('active');if(fb)fb.classList.remove('active');document.getElementById('dsPCard').style.display='none';document.getElementById('dsFCard').style.display='none';document.getElementById('dsCard').style.display='';_dsSel=null;buildPreview();buildElList();buildProps()}
			function showPopup(){pb.classList.add('active');cb.classList.remove('active');if(fb)fb.classList.remove('active');document.getElementById('dsFCard').style.display='none';document.getElementById('dsCard').style.display='none';buildPopupPreview();buildPopupElList();buildPopupProps()}
			function showFilter(){if(!fb)return;fb.classList.add('active');cb.classList.remove('active');pb.classList.remove('active');document.getElementById('dsPCard').style.display='none';document.getElementById('dsCard').style.display='none';buildFiltersPreview();buildFiltersElList();buildFiltersProps()}
			cb.addEventListener('click',function(){if(_dsMode==='card')return;_dsMode='card';showCard()});
			pb.addEventListener('click',function(){if(_dsMode==='popup')return;_dsMode='popup';showPopup()});
			if(fb)fb.addEventListener('click',function(){if(_dsMode==='filters')return;_dsMode='filters';showFilter()});
		})();
	})();
