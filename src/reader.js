// Notia 1.2 — library (cover flow / list) + readers for PDF, EPUB and MOBI
addL({addbook:["+ Add book","+ Ajouter un livre"],v_flow:["Cover flow","Couvertures"],v_list:["List","Liste"],open:["Open","Ouvrir"],remove:["Remove","Retirer"],
max10:["Library full (10 books max). Finish one of your books first, then import a new one.","Bibliothèque pleine (10 livres max). Terminez d’abord un de vos livres avant d’en importer un nouveau."],finishq:["Continue reading “{b}” ({p}% read)?","Continuer la lecture de « {b} » ({p} % lu) ?"],
nohuff:["This MOBI uses an unsupported compression. Convert it to EPUB or PDF.","Ce MOBI utilise une compression non prise en charge. Convertissez-le en EPUB ou PDF."],drm:["This book is DRM-protected and cannot be opened.","Ce livre est protégé par DRM et ne peut pas être ouvert."],
extracts:["Extracts","Extraits"],copied:["Copied to note","Copié dans la note"],chap:["Section","Section"],
v_back:["Back to library","Retour à la bibliothèque"],v_toc:["Table of contents (T)","Table des matières (T)"],notoc:["No table of contents in this book.","Ce livre n’a pas de table des matières."],v_prev:["Previous page","Page précédente"],v_next:["Next page (→ or ↓ at the end of the page)","Page suivante (→ ou ↓ en bas de page)"],v_zout:["Zoom out (Ctrl+−)","Zoom arrière (Ctrl+−)"],v_zin:["Zoom in (Ctrl++)","Zoom avant (Ctrl++)"],v_fitw:["Fit width","Ajuster à la largeur"],v_fitp:["Fit page","Ajuster à la page"],v_rot:["Rotate","Pivoter"],v_night:["Night mode","Mode nuit"],v_search:["Search in book (Ctrl+F)","Chercher dans le livre (Ctrl+F)"],v_mark:["Bookmark this page (Ctrl+D)","Marquer cette page (Ctrl+D)"],v_panel:["Highlights & bookmarks","Surlignages et signets"],v_fs:["Full screen (Ctrl+L)","Plein écran (Ctrl+L)"],
h_hl:["Highlight","Surligner"],h_note:["Copy to a note","Copier dans une note"],hl_all:["All highlights → note","Tous les surlignages → note"],bm_h:["Bookmarks","Signets"],hl_h:["Highlights","Surlignages"],s_ph:["Search…","Rechercher…"],s_none:["No result","Aucun résultat"],s_busy:["Searching…","Recherche…"],hlnone:["Select text, then pick a color to highlight it.","Sélectionnez du texte puis choisissez une couleur pour le surligner."]});
var book=null;let R=null,pj,CI=db.lib.ci||0,sc=1.3,XN={},PG=1800;const EXT=p=>(p.split(".").pop()||"").toLowerCase(),pct=k=>Math.min(100,Math.round((k.max||0)/(k.total||1)*100)),HC={y:"#fde04799",g:"#86efac99",b:"#93c5fd99",r:"#fca5a599"};
function normBooks(){for(const k of db.lib?.books||[]){const e=EXT(k.path||"");k.fmt=e=="pdf"?"pdf":e=="epub"?"epub":"mobi";k.name??=(k.path||"").split(/[\\/]/).pop().replace(/\.[^.]+$/,"")}}
// ---------- loaders ----------
async function loadPdf(path){pj??=await import("./vendor/pdf.min.mjs");pj.GlobalWorkerOptions.workerSrc="./vendor/pdf.worker.min.mjs";return pj.getDocument({data:new Uint8Array(await I("read_file",{path}))}).promise}
const res=(from,h)=>decodeURIComponent(new URL(h,"http://x/"+from).pathname.slice(1)),X_L=s=>new DOMParser().parseFromString(s,"application/xml"),textOf=h=>new DOMParser().parseFromString(h,"text/html").body.textContent;
async function loadEpub(path){const z=unzip(new Uint8Array(await I("read_file",{path}))),opfP=X_L(await z.text("META-INF/container.xml")).querySelector("rootfile").getAttribute("full-path"),opf=X_L(await z.text(opfP)),man={};
opf.querySelectorAll("manifest > item").forEach(i=>man[i.getAttribute("id")]={h:res(opfP,i.getAttribute("href")),mt:i.getAttribute("media-type")||"",pr:i.getAttribute("properties")||""});
const sp=[...opf.querySelectorAll("spine > itemref")].map(r=>man[r.getAttribute("idref")]).filter(m=>m&&/html|xml/.test(m.mt)).map(m=>m.h),ix=new Map(sp.map((p,i)=>[p,i])),toc=[];
const nav=Object.values(man).find(m=>/\bnav\b/.test(m.pr)),ncx=Object.values(man).find(m=>m.mt=="application/x-dtbncx+xml");
try{if(nav){const d=new DOMParser().parseFromString(await z.text(nav.h),"text/html"),n=d.querySelector('nav[epub\\:type="toc"]')||d.querySelector("nav"),w=(ol,dp)=>[...ol.children].forEach(li=>{const a=li.querySelector(":scope > a,:scope > span");if(a){const[p,f]=(a.getAttribute("href")||"").split("#"),u=ix.get(res(nav.h,p||nav.h.split("/").pop()));if(u!=null)toc.push({t:a.textContent.trim(),u,f,d:dp})}const s=li.querySelector(":scope > ol");if(s)w(s,dp+1)});n&&n.querySelector("ol")&&w(n.querySelector("ol"),0)}
else if(ncx){const w=(el,dp)=>[...el.children].filter(c=>c.localName=="navPoint").forEach(np=>{const[p,f]=np.querySelector("content").getAttribute("src").split("#"),u=ix.get(res(ncx.h,p));if(u!=null)toc.push({t:np.querySelector("navLabel").textContent.trim(),u,f,d:dp});w(np,dp+1)});w(X_L(await z.text(ncx.h)).querySelector("navMap"),0)}}catch{}
const txt=[];for(const p of sp)txt.push(textOf(await z.text(p)||""));
const cid=opf.querySelector('meta[name="cover"]')?.getAttribute("content"),cm=Object.values(man).find(m=>/cover-image/.test(m.pr))||man[cid];
return{n:sp.length,txt,toc,title:opf.querySelector("metadata > title")?.textContent,cover:cm?await z.get(cm.h):null,
ch:async i=>({h:await z.text(sp[i]),path:sp[i]}),img:async(from,src)=>{const b=await z.get(res(from,src));return b&&URL.createObjectURL(new Blob([b]))},link:(from,h)=>{const[p,f]=h.split("#"),u=ix.get(p?res(from,p):from);return u==null?null:{u,f}}}}
function trimRec(r,fl){let n=0;for(let f=fl>>1;f;f>>=1)if(f&1){let s=0,sh=0,p=r.length-n;while(p>0){const c=r[--p];s|=(c&127)<<sh;sh+=7;if(c&128||sh>=28)break}n+=s}if(fl&1)n+=(r[r.length-n-1]&3)+1;return r.subarray(0,r.length-n)}
function palm(d){const o=[];for(let i=0;i<d.length;){const c=d[i++];if(c==0||c>=9&&c<=127)o.push(c);else if(c<=8)for(let k=0;k<c;k++)o.push(d[i++]);else if(c>=192)o.push(32,c^128);else{const q=c<<8|d[i++],dist=q>>3&2047,len=(q&7)+3;for(let k=0;k<len;k++)o.push(o[o.length-dist])}}return Uint8Array.from(o)}
async function loadMobi(path){const u=new Uint8Array(await I("read_file",{path})),v=new DataView(u.buffer,u.byteOffset),nrec=v.getUint16(76),off=i=>v.getUint32(78+i*8),rec=i=>u.subarray(off(i),i+1<nrec?off(i+1):u.length),r0=rec(0),d=new DataView(r0.buffer,r0.byteOffset,r0.byteLength);
if(String.fromCharCode(...r0.subarray(16,20))!="MOBI")throw Error("Not a MOBI file");const comp=d.getUint16(0),ntx=d.getUint16(8);if(comp==17480)throw Error(t("nohuff"));if(d.getUint16(12))throw Error(t("drm"));
const hl=d.getUint32(20),fl=hl>=228&&r0.length>244?d.getUint16(242):0,first=d.getUint32(108),enc=d.getUint32(28),parts=[];for(let i=1;i<=ntx;i++){const r=trimRec(rec(i),fl);parts.push(comp==2?palm(r):r)}
const all=new Uint8Array(parts.reduce((s,a)=>s+a.length,0));let p=0;parts.forEach(a=>{all.set(a,p);p+=a.length});let html=new TextDecoder(enc==65001?"utf-8":"windows-1252").decode(all),title,coff=null;
if(d.getUint32(128)&64&&String.fromCharCode(...r0.subarray(16+hl,20+hl))=="EXTH"){let q=16+hl+12;const cnt=d.getUint32(16+hl+8);for(let i=0;i<cnt;i++){const ty=d.getUint32(q),ln=d.getUint32(q+4);if(ty==503)title=new TextDecoder().decode(r0.subarray(q+8,q+ln));if(ty==201)coff=d.getUint32(q+8);q+=ln}}
let chs=html.split(/<mbp:pagebreak[^>]*>/i).filter(s=>textOf(s).trim().length>20||/<img/i.test(s));if(chs.length<3&&html.length>150e3){chs=[];let c="";html.split(/(?<=<\/p>)/i).forEach(s=>{c+=s;if(c.length>50e3){chs.push(c);c=""}});c&&chs.push(c)}
const txt=chs.map(textOf),toc=chs.map((h,i)=>({t:(h.match(/<h[1-3][^>]*>([\s\S]*?)<\/h[1-3]>/i)?.[1]||"").replace(/<[^>]+>/g,"").trim().slice(0,70)||t("chap")+" "+(i+1),u:i,d:0}));
return{n:chs.length,txt,toc,title,cover:coff!=null?rec(first+coff):null,ch:async i=>({h:chs[i],path:""}),img:async(_,s)=>null,mimg:n=>{const b=rec(first+n-1);return URL.createObjectURL(new Blob([b]))},link:()=>null}}
const loadRf=k=>k.fmt=="epub"?loadEpub(k.path):loadMobi(k.path);
function clean(html){const doc=new DOMParser().parseFromString(html,"text/html");doc.querySelectorAll("script,style,link,iframe,object,embed,form,meta,base,title,audio,video").forEach(e=>e.remove());doc.querySelectorAll("*").forEach(e=>{for(const a of[...e.attributes]){const n=a.name;if(/^on/i.test(n)||n=="style"||/^(href|src|xlink:href)$/i.test(n)&&/^\s*javascript:/i.test(a.value))e.removeAttribute(n)}});return doc.body}
// ---------- cover generation ----------
async function shrink(bytes){const u=URL.createObjectURL(new Blob([bytes]));try{const i=await loadImg(u),k=Math.min(1,300/i.width),c=document.createElement("canvas");c.width=i.width*k;c.height=i.height*k;c.getContext("2d").drawImage(i,0,0,c.width,c.height);return c.toDataURL("image/jpeg",.75)}finally{URL.revokeObjectURL(u)}}
async function mkCover(k){try{if(k.fmt=="pdf"){const doc=await loadPdf(k.path),p=await doc.getPage(1),v0=p.getViewport({scale:1}),v=p.getViewport({scale:300/v0.width}),c=document.createElement("canvas");c.width=v.width;c.height=v.height;await p.render({canvasContext:c.getContext("2d"),viewport:v}).promise;doc.destroy();return c.toDataURL("image/jpeg",.72)}const D=await loadRf(k);return D.cover?await shrink(D.cover):""}catch{return""}}
let cvBusy=false;const cvTry=new Set();async function missingCovers(){if(cvBusy)return;cvBusy=true;try{normBooks();for(const k of db.lib.books)if(!k.cv&&!cvTry.has(k.id)){cvTry.add(k.id);k.cv=await mkCover(k);save();if(tab=="lib"&&!book)lib()}}finally{cvBusy=false}}
// ---------- library ----------
function toFinish(){const B=db.lib.books.filter(k=>pct(k)<100);return B.filter(k=>pct(k)>0).sort((a,b)=>pct(b)-pct(a))[0]||B[0]}
async function addBook(path){const B=db.lib.books,o=B.find(k=>k.path==path);if(o)return o;if(B.length>=10){const k=toFinish();if(await ask(t("max10")+(k?"\n\n"+t("finishq").replace("{b}",k.name).replace("{p}",pct(k)):""))&&k){tab="lib";render();openBook(k.id)}return null}
const e=EXT(path),k={id:uid(),path,name:path.split(/[\\/]/).pop().replace(/\.[^.]+$/,""),fmt:e=="pdf"?"pdf":e=="epub"?"epub":"mobi",page:1,max:0,total:0,secs:0};B.push(k);save();return k}
async function addDialog(){const p=await TR.dialog.open({filters:[{name:"Ebooks",extensions:["pdf","epub","mobi","azw"]}]});if(!p)return;const k=await addBook(p);if(k){CI=db.lib.books.indexOf(k);lib()}}
async function openExt(path){if(!/\.(pdf|epub|mobi|azw)$/i.test(path))return;if(book)closeBook();const k=await addBook(path);if(!k)return;tab="lib";render();openBook(k.id)}
const cvr=k=>k.cv?`<img src="${k.cv}" alt="">`:esc(k.name),info=k=>`${pct(k)}% ${t("read")} · ${t("pages")} ${k.page||1}/${k.total||"?"} · ${fT(k.secs||0)}`;
function lib(){if($("#lib").hidden||book)return;normBooks();const B=db.lib.books,v=db.lib.view||"flow";CI=Math.max(0,Math.min(B.length-1,CI));
let h=`<div class="sg" style="align-items:center"><button id="addpdf" class="pri">${t("addbook")}</button><span>${t("time")}: <b>${fT(db.lib.secs)}</b> · ${B.length}/10</span><span style="flex:1"></span><button data-lv="flow" class="${v=="flow"?"on":""}">${t("v_flow")}</button><button data-lv="list" class="${v=="list"?"on":""}">${t("v_list")}</button></div>`;
if(!B.length)h+=`<p>${t("empty")}</p>`;else if(v=="flow")h+=`<div id="cf">${B.map((k,i)=>`<div class="cfi${k.cv?"":" nc"}" data-ci="${i}">${cvr(k)}</div>`).join("")}<button class="cfn" data-cfn="-1" style="left:8px">‹</button><button class="cfn" data-cfn="1" style="right:8px">›</button><div id="cfi"></div></div>`;
else h+=B.map(k=>`<div class="bk" data-bk="${k.id}">${k.cv?`<img src="${k.cv}" alt="">`:""}<div style="flex:1"><b>${esc(k.name)}</b> <small>${k.fmt.toUpperCase()}</small><div class="pb"><i style="width:${pct(k)}%"></i></div><small>${info(k)}</small></div><button data-rm="${k.id}" title="${t("remove")}">✕</button></div>`).join("");
$("#shelf").className=v;$("#shelf").innerHTML=h;if(v=="flow"&&B.length)cfPos();missingCovers()}
function cfPos(){const B=db.lib.books;if(!B.length)return;CI=Math.max(0,Math.min(B.length-1,CI));$$(".cfi").forEach((e,i)=>{const d=i-CI,a=Math.abs(d),s=Math.sign(d);e.style.transform=d?`translateX(${s*(135+(a-1)*48)}px) translateZ(${-a*45}px) rotateY(${-s*58}deg)`:"translateX(0) translateZ(130px)";e.style.zIndex=50-a;e.style.opacity=a>6?0:1});
const k=B[CI];$("#cfi").innerHTML=`<b>${esc(k.name)}</b><div class="pb"><i style="width:${pct(k)}%"></i></div><small>${info(k)}</small><div style="margin-top:6px"><button class="pri" data-bk="${k.id}">${t("open")}</button> <button data-rm="${k.id}">✕ ${t("remove")}</button></div>`;db.lib.ci=CI}
// ---------- reader ----------
function vInit(){$("#viewer").innerHTML=`<div id="vbar" class="nofocus"><button data-v="back" title="${t("v_back")}">${t("back")}</button><button data-v="toc">☰</button><button data-v="prev">◀</button><input id="pn" type="number" min="1"><span id="pt"></span><button data-v="next">▶</button><button data-v="zout">−</button><button data-v="zin">+</button><button data-v="fitw" data-pdf>↔</button><button data-v="fitp" data-pdf>⛶</button><button data-v="rot" data-pdf>⟳</button><button data-v="night">🌙</button><button data-v="search">🔎</button><button data-v="mark">🔖</button><button data-v="panel">🖍</button><button data-v="fs">⤢</button></div>
<div id="vmain"><aside id="toc" hidden></aside><div id="pg"><div id="pgw"><canvas id="cv"></canvas><div class="textLayer" id="tlyr"></div><div id="hl"></div></div><div id="rf" hidden></div></div><aside id="mk" hidden></aside></div>
<div id="stb" hidden class="nofocus">${Object.entries(HC).map(([k,c])=>`<button data-hc="${k}" style="background:${c}">🖍</button>`).join("")}<button data-hn title="${t("h_note")}">📝</button></div>`;
$$("#vbar [data-v]").forEach(b=>{const k="v_"+b.dataset.v;if(t(k))b.title=t(k)});$$("[data-pdf]").forEach(b=>b.hidden=R.kind!="pdf");$("#pgw").hidden=R.kind!="pdf";$("#rf").hidden=R.kind=="pdf";
const pg=$("#pg");let wt=0;pg.addEventListener("wheel",e=>{if(e.ctrlKey){e.preventDefault();zoom(e.deltaY<0?1:-1);return}const n=Date.now();if(n-wt<500)return;if(e.deltaY>0&&pg.scrollTop+pg.clientHeight>=pg.scrollHeight-2){wt=n;nextU()}else if(e.deltaY<0&&pg.scrollTop<=0){wt=n;prevU(true)}},{passive:false});
pg.addEventListener("scroll",()=>{clearTimeout(vInit.t);vInit.t=setTimeout(prog,250)});$("#pn").onchange=e=>R.kind=="pdf"?goPdf(+e.target.value):goVP(+e.target.value);
pg.addEventListener("mouseup",()=>setTimeout(selTb,10));pg.addEventListener("scroll",()=>$("#stb").hidden=true);
$("#rf").addEventListener("click",async e=>{const a=e.target.closest("a");if(!a)return;e.preventDefault();const h=a.getAttribute("href")||"";if(/^https?:/.test(h))return open_(h);const L=R.doc.link(R.cp,h);if(L)showCh(L.u,L.f)})}
async function openBook(id){normBooks();const k=db.lib.books.find(z=>z.id==id);if(!k)return;let D;try{D=k.fmt=="pdf"?await loadPdf(k.path):await loadRf(k)}catch(e){return toast(String(e.message||e))}
book=k;R={kind:k.fmt=="pdf"?"pdf":"rf",doc:D,u:1,rot:0,tk:0,pm:null,q:"",urls:[]};vInit();$("#shelf").hidden=true;$("#viewer").hidden=false;
if(R.kind=="pdf"){R.n=D.numPages;k.total=R.n;R.u=Math.min(k.page||1,R.n);drawPdf();D.getOutline().then(o=>{R.toc=[];const w=(a,d)=>(a||[]).forEach(i=>{R.toc.push({t:i.title,dest:i.dest,d});w(i.items,d+1)});w(o,0)}).catch(()=>R.toc=[])}
else{R.n=D.n;R.len=i=>R.cum[i+1]-R.cum[i];R.cum=[0];D.txt.forEach((s,i)=>R.cum.push(R.cum[i]+s.length));k.total=Math.max(1,Math.ceil(R.cum[R.n]/PG));R.toc=D.toc;await showCh(Math.min(k.u||0,R.n-1),null,k.sf)}
if(!k.cv&&!cvTry.has(k.id)){cvTry.add(k.id);mkCover(k).then(c=>{k.cv=c;save()})}}
function closeBook(){saveProg();try{R.doc.destroy?.()}catch{}R.urls?.forEach(u=>URL.revokeObjectURL(u));CSS.highlights?.delete("bs");if(document.body.classList.contains("fs"))fsToggle();book=null;R=null;$("#viewer").hidden=true;$("#viewer").innerHTML="";$("#shelf").hidden=false;save();lib()}
function saveProg(){if(!book||!R)return;if(R.kind=="rf"){const p=$("#pg");book.u=R.u;book.sf=+(p.scrollTop/Math.max(1,p.scrollHeight-p.clientHeight)).toFixed(4)}}
const vp=()=>{const p=$("#pg"),f=p.scrollTop/Math.max(1,p.scrollHeight-p.clientHeight);return Math.floor((R.cum[R.u]+f*R.len(R.u))/PG)+1};
function prog(){if(!book||!R)return;if(R.kind=="rf"){const v=Math.min(book.total,vp());book.page=v;book.max=Math.max(book.max||0,v);$("#pn").value=v}saveProg();clearTimeout(prog.t);prog.t=setTimeout(save,1500)}
// PDF
async function drawPdf(){const my=++R.tk,p=await R.doc.getPage(R.u),d=devicePixelRatio||1,rot=(p.rotate+R.rot)%360,vw=p.getViewport({scale:sc,rotation:rot}),vq=p.getViewport({scale:sc*d,rotation:rot}),c=$("#cv"),w=$("#pgw");
c.width=vq.width;c.height=vq.height;c.style.width=vw.width+"px";c.style.height=vw.height+"px";w.style.width=vw.width+"px";w.style.height=vw.height+"px";R.rt?.cancel();R.rt=p.render({canvasContext:c.getContext("2d"),viewport:vq});try{await R.rt.promise}catch{return}if(my!=R.tk)return;
const tl=$("#tlyr");tl.replaceChildren();tl.style.setProperty("--scale-factor",sc);tl.style.width=vw.width+"px";tl.style.height=vw.height+"px";
book.page=R.u;book.max=Math.max(book.max||0,R.u);$("#pn").value=R.u;$("#pt").textContent="/ "+R.n;const pg=$("#pg");pg.scrollTop=R.bottom?1e9:0;R.bottom=false;drawHl();prog();save();
const tlr=new pj.TextLayer({textContentSource:p.streamTextContent(),container:tl,viewport:vw});tlr.render().then(()=>{if(my==R.tk&&R.q)markQ()}).catch(()=>{})}
function goPdf(n){if(n>=1&&n<=R.n){R.u=n;drawPdf()}}
// reflowable
async function fixImgs(el,from){for(const i of el.querySelectorAll("img,image")){const a=i.hasAttribute("recindex")?null:["src","href","xlink:href"].find(x=>i.hasAttribute(x));let u=null;if(i.hasAttribute("recindex"))u=R.doc.mimg?.(+i.getAttribute("recindex"));else if(a){const s=i.getAttribute(a);if(/^(data|blob):/.test(s))continue;u=/^https?:/.test(s)?null:await R.doc.img(from,s)}
if(u){R.urls.push(u);i.setAttribute(i.tagName=="image"?"href":"src",u)}else if(i.tagName=="IMG")i.removeAttribute("src")}}
async function showCh(i,frag,sf){const my=++R.tk;R.u=i;const{h,path}=await R.doc.ch(i),rf=$("#rf");R.cp=path;rf.replaceChildren(...clean(h||"").childNodes);await fixImgs(rf,path);if(my!=R.tk)return;applyHl();const pg=$("#pg");$("#pt").textContent="/ "+book.total;
if(frag){try{rf.querySelector("#"+CSS.escape(frag))?.scrollIntoView()}catch{}}else pg.scrollTop=R.bottom?1e9:sf?sf*Math.max(0,pg.scrollHeight-pg.clientHeight):0;R.bottom=false;if(R.q)markQ();prog()}
function goVP(n){let i=0;const tgt=(Math.max(1,n)-1)*PG;while(i<R.n-1&&R.cum[i+1]<=tgt)i++;showCh(i,null,Math.min(1,(tgt-R.cum[i])/Math.max(1,R.cum[i+1]-R.cum[i])))}
const nextU=()=>R.kind=="pdf"?goPdf(R.u+1):R.u<R.n-1&&showCh(R.u+1),prevU=bot=>{R.bottom=!!bot;R.kind=="pdf"?(R.u>1?goPdf(R.u-1):R.bottom=false):R.u>0?showCh(R.u-1):R.bottom=false};
function step(dir,big){const p=$("#pg"),a=Math.round(p.clientHeight*(big?.9:.3));if(dir>0){p.scrollTop+p.clientHeight>=p.scrollHeight-4?nextU():p.scrollBy({top:a,behavior:"smooth"})}else p.scrollTop<=0?prevU(true):p.scrollBy({top:-a,behavior:"smooth"})}
function zoom(d){if(R.kind=="pdf"){sc=Math.max(.4,Math.min(4,sc+d*.2));drawPdf()}else{const r=$("#rf"),f=Math.max(12,Math.min(34,(parseFloat(r.style.fontSize)||18)+d*2));r.style.fontSize=f+"px"}}
async function fit(pageMode){const p=await R.doc.getPage(R.u),v=p.getViewport({scale:1,rotation:(p.rotate+R.rot)%360}),g=$("#pg");sc=pageMode?Math.min((g.clientWidth-40)/v.width,(g.clientHeight-20)/v.height):(g.clientWidth-40)/v.width;drawPdf()}
function fsToggle(){const on=!document.body.classList.contains("fs");document.body.classList.toggle("fs",on);try{TR.window.getCurrentWindow().setFullscreen(on)}catch{}}
// TOC
function tocShow(){const a=$("#toc");a.hidden=!a.hidden;if(a.hidden)return;a.innerHTML=R.toc?.length?R.toc.map((e,i)=>`<a data-ti="${i}" style="padding-left:${e.d*12}px">${esc(e.t)}</a>`).join(""):`<small>${t("notoc")}</small>`}
async function tocGo(i){const e=R.toc[i];if(R.kind=="rf")return showCh(e.u,e.f);try{const D=typeof e.dest=="string"?await R.doc.getDestination(e.dest):e.dest;if(!D)return;const r=D[0];goPdf(typeof r=="object"?await R.doc.getPageIndex(r)+1:r+1)}catch{}}
// selection → highlight / copy to note
const off=(root,n,o)=>{const r=document.createRange();r.selectNodeContents(root);r.setEnd(n,o);return r.toString().length};
function selInfo(){const s=getSelection();if(!s.rangeCount||s.isCollapsed)return null;const r=s.getRangeAt(0),root=R.kind=="pdf"?$("#tlyr"):$("#rf");if(!root.contains(r.commonAncestorContainer))return null;const h={id:uid(),u:R.u,t:s.toString().replace(/\s+/g," ").trim(),ts:Date.now()};if(!h.t)return null;
if(R.kind=="pdf"){h.pg=R.u;const w=$("#pgw").getBoundingClientRect();h.r=[...r.getClientRects()].filter(q=>q.width>1&&q.height>1).map(q=>[(q.left-w.left)/w.width,(q.top-w.top)/w.height,q.width/w.width,q.height/w.height].map(v=>+v.toFixed(4)))}
else{h.o=[off(root,r.startContainer,r.startOffset),off(root,r.endContainer,r.endOffset)];h.pg=Math.floor((R.cum[R.u]+h.o[0])/PG)+1}return h}
function selTb(){const s=getSelection(),b=$("#stb");if(!b||!R||s.isCollapsed||!selInfo()){if(b)b.hidden=true;return}const q=s.getRangeAt(0).getBoundingClientRect();b.hidden=false;b.style.left=Math.max(8,q.left)+"px";b.style.top=Math.max(8,q.top-42)+"px"}
function hlAdd(c){const h=selInfo();if(!h)return;h.c=HC[c];(book.hl??=[]).push(h);getSelection().removeAllRanges();$("#stb").hidden=true;save();R.kind=="pdf"?drawHl():wrapOff($("#rf"),h);panel()}
function wrapOff(root,h){const[s,e]=h.o,w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),Ls=[];let n,p=0;while(n=w.nextNode()){const l=n.data.length;if(p+l>s&&p<e)Ls.push([n,Math.max(0,s-p),Math.min(l,e-p)]);p+=l}
Ls.reverse().forEach(([n,a,b])=>{const r=new Range();r.setStart(n,a);r.setEnd(n,b);const m=document.createElement("mark");m.dataset.h=h.id;m.style.background=h.c;r.surroundContents(m)})}
function applyHl(){(book.hl||[]).filter(h=>h.u==R.u&&h.o).forEach(h=>wrapOff($("#rf"),h))}
function drawHl(){if(R.kind!="pdf")return;$("#hl").innerHTML=(book.hl||[]).filter(h=>h.u==R.u&&h.r).map(h=>h.r.map(([x,y,w,v])=>`<i style="left:${x*100}%;top:${y*100}%;width:${w*100}%;height:${v*100}%;background:${h.c}"></i>`).join("")).join("")}
function toNote(items){const nm=book.name,H=items.map(i=>`<blockquote>${esc(i.t)}</blockquote><p><i>— ${esc(nm)}, ${t("pages")} ${i.pg}</i></p>`).join("");let n=db.notes.find(z=>z.id==XN[book.id]);
if(!n){let c=cat!="all"&&!db.cats.find(z=>z.id==cat)?.lk?cat:db.cats.find(z=>!z.lk)?.id;if(!c){c=uid();db.cats.push({id:c,name:t("extracts")})}n={id:uid(),cat:c,title:nm+" — "+t("extracts"),html:H,ts:Date.now(),cr:Date.now()};db.notes.push(n);XN[book.id]=n.id}else{n.html+=H;n.ts=Date.now()}
save();if(cur==n.id)ed.innerHTML=n.html;list();toast("📝 "+t("copied")+": "+n.title)}
// panel: highlights, bookmarks, search
function panel(mode){const m=$("#mk");if(mode)R.pm=mode;if(R.pm==null)return;m.hidden=false;const k=book,hl=(k.hl||[]).slice().sort((a,b)=>a.u-b.u||a.ts-b.ts),bm=(k.bm||[]).slice().sort((a,b)=>a.pg-b.pg);
const item=(c,pg,tx,id,x)=>`<div class="mi" data-${c}="${id}"><small>${t("pages")} ${pg}</small>${esc(tx)}<div>${x}</div></div>`;
let h=`<div class="sg"><input id="sq" type="text" placeholder="${t("s_ph")}" value="${esc(R.q||"")}" style="flex:1;min-width:0"></div><div id="sr">${R.sr||""}</div><b>${t("hl_h")}</b>`+(hl.length?hl.map(x=>item("j",x.pg,x.t.slice(0,160),x.id,`<button data-nt="${x.id}" title="${t("h_note")}">📝</button> <button data-rh="${x.id}">✕</button>`)).join("")+`<p><button data-hall>${t("hl_all")}</button></p>`:`<p><small>${t("hlnone")}</small></p>`)+`<b>${t("bm_h")}</b>`+bm.map(x=>item("jb",x.pg,x.l||"",x.id,`<button data-rb="${x.id}">✕</button>`)).join("");m.innerHTML=h;
$("#sq").onkeydown=e=>{if(e.key=="Enter")search($("#sq").value)}}
async function jump(u,sel){if(R.u!=u){R.kind=="pdf"?(R.u=u,await drawPdf()):await showCh(u)}if(sel)setTimeout(()=>{const el=R.kind=="rf"?$(`#rf mark[data-h="${sel}"]`):null;el?.scrollIntoView({block:"center"});const h=book.hl.find(z=>z.id==sel);if(R.kind=="pdf"&&h?.r?.[0])$("#pg").scrollTop=h.r[0][1]*$("#pgw").offsetHeight-80},60)}
function bmToggle(){const pg=R.kind=="pdf"?R.u:vp(),a=book.bm??=[],i=a.findIndex(b=>b.pg==pg);if(i>=0)a.splice(i,1);else a.push({id:uid(),u:R.u,pg,l:R.kind=="rf"?(R.toc?.filter(e=>e.u<=R.u).pop()?.t||t("pages")+" "+pg):t("pages")+" "+pg,ts:Date.now()});save();toast(i>=0?"🔖 ✕":"🔖 "+t("pages")+" "+pg);panel()}
async function search(q){q=q.trim();R.q=q;CSS.highlights?.delete("bs");const my=R.sk=(R.sk||0)+1;if(!q){R.sr="";return panel("s")}R.sr=`<small>${t("s_busy")}</small>`;panel("s");const lq=q.toLowerCase(),out=[];
const add=(u,pg,s,i)=>out.push(`<div class="mi" data-su="${u}"><small>${t("pages")} ${pg}</small>${esc(s.slice(Math.max(0,i-40),i+q.length+60))}</div>`);
if(R.kind=="rf"){R.doc.txt.forEach((s,u)=>{const l=s.toLowerCase();let i=-1,c=0;while((i=l.indexOf(lq,i+1))>-1&&c++<5&&out.length<200)add(u,Math.floor((R.cum[u]+i)/PG)+1,s,i)})}
else for(let p=1;p<=R.n&&out.length<200;p++){if(my!=R.sk)return;const tc=await(await R.doc.getPage(p)).getTextContent(),s=tc.items.map(i=>i.str).join(" "),l=s.toLowerCase();let i=-1,c=0;while((i=l.indexOf(lq,i+1))>-1&&c++<3)add(p,p,s,i)}
if(my!=R.sk)return;R.sr=out.join("")||`<small>${t("s_none")}</small>`;panel("s");markQ()}
function markQ(){const rs=findRanges(R.kind=="pdf"?$("#tlyr"):$("#rf"),R.q,"bs");rs[0]?.startContainer.parentElement?.scrollIntoView({block:"center"})}
// ---------- events ----------
document.addEventListener("click",async e=>{const q=s=>e.target.closest(s);if(q("#addpdf"))return addDialog();
if(q("[data-lv]")){db.lib.view=q("[data-lv]").dataset.lv;save();return lib()}
if(q("[data-cfn]")){CI+=+q("[data-cfn]").dataset.cfn;return cfPos()}
if(q("[data-ci]")){const i=+q("[data-ci]").dataset.ci;if(i==CI)return openBook(db.lib.books[i].id);CI=i;return cfPos()}
if(q("[data-rm]")){const id=q("[data-rm]").dataset.rm;if(await ask(t("confirm"))){db.lib.books=db.lib.books.filter(z=>z.id!=id);save();lib()}return}
if(q("[data-bk]"))return openBook(q("[data-bk]").dataset.bk);
if(!R||$("#viewer").hidden)return;const v=q("[data-v]")?.dataset.v;
if(v){({back:closeBook,toc:tocShow,prev:()=>prevU(),next:nextU,zin:()=>zoom(1),zout:()=>zoom(-1),fitw:()=>fit(0),fitp:()=>fit(1),rot:()=>{R.rot=(R.rot+90)%360;drawPdf()},night:()=>$("#pg").classList.toggle("night"),search:()=>{panel("s");$("#sq").focus()},mark:bmToggle,panel:()=>{$("#mk").hidden=!$("#mk").hidden;if(!$("#mk").hidden)panel("m")},fs:fsToggle})[v]?.();return}
if(q("[data-ti]"))return tocGo(+q("[data-ti]").dataset.ti);
if(q("[data-hc]"))return hlAdd(q("[data-hc]").dataset.hc);
if(q("[data-hn]")){const h=selInfo();if(h){toNote([h]);getSelection().removeAllRanges();$("#stb").hidden=true}return}
if(q("[data-nt]")){const h=book.hl.find(z=>z.id==q("[data-nt]").dataset.nt);h&&toNote([h]);return}
if(q("[data-hall]"))return toNote(book.hl.slice().sort((a,b)=>a.u-b.u||a.ts-b.ts));
if(q("[data-rh]")){const id=q("[data-rh]").dataset.rh;book.hl=book.hl.filter(z=>z.id!=id);save();R.kind=="pdf"?drawHl():showCh(R.u,null,$("#pg").scrollTop/Math.max(1,$("#pg").scrollHeight-$("#pg").clientHeight));return panel()}
if(q("[data-rb]")){book.bm=book.bm.filter(z=>z.id!=q("[data-rb]").dataset.rb);save();return panel()}
if(q("[data-j]"))return jump(book.hl.find(z=>z.id==q("[data-j]").dataset.j).u,q("[data-j]").dataset.j);
if(q("[data-jb]")){const b=book.bm.find(z=>z.id==q("[data-jb]").dataset.jb);return jump(b.u)}
if(q("[data-su]")){await jump(+q("[data-su]").dataset.su);markQ()}});
addEventListener("keydown",e=>{if(tab=="lib"&&!book&&!e.target.matches("input,textarea")&&db.lib.view!="list"){if(e.key=="ArrowRight"){CI++;cfPos()}if(e.key=="ArrowLeft"){CI--;cfPos()}if(e.key=="Enter"&&db.lib.books[CI])openBook(db.lib.books[CI].id)}
if(!book||!R||$("#viewer").hidden||tab!="lib"||$("#lib").hidden||e.target.isContentEditable)return;const k=e.key,K=k.toLowerCase(),inp=e.target.matches("input,textarea,select"),h=f=>{e.preventDefault();e.stopImmediatePropagation();f()};
if(e.ctrlKey){if(K=="l")return h(fsToggle);if(K=="f")return h(()=>{panel("s");$("#sq").focus()});if(K=="d")return h(bmToggle);if(k=="+"||k=="=")return h(()=>zoom(1));if(k=="-")return h(()=>zoom(-1));return}
if(k=="Escape"){if(document.body.classList.contains("fs"))fsToggle();else $("#stb").hidden=true;return}if(inp)return;
if(k=="ArrowDown")return h(()=>step(1));if(k=="ArrowUp")return h(()=>step(-1));if(k==" ")return h(()=>step(e.shiftKey?-1:1,true));if(k=="ArrowRight"||k=="PageDown")return h(nextU);if(k=="ArrowLeft"||k=="PageUp")return h(()=>prevU());
if(k=="Home")return h(()=>R.kind=="pdf"?goPdf(1):showCh(0));if(k=="End")return h(()=>R.kind=="pdf"?goPdf(R.n):showCh(R.n-1));if(K=="t")return h(tocShow);if(K=="h")return h(()=>hlAdd("y"))},true);
setInterval(()=>{if(book&&document.hasFocus()&&!$("#lib").hidden){book.secs=(book.secs||0)+1;db.lib.secs++;if(db.lib.secs%10==0){saveProg();save()}}},1000);
