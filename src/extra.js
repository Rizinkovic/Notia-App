// Notia 1.1 — library/PDF viewer, planner month view, sharing, tutorial, editor extras, shortcuts
Object.assign(L.en,{lib:"Library",tour:"Tutorial",share:"Share",pdfadd:"+ Add PDF",time:"Total reading time",max10:"Library full (10 books max). Remove one first.",back:"← Library",list:"List",month:"Month",start:"Start with Windows (reminders keep working in the tray)",next:"Next",skip:"Skip",done:"Let’s go",wifi:"Wi-Fi (same network)",bt:"Bluetooth",drive:"Google Drive",mail:"Email",fmt:"File format",read:"read",pages:"page",words:"words",
wifihelp:"Open this address in your phone’s browser (same Wi-Fi). If Windows asks, allow Notia on private networks.",bthelp:"Windows Bluetooth wizard opened: choose “Send files”, pick your phone, then select the file shown in Explorer.",drivehelp:"File saved. Google Drive opened: drop the file into your Drive.",empty:"Add a PDF to start. Notia remembers your page, progress and reading time.",
steps:[["Welcome to Notia","Your notes stay on this PC. Pick a category on the left or add your own with “+ Category”."],["Write & format","Click “+ New note”. Use the two toolbars for styles, lists, tables and images. Paste code and it is colorized automatically."],["Tasks & planner","Add tasks with a date and time, switch between List and Month. Reminders keep working when Notia is closed to the tray."],["Library","Add up to 10 PDFs. Notia remembers your page, percentage read and reading time for each book."],["Share & export","Use 📤 to send a note by Wi-Fi, Bluetooth, Telegram, WhatsApp, email or Drive, or export to DOCX, PDF, MD, TXT. Shortcuts: Ctrl+N new note, Ctrl+F search, Ctrl+P print."]]});
Object.assign(L.fr,{lib:"Bibliothèque",tour:"Tutoriel",share:"Partager",pdfadd:"+ Ajouter un PDF",time:"Temps de lecture total",max10:"Bibliothèque pleine (10 livres max). Retirez-en un.",back:"← Bibliothèque",list:"Liste",month:"Mois",start:"Démarrer avec Windows (les rappels restent actifs dans la barre système)",next:"Suivant",skip:"Passer",done:"C’est parti",wifi:"Wi-Fi (même réseau)",bt:"Bluetooth",drive:"Google Drive",mail:"E-mail",fmt:"Format du fichier",read:"lu",pages:"page",words:"mots",
wifihelp:"Ouvrez cette adresse dans le navigateur de votre téléphone (même Wi-Fi). Si Windows le demande, autorisez Notia sur les réseaux privés.",bthelp:"L’assistant Bluetooth de Windows s’est ouvert : choisissez « Envoyer des fichiers », votre téléphone, puis le fichier affiché dans l’Explorateur.",drivehelp:"Fichier enregistré. Google Drive est ouvert : déposez-y le fichier.",empty:"Ajoutez un PDF pour commencer. Notia retient votre page, votre progression et votre temps de lecture.",
steps:[["Bienvenue dans Notia","Vos notes restent sur ce PC. Choisissez une catégorie à gauche ou créez la vôtre avec « + Catégorie »."],["Écrire et mettre en forme","Cliquez sur « + Nouvelle note ». Les deux barres d’outils gèrent styles, listes, tableaux et images. Le code collé est colorisé automatiquement."],["Tâches et planning","Ajoutez des tâches avec date et heure, basculez entre Liste et Mois. Les rappels fonctionnent même quand Notia est réduite dans la barre système."],["Bibliothèque","Ajoutez jusqu’à 10 PDF. Notia retient la page, le pourcentage lu et le temps de lecture de chaque livre."],["Partager et exporter","Utilisez 📤 pour envoyer une note par Wi-Fi, Bluetooth, Telegram, WhatsApp, e-mail ou Drive, ou exportez en DOCX, PDF, MD, TXT. Raccourcis : Ctrl+N nouvelle note, Ctrl+F recherche, Ctrl+P imprimer."]]});
db.lib??={books:[],secs:0};
const enc=encodeURIComponent,fT=s=>Math.floor(s/3600)+"h "+String(Math.floor(s%3600/60)).padStart(2,"0")+"m",I=(c,a)=>TR.core.invoke(c,a);
const _r=render;render=function(){_r();$("#lib").hidden=tab!="lib";lib()};
// ---- Library & PDF viewer
let book,pdf,pg=1,sc=1.3,pj;
function lib(){if($("#lib").hidden||book)return;const b=db.lib.books;$("#shelf").innerHTML=`<div><button id="addpdf" class="pri">${t("pdfadd")}</button> &nbsp; ${t("time")}: <b>${fT(db.lib.secs)}</b> · ${b.length}/10</div>`+(b.length?"":`<p>${t("empty")}</p>`)+b.map(k=>{const p=Math.round((k.max||0)/(k.total||1)*100);return `<div class="bk" data-bk="${k.id}"><b>${esc(k.name)}</b><div class="pb"><i style="width:${p}%"></i></div><small>${p}% ${t("read")} · ${t("pages")} ${k.page||1}/${k.total||"?"} · ${fT(k.secs||0)}</small><button data-rm="${k.id}">✕</button></div>`}).join("")}
async function addPdf(){if(db.lib.books.length>=10)return toast(t("max10"));const p=await TR.dialog.open({filters:[{name:"PDF",extensions:["pdf"]}]});if(!p||db.lib.books.some(k=>k.path==p))return;db.lib.books.push({id:uid(),path:p,name:p.split(/[\\/]/).pop().replace(/\.pdf$/i,""),page:1,max:0,total:0,secs:0});save();lib()}
async function openBook(id){const k=db.lib.books.find(z=>z.id==id);try{pj??=await import("./vendor/pdf.min.mjs");pj.GlobalWorkerOptions.workerSrc="./vendor/pdf.worker.min.mjs";pdf=await pj.getDocument({data:new Uint8Array(await I("read_file",{path:k.path}))}).promise}catch(e){return toast(String(e))}
book=k;k.total=pdf.numPages;pg=Math.min(k.page||1,k.total);$("#shelf").hidden=true;$("#viewer").hidden=false;draw()}
async function draw(){const p=await pdf.getPage(pg),d=devicePixelRatio||1,v=p.getViewport({scale:sc*d}),c=$("#cv");c.width=v.width;c.height=v.height;c.style.width=v.width/d+"px";
await p.render({canvasContext:c.getContext("2d"),viewport:v}).promise;book.page=pg;book.max=Math.max(book.max||0,pg);$("#pn").value=pg;$("#pt").textContent="/ "+book.total;$("#pg").scrollTop=0;save()}
const go=n=>{if(pdf&&n>=1&&n<=book.total){pg=n;draw()}};
$("#pn").onchange=e=>go(+e.target.value);
setInterval(()=>{if(book&&document.hasFocus()&&!$("#lib").hidden){book.secs=(book.secs||0)+1;db.lib.secs++;if(db.lib.secs%10==0)save()}},1000);
// ---- Planner month view
let tv="list",pm=new Date();pm.setDate(1);
const _t=tasks;tasks=function(){$("#vl").classList.toggle("on",tv=="list");$("#vm").classList.toggle("on",tv=="month");if(tv=="list")return _t();
const y=pm.getFullYear(),m=pm.getMonth(),f=(new Date(y,m,1).getDay()+6)%7,n=new Date(y,m+1,0).getDate(),H=[...Array(7)].map((_,i)=>new Date(2024,0,1+i).toLocaleDateString(db.lang,{weekday:"short"}));
let c=H.map(h=>`<b>${h}</b>`).join("")+"<i></i>".repeat(f);
for(let d=1;d<=n;d++){const k=`${y}-${String(m+1).padStart(2,"0")}-${String(d).padStart(2,"0")}`,a=db.todos.filter(o=>o.due?.startsWith(k));c+=`<div class="dy" data-day="${k}"><u>${d}</u>${a.slice(0,3).map(o=>`<small>${o.done?"✓ ":""}${esc(o.text)}</small>`).join("")}${a.length>3?`<small>+${a.length-3}</small>`:""}</div>`}
$("#tl").innerHTML=`<div class="mh"><button data-pm="-1">‹</button><b>${pm.toLocaleDateString(db.lang,{month:"long",year:"numeric"})}</b><button data-pm="1">›</button></div><div class="mg">${c}</div>`};
// ---- Export / PDF writer / share
function mkpdf(T,text){const W=s=>s.replace(/[^\x20-\xff]/g,"?").replace(/[\\()]/g,"\\$&"),L=[];[T,"",...text.split("\n")].forEach(p=>{p=p.replace(/\s+$/,"");do{L.push(p.slice(0,85));p=p.slice(85)}while(p.length)});
const P=[];for(let i=0;i<L.length;i+=54)P.push(L.slice(i,i+54));
const o=["<</Type/Catalog/Pages 2 0 R>>",`<</Type/Pages/Kids[${P.map((_,i)=>4+2*i+" 0 R").join(" ")}]/Count ${P.length}>>`,"<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>"];
P.forEach((p,i)=>{const c="BT/F1 11 Tf 14 TL 50 800 Td\n"+p.map(l=>`(${W(l)})'`).join("\n")+"\nET";o.push(`<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<</Font<</F1 3 0 R>>>>/Contents ${5+2*i} 0 R>>`,`<</Length ${c.length}>>stream\n${c}\nendstream`)});
let s="%PDF-1.4\n";const X=[];o.forEach((b,i)=>{X.push(s.length);s+=`${i+1} 0 obj\n${b}\nendobj\n`});const xr=s.length;
s+=`xref\n0 ${o.length+1}\n0000000000 65535 f \n`+X.map(v=>String(v).padStart(10,"0")+" 00000 n \n").join("")+`trailer<</Size ${o.length+1}/Root 1 0 R>>\nstartxref\n${xr}\n%%EOF`;return Uint8Array.from(s,c=>c.charCodeAt(0))}
async function pack(k){const n=cur_(),T=n.title||t("untitled"),nm=T.normalize("NFD").replace(/[\u0300-\u036f]/g,"").replace(/[^\w .-]/g,"_")+"."+k,E=new TextEncoder(),tx=ed.innerText;
return [nm,k=="txt"?E.encode(T+"\n\n"+tx):k=="md"?E.encode("# "+T+"\n\n"+md(ed.innerHTML)):k=="pdf"?mkpdf(T,tx):new Uint8Array(await docx(T,tx).arrayBuffer())]}
async function exp(k){if(!cur_()||!k)return;try{const[nm,d]=await pack(k);await I("save_file",{name:nm,data:[...d]})}catch(e){toast(String(e))}}
function shareDlg(){if(!cur)return;const d=$("#dlg");d.innerHTML=`<h3>${t("share")}</h3><label>${t("fmt")} <select id="sf"><option>md</option><option>pdf</option><option>txt</option></select></label><div class="sg"><button data-sh="wifi">📶 ${t("wifi")}</button><button data-sh="bt">🔵 ${t("bt")}</button><button data-sh="tg">✈️ Telegram</button><button data-sh="wa">💬 WhatsApp</button><button data-sh="mail">✉️ ${t("mail")}</button><button data-sh="drive">☁️ ${t("drive")}</button></div><p id="shm"></p><button id="x">${t("close")}</button>`;d.showModal();d.onclose=()=>{try{I("share_stop")}catch{}};$("#x").onclick=()=>d.close()}
async function share(k){const n=cur_(),T=n.title||t("untitled"),f=$("#sf").value,[nm,d]=await pack(f),m=$("#shm"),body=enc((f=="txt"?ed.innerText:"# "+T+"\n\n"+md(ed.innerHTML)).slice(0,3000));
try{if(k=="wifi")m.innerHTML=`<b>${await I("share_start",{files:[[nm,[...d]]]})}</b><br>${t("wifihelp")}`;
else if(k=="bt"){await I("bluetooth_send",{name:nm,data:[...d]});m.textContent=t("bthelp")}
else if(k=="drive"){await I("save_file",{name:nm,data:[...d]});open_("https://drive.google.com/drive/my-drive");m.textContent=t("drivehelp")}
else open_(k=="tg"?`https://t.me/share/url?url=%20&text=${body}`:k=="wa"?`https://wa.me/?text=${body}`:`mailto:?subject=${enc(T)}&body=${body}`)}catch(e){toast(String(e))}}
// ---- Tutorial
function tour(i=0){const s=t("steps"),d=$("#dlg"),last=i==s.length-1;d.innerHTML=`<h2>${s[i][0]}</h2><p>${s[i][1]}</p><div class="sg"><button id="sk">${t("skip")}</button><button class="pri" id="nx">${last?t("done"):t("next")}</button></div><small>${i+1}/${s.length}</small>`;if(!d.open)d.showModal();$("#sk").onclick=()=>d.close();$("#nx").onclick=()=>last?d.close():tour(i+1);db.tour=1;save()}
// ---- Editor extras
const E={h1:()=>x("formatBlock","h1"),h2:()=>x("formatBlock","h2"),p:()=>x("formatBlock","p"),ul:()=>x("insertUnorderedList"),ol:()=>x("insertOrderedList"),quote:()=>x("formatBlock","blockquote"),left:()=>x("justifyLeft"),center:()=>x("justifyCenter"),right:()=>x("justifyRight"),link:()=>{const u=prompt("URL");u&&x("createLink",u)},hr:()=>x("insertHorizontalRule"),strike:()=>x("strikeThrough"),check:()=>x("insertHTML","☐&nbsp;"),table:()=>x("insertHTML","<table class=tb>"+"<tr><td>&nbsp;</td><td>&nbsp;</td><td>&nbsp;</td></tr>".repeat(3)+"</table><br>"),undo:()=>x("undo"),redo:()=>x("redo"),clear:()=>x("removeFormat"),share:shareDlg,tour:()=>tour()};
$$("#bar2 button,[data-e]").forEach(b=>b.onmousedown=e=>e.preventDefault());
$("#col").oninput=e=>cur&&x("foreColor",e.target.value);
ed.addEventListener("click",e=>{const a=e.target.closest("a");if(a){e.preventDefault();open_(a.href)}});
ed.addEventListener("input",()=>$("#wc").textContent=ed.innerText.trim().split(/\s+/).filter(Boolean).length+" "+t("words"));
// ---- Global click / keys
document.addEventListener("click",async e=>{const q=s=>e.target.closest(s),v=q("[data-v]")?.dataset.v;
if(q("[data-e]")){const k=q("[data-e]").dataset.e;if(cur||k=="tour")E[k]?.();return}
if(q("[data-sh]"))return share(q("[data-sh]").dataset.sh);
if(q("[data-rm]")){db.lib.books=db.lib.books.filter(z=>z.id!=q("[data-rm]").dataset.rm);save();return lib()}
if(q("[data-bk]"))return openBook(q("[data-bk]").dataset.bk);
if(q("#addpdf"))return addPdf();
if(q("[data-tv]")){tv=q("[data-tv]").dataset.tv;return tasks()}
if(q("[data-pm]")){pm.setMonth(pm.getMonth()+ +q("[data-pm]").dataset.pm);return tasks()}
if(q("[data-day]")){$("#nd").value=q("[data-day]").dataset.day+"T09:00";return $("#nt").focus()}
if(v&&pdf){if(v=="prev")go(pg-1);if(v=="next")go(pg+1);if(v=="zin"){sc=Math.min(4,sc+.2);draw()}if(v=="zout"){sc=Math.max(.4,sc-.2);draw()}if(v=="fit"){const p=await pdf.getPage(pg);sc=($("#pg").clientWidth-40)/p.getViewport({scale:1}).width;draw()}}
if(v=="back"){book=pdf=null;$("#viewer").hidden=true;$("#shelf").hidden=false;save();lib()}
if(q("#about"))setTimeout(async()=>{let on=false;try{on=await TR.autostart.isEnabled()}catch{}$("#dlg").insertAdjacentHTML("beforeend",`<p><label><input type="checkbox" id="as" ${on?"checked":""}> ${t("start")}</label></p>`);$("#as").onchange=ev=>TR.autostart[ev.target.checked?"enable":"disable"]()},50)});
addEventListener("keydown",e=>{if(book&&!$("#viewer").hidden&&!e.target.matches("input")){if(e.key=="ArrowRight"||e.key=="PageDown")go(pg+1);if(e.key=="ArrowLeft"||e.key=="PageUp")go(pg-1)}
if(!e.ctrlKey)return;const k=e.key.toLowerCase();if(k=="n"){e.preventDefault();tab="notes";render();$("#new").click()}if(k=="f"){e.preventDefault();tab="notes";render();$("#q").focus()}if(k=="p"&&tab=="notes"){e.preventDefault();print()}});
render();if(!db.tour)tour();
