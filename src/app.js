const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)],TR=window.__TAURI__;
const L={en:{notes:"Notes",tasks:"Tasks & Planner",newcat:"+ Category",about:"About",all:"All notes",newnote:"+ New note",search:"Search…",title:"Title",add:"Add",untitled:"Untitled",tdesc:"New task…",close:"Close",by:"Made by",tt:"Terms: Notia is provided “as is”, without warranty. Your notes stay on this device; nothing is sent online.",catname:"Category name",cats:["Prompts","App ideas","Books to read","Movies to watch"],confirm:"Delete?",none:"No date",due:"Reminder"},
fr:{notes:"Notes",tasks:"Tâches & Planning",newcat:"+ Catégorie",about:"À propos",all:"Toutes les notes",newnote:"+ Nouvelle note",search:"Rechercher…",title:"Titre",add:"Ajouter",untitled:"Sans titre",tdesc:"Nouvelle tâche…",close:"Fermer",by:"Créé par",tt:"Conditions : Notia est fournie « telle quelle », sans garantie. Vos notes restent sur cet appareil ; rien n’est envoyé en ligne.",catname:"Nom de la catégorie",cats:["Prompts","Idées d’apps","Livres à lire","Films à voir"],confirm:"Supprimer ?",none:"Sans date",due:"Rappel"}};
const lg=navigator.language.startsWith("fr")?"fr":"en";
const FRESH=!localStorage.notia;let db;try{db=JSON.parse(localStorage.notia)}catch{}
const SEALED=!!db?.sealed,PREF=(()=>{try{return JSON.parse(localStorage.notia_pref)}catch{return{}}})();
if(SEALED)db={lang:PREF.lang||lg,theme:PREF.theme||"light",cats:[],notes:[],todos:[],tour:1};
db=db||{lang:lg,theme:"light",cats:L[lg].cats.map((n,i)=>({id:"c"+i,name:n})),notes:[],todos:[]};
let cat="all",cur=null,tab="notes";const ed=$("#ed"),t=k=>L[db.lang][k],uid=()=>Date.now().toString(36)+Math.random().toString(36).slice(2,5);
function save(){try{localStorage.notia=JSON.stringify(db);localStorage.notia_pref=JSON.stringify({lang:db.lang,theme:db.theme,priv:PREF.priv})}catch{toast("Storage error")}}
const addL=S=>{for(const k in S){L.en[k]=S[k][0];L.fr[k]=S[k][1]}},VER="1.2.0",DONATE="https://srichard.site/donate";
let hid=n=>false,gate=async()=>true;
const p2=v=>String(v).padStart(2,"0"),fd=ts=>{const d=new Date(ts);return p2(d.getDate())+"/"+p2(d.getMonth()+1)+"/"+p2(d.getFullYear()%100)},fdt=ts=>{const d=new Date(ts);return fd(ts)+" "+p2(d.getHours())+":"+p2(d.getMinutes())};
const ask=m=>new Promise(r=>{const d=document.createElement("dialog");d.innerHTML=`<p>${esc(m)}</p><div class="sg"><button class="pri" id="ay">${t("yes")}</button><button id="an">${t("no")}</button></div>`;document.body.append(d);d.onclose=()=>{d.remove();r(d.returnValue=="y")};d.querySelector("#ay").onclick=()=>d.close("y");d.querySelector("#an").onclick=()=>d.close("n");d.showModal()});
const esc=s=>s.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;"),cur_=()=>db.notes.find(n=>n.id==cur);
function toast(m){const e=$("#toast");e.textContent=m;e.style.display="block";setTimeout(()=>e.style.display="none",4000)}
function render(){document.documentElement.dataset.theme=db.theme;$$("[data-i]").forEach(e=>e.textContent=t(e.dataset.i));$("#lang").textContent=db.lang.toUpperCase();
$("#q").placeholder=t("search");$("#ttl").placeholder=t("title");$("#nt").placeholder=t("tdesc");$("#notes").hidden=tab!="notes";$("#tasks").hidden=tab!="tasks";
$$("nav button").forEach(b=>b.classList.toggle("on",b.dataset.tab==tab));
$("#cats").innerHTML=[{id:"all",name:t("all")},...db.cats].map(c=>`<button data-cat="${c.id}" class="${c.id==cat?"on":""}">${esc(c.name)}${c.id=="all"?"":` <span data-lk="${c.id}">${c.lk?"🔒":"🔓"}</span><span data-dc="${c.id}">✕</span>`}</button>`).join("");list();tasks()}
function list(){const q=$("#q").value.toLowerCase();$("#items").innerHTML=db.notes.filter(n=>!hid(n)&&(cat=="all"||n.cat==cat)&&(n.title+n.html).toLowerCase().includes(q)).sort((a,b)=>b.ts-a.ts)
.map(n=>`<div class="it ${n.id==cur?"on":""}" data-n="${n.id}">${esc(n.title||t("untitled"))}<small>${fdt(n.ts)}</small></div>`).join("")}
function open(id){cur=id;const n=cur_();$("#ttl").value=n?n.title:"";ed.innerHTML=n?n.html:"";ed.contentEditable=!!n;list()}
function touch(){const n=cur_();if(!n)return;n.title=$("#ttl").value;n.html=ed.innerHTML;n.ts=Date.now();save();clearTimeout(touch.t);touch.t=setTimeout(list,400)}
const x=(c,v)=>{ed.focus();document.execCommand(c,false,v);touch()};
function tok(s){return esc(s).replace(/(\/\/.*|#.*|\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`[^`]*`)|\b(\d+\.?\d*)\b|\b(const|let|var|function|return|if|else|for|while|class|import|from|export|def|in|new|async|await|try|catch|fn|pub|struct|impl|use|true|false|null|None|self|this|print)\b/g,(m,c,s,n,k)=>`<span class="${c?"tc":s?"ts":n?"tn":"tk"}">${m}</span>`)}
const isCode=s=>/\n/.test(s)&&(s.match(/[{};()<>]|=>|\bdef |\bfunction\b|\bimport /g)||[]).length>=4;
function img(f){const r=new FileReader();r.onload=()=>x("insertImage",r.result);r.readAsDataURL(f)}
ed.addEventListener("paste",e=>{const f=[...e.clipboardData.files].find(f=>f.type.startsWith("image"));if(f){e.preventDefault();return img(f)}
const s=e.clipboardData.getData("text/plain");if(isCode(s)){e.preventDefault();x("insertHTML",`<pre class="code">${tok(s)}</pre><br>`)}});
ed.oninput=$("#ttl").oninput=touch;$("#q").oninput=list;
const dec=s=>{const a=document.createElement("textarea");a.innerHTML=s;return a.value};
const md=h=>dec(h.replace(/<pre[^>]*>([\s\S]*?)<\/pre>/gi,(m,c)=>"\n```\n"+c.replace(/<[^>]+>/g,"")+"\n```\n").replace(/<(b|strong)[^>]*>(.*?)<\/\1>/gis,"**$2**").replace(/<(i|em)[^>]*>(.*?)<\/\1>/gis,"*$2*").replace(/<h1[^>]*>/gi,"# ").replace(/<h2[^>]*>/gi,"## ").replace(/<li[^>]*>/gi,"- ").replace(/<br\s*\/?>|<\/(div|p|h\d|li|tr)>/gi,"\n").replace(/<img[^>]*>/gi,"![image]").replace(/<(?!\/?pre)[^>]+>/g,""));
function crc(b){let r=-1;for(let i=0;i<b.length;i++){let c=(r^b[i])&255;for(let k=0;k<8;k++)c=c&1?(c>>>1)^0xEDB88320:c>>>1;r=(r>>>8)^c}return(r^-1)>>>0}
function zip(files){const E=new TextEncoder(),P=[],D=[];let o=0;for(const[n,s]of files){const nb=E.encode(n),d=E.encode(s),c=crc(d);
const h=new DataView(new ArrayBuffer(30));h.setUint32(0,0x04034b50,true);h.setUint16(4,20,true);h.setUint32(14,c,true);h.setUint32(18,d.length,true);h.setUint32(22,d.length,true);h.setUint16(26,nb.length,true);P.push(new Uint8Array(h.buffer),nb,d);
const g=new DataView(new ArrayBuffer(46));g.setUint32(0,0x02014b50,true);g.setUint16(4,20,true);g.setUint16(6,20,true);g.setUint32(16,c,true);g.setUint32(20,d.length,true);g.setUint32(24,d.length,true);g.setUint16(28,nb.length,true);g.setUint32(42,o,true);D.push(new Uint8Array(g.buffer),nb);o+=30+nb.length+d.length}
const cl=D.reduce((s,a)=>s+a.length,0),e=new DataView(new ArrayBuffer(22));e.setUint32(0,0x06054b50,true);e.setUint16(8,files.length,true);e.setUint16(10,files.length,true);e.setUint32(12,cl,true);e.setUint32(16,o,true);return new Blob([...P,...D,new Uint8Array(e.buffer)])}
function docx(title,text){const p=(s,b)=>`<w:p><w:r>${b?"<w:rPr><w:b/></w:rPr>":""}<w:t xml:space="preserve">${esc(s)}</w:t></w:r></w:p>`,R="http://schemas.openxmlformats.org/";
return zip([["[Content_Types].xml",`<Types xmlns="${R}package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>`],
["_rels/.rels",`<Relationships xmlns="${R}package/2006/relationships"><Relationship Id="r1" Type="${R}officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>`],
["word/document.xml",`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="${R}wordprocessingml/2006/main"><w:body>${p(title,1)}${text.split("\n").map(l=>p(l)).join("")}</w:body></w:document>`]])}
async function exp(k){const n=cur_();if(!n||!k)return;const nm=(n.title||t("untitled")).replace(/[\\/:*?"<>|]/g,"_"),T=n.title||t("untitled");
const b=k=="txt"?new Blob([T+"\n\n"+ed.innerText]):k=="md"?new Blob(["# "+T+"\n\n"+md(ed.innerHTML)]):docx(T,ed.innerText);
try{await TR.core.invoke("save_file",{name:nm+"."+k,data:Array.from(new Uint8Array(await b.arrayBuffer()))})}catch(e){toast(String(e))}}
const open_=u=>TR?TR.opener.openUrl(u):window.open(u);
function tasks(){const g={};db.todos.slice().sort((a,b)=>(a.due||"9").localeCompare(b.due||"9")).forEach(o=>(g[o.due?o.due.slice(0,10):t("none")]??=[]).push(o));
$("#tl").innerHTML=Object.entries(g).map(([d,a])=>`<h4>${d.length==10?new Date(d+"T12:00").toLocaleDateString(db.lang,{weekday:"long",day:"numeric",month:"long"}):d}</h4>`+a.map(o=>`<div class="td ${o.done?"dn":""}"><input type="checkbox" data-td="${o.id}" ${o.done?"checked":""}><span>${esc(o.text)}</span><small>${o.due?o.due.slice(11):""}</small><button data-dt="${o.id}">✕</button></div>`).join("")).join("")}
$("#tf").onsubmit=e=>{e.preventDefault();db.todos.push({id:uid(),text:$("#nt").value,due:$("#nd").value,done:0});$("#tf").reset();save();tasks()};
setInterval(()=>db.todos.forEach(o=>{if(o.due&&!o.done&&!o.n&&new Date(o.due)<=new Date()){o.n=1;save();toast("⏰ "+o.text);try{TR.notification.sendNotification({title:"Notia",body:o.text})}catch{}}}),15000);
try{TR.notification.requestPermission()}catch{}
document.addEventListener("click",e=>{const g=a=>e.target.closest("["+a+"]")?.getAttribute(a),B=e.target.closest("button");
if(g("data-lk"))return;
if(g("data-dc")){const c=g("data-dc"),k=db.notes.filter(n=>n.cat==c).length;(async()=>{if(db.cats.find(z=>z.id==c)?.lk&&!await gate())return;if(k?!(await ask(t("delc1").replace("{n}",k))&&await ask(t("delc2"))):!await ask(t("confirm")))return;db.cats=db.cats.filter(z=>z.id!=c);db.notes=db.notes.filter(n=>n.cat!=c);cat="all";cur=null;open(null);save();render()})();return}
if(g("data-cat")){(async()=>{const c=g("data-cat");if(db.cats.find(z=>z.id==c)?.lk&&!await gate())return;cat=c;tab="notes";render()})()}else if(g("data-n")){open(g("data-n"))}else if(g("data-td")){const o=db.todos.find(z=>z.id==g("data-td"));o.done=o.done?0:1;save();tasks()}else if(g("data-dt")){db.todos=db.todos.filter(z=>z.id!=g("data-dt"));save();tasks()}
else if(B?.dataset.tab){tab=B.dataset.tab;render()}
else if(B?.id=="theme"){db.theme=db.theme=="dark"?"light":"dark";save();render()}else if(B?.id=="lang"){db.lang=db.lang=="fr"?"en":"fr";save();render()}
else if(B?.id=="addcat"){const n=prompt(t("catname"));if(n?.trim()){db.cats.push({id:uid(),name:n.trim()});save();render()}}
else if(B?.id=="new"){const n={id:uid(),cat:cat=="all"?db.cats[0]?.id:cat,title:"",html:"",ts:Date.now(),cr:Date.now()};if(!n.cat)return;db.notes.push(n);save();open(n.id);$("#ttl").focus();render()}
else if(B?.id=="about"){const d=$("#dlg");d.innerHTML=`<h2>Notia</h2><p>v${VER} — ${t("by")} <a href="https://srichard.site" id="site">Song Richard</a></p><p>${t("tt")}</p><p><button id="don" class="pri">☕ ${t("donate")}</button></p><button id="x">${t("close")}</button>`;d.showModal();$("#site").onclick=v=>{v.preventDefault();open_("https://srichard.site")};$("#don").onclick=()=>open_(DONATE);$("#x").onclick=()=>d.close()}
else if(B?.dataset.c){const c=B.dataset.c;if(!cur&&c!="print")return;({bold:()=>x("bold"),italic:()=>x("italic"),underline:()=>x("underline"),hl:()=>x("hiliteColor","#fde047"),mono:()=>x("fontName","Consolas"),
sm:()=>x("fontSize",Math.max(1,(+document.queryCommandValue("fontSize")||3)-1)),lg:()=>x("fontSize",Math.min(7,(+document.queryCommandValue("fontSize")||3)+1)),img:()=>$("#file").click(),print:()=>window.print(),
mail:()=>open_("mailto:?subject="+encodeURIComponent($("#ttl").value)+"&body="+encodeURIComponent(ed.innerText.slice(0,1500))),
del:async()=>{if(await ask(t("confirm"))){db.notes=db.notes.filter(n=>n.id!=cur);save();open(null)}}})[c]?.()}});
$("#font").onchange=e=>cur&&x("fontName",e.target.value);$("#exp").onchange=e=>{exp(e.target.value);e.target.value=""};$("#file").onchange=e=>e.target.files[0]&&img(e.target.files[0]);
$$(".bar button").forEach(b=>b.onmousedown=e=>e.preventDefault());
render();open(null);
