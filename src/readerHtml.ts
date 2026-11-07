import { Theme } from './theme';
import { JSZIP, MAMMOTH, PDFJS, PDFWORKER } from './readerLibs';

/**
 * Reader page (runs inside a WebView). All libraries are bundled, so no internet is needed.
 * The book arrives in chunks via cp(), then load(kind, start, title) renders it.
 * Formats: pdf, epub, docx, mobi (MOBI / AZW / AZW3-hybrid / PRC), txt, html.
 * String.raw keeps the regex backslashes of the script below intact.
 */
export const readerHtml = (th: Theme) => String.raw`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html{-webkit-text-size-adjust:100%}body{margin:0;padding:14px 16px 96px;background:${th.bg};color:${th.ink};font:18px/1.65 Georgia,serif;-webkit-user-select:text;overflow-wrap:break-word}
img,svg,video{max-width:100%;height:auto}h1,h2,h3{line-height:1.25}mark{background:#FFE27A;color:#111;border-radius:3px}a{color:${th.accent}}
table{border-collapse:collapse;max-width:100%}td,th{border:1px solid ${th.sub};padding:4px 6px}pre{white-space:pre-wrap}
.pg{background:#fff;margin:0 auto 10px;overflow:hidden}.pg canvas{display:block}#msg{padding:40px 8px;color:${th.sub};text-align:center;font:16px sans-serif}
.txt{white-space:pre-wrap;font:17px/1.6 Georgia,serif}</style></head>
<body><div id="doc"><div id="msg">Loading…</div></div>
<script>${JSZIP}</script><script>${MAMMOTH}</script><script>${PDFJS}</script>
<script>
var PW=${JSON.stringify(PDFWORKER)};
var RN=function(m){window.ReactNativeWebView.postMessage(JSON.stringify(m))};window.RN=RN;
var doc=document.getElementById('doc'),total=0,TITLE='',KIND='',coverSent=false,touched=false;window.__c=[];window.__secs=null;
window.cp=function(s){window.__c.push(s)};
function b2u(b){var s=atob(b),u=new Uint8Array(s.length);for(var i=0;i<s.length;i++)u[i]=s.charCodeAt(i);return u}
function sendCover(d,gen){if(coverSent)return;coverSent=true;RN({type:'cover',data:d,gen:!!gen})}
function thumb(src){return new Promise(function(res){var done=false,fin=function(v){if(!done){done=true;res(v)}};setTimeout(function(){fin(null)},5000);
 var im=new Image();im.onload=function(){try{if(!im.width||!im.height)return fin(null);var w=240,c=document.createElement('canvas');c.width=w;c.height=Math.min(400,Math.round(im.height*w/im.width));var x=c.getContext('2d');x.fillStyle='#fff';x.fillRect(0,0,c.width,c.height);x.drawImage(im,0,0,c.width,c.height);fin(c.toDataURL('image/jpeg',0.8))}catch(e){fin(null)}};im.onerror=function(){fin(null)};im.src=src})}
function mime(u8){if(u8[0]===0xFF&&u8[1]===0xD8)return 'image/jpeg';if(u8[0]===0x89&&u8[1]===0x50)return 'image/png';if(u8[0]===0x47&&u8[1]===0x49)return 'image/gif';if(u8[0]===0x52&&u8[1]===0x49)return 'image/webp';return ''}
function u2b(u8){var s='',n=0x8000;for(var i=0;i<u8.length;i+=n)s+=String.fromCharCode.apply(null,u8.subarray(i,i+n));return btoa(s)}
function genCover(t,kind){var c=document.createElement('canvas');c.width=240;c.height=340;var x=c.getContext('2d');
 var pal=['#5B47E0','#7C3AED','#0F766E','#B45309','#BE185D'],h=0;for(var k=0;k<t.length;k++)h+=t.charCodeAt(k);
 var g=x.createLinearGradient(0,0,240,340);g.addColorStop(0,pal[h%pal.length]);g.addColorStop(1,'#14121C');x.fillStyle=g;x.fillRect(0,0,240,340);
 x.fillStyle='#fff';x.textBaseline='top';x.font='bold 26px sans-serif';
 var words=String(t||'Book').split(/\s+/),lines=[],cur='';
 words.forEach(function(w){var tt=cur?cur+' '+w:w;if(x.measureText(tt).width>200&&cur){lines.push(cur);cur=w}else cur=tt});if(cur)lines.push(cur);
 if(lines.length>7){lines=lines.slice(0,7);lines[6]=lines[6].slice(0,-1)+'…'}
 for(var i=0;i<lines.length;i++)x.fillText(lines[i],20,24+i*34);
 x.globalAlpha=.75;x.font='bold 16px sans-serif';x.fillText(String(kind||'').toUpperCase(),20,304);
 return c.toDataURL('image/jpeg',0.85)}
/* Removes scripts, frames, inline handlers, javascript: links and hard-coded colours (book CSS would make text unreadable on dark). */
function clean(html){var d=new DOMParser().parseFromString(html,'text/html');
 d.querySelectorAll('script,iframe,object,embed,link,meta,base,form,style,title').forEach(function(n){n.remove()});
 d.querySelectorAll('*').forEach(function(e){[].slice.call(e.attributes).forEach(function(a){var n=a.name.toLowerCase();
  if(n.indexOf('on')===0||((n==='href'||n==='src'||n==='xlink:href'||n==='action'||n==='formaction')&&/^\s*(javascript|vbscript):/i.test(a.value))||n==='color'||n==='bgcolor'||n==='face')e.removeAttribute(a.name)});
  if(e.style){e.style.removeProperty('color');e.style.removeProperty('background');e.style.removeProperty('background-color');e.style.removeProperty('font-family')}});
 return d.body?d.body.innerHTML:''}
function room(){return document.documentElement.scrollHeight-innerHeight}
function prog(){var h=room(),p=h>0?scrollY/h:0;RN({type:'p',pct:Math.max(0,Math.min(1,p)),page:total?Math.min(total,Math.round(p*(total-1))+1):0,total:total})}
var tm;addEventListener('scroll',function(){clearTimeout(tm);tm=setTimeout(prog,400)},{passive:true});
addEventListener('touchstart',function(){touched=true},{passive:true});
window.jump=function(p){touched=true;scrollTo(0,room()*p)};
function restore(p){if(!p){prog();return}setTimeout(function(){scrollTo(0,room()*p);prog()},350);setTimeout(function(){if(!touched){scrollTo(0,room()*p);prog()}},1800)}
function res(from,href){try{return decodeURIComponent(new URL(href,'http://x/'+from).pathname.slice(1))}catch(e){return href}}
/* highlights are re-applied by text match inside a single text node */
window.applyHl=function(list){if(KIND==='pdf'||!list)return;list.forEach(function(t){if(!t)return;var w=document.createTreeWalker(doc,NodeFilter.SHOW_TEXT),n;while((n=w.nextNode())){var i=n.data.indexOf(t);if(i<0||(n.parentNode&&n.parentNode.nodeName==='MARK'))continue;try{var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+t.length);var m=document.createElement('mark');r.surroundContents(m)}catch(e){}break}})};
document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a'):null;if(!a)return;e.preventDefault();var h=a.getAttribute('href');if(!h)return;
 if(/^(https?:|mailto:)/i.test(h)){RN({type:'link',url:h});return}
 if(h.charAt(0)==='#'&&!window.__secs){var t0=doc.querySelector('[id="'+h.slice(1).replace(/"/g,'')+'"]');if(t0)t0.scrollIntoView();return}
 if(!window.__secs)return;var parts=h.split('#'),fp=parts[0],fr=parts[1],from=a.closest('section[data-p]'),base=from?from.getAttribute('data-p'):'',tp=fp?res(base,fp):base,sec=window.__secs[tp.toLowerCase()];if(!sec)return;
 var el=null;if(fr){try{el=sec.querySelector('[id="'+decodeURIComponent(fr).replace(/"/g,'')+'"]')}catch(x){}}(el||sec).scrollIntoView()},true);

function utf8(u8){try{return new TextDecoder('utf-8',{fatal:true}).decode(u8)}catch(e){return new TextDecoder('windows-1252').decode(u8)}}

/* ---------- MOBI / AZW / PRC (PalmDOC + MOBI header, no DRM) ---------- */
function palm(inp){var out=new Uint8Array(inp.length*8+64),o=0,i=0,n=inp.length;
 function put(b){if(o>=out.length){var t=new Uint8Array(out.length*2);t.set(out);out=t}out[o++]=b}
 while(i<n){var c=inp[i++];
  if(c===0||(c>=9&&c<=0x7F))put(c);
  else if(c>=1&&c<=8){while(c--&&i<n)put(inp[i++])}
  else if(c>=0xC0){put(0x20);put(c^0x80)}
  else{var d=(c<<8)|(inp[i++]||0),dist=(d>>3)&0x7FF,len=(d&7)+3;if(!dist)continue;while(len--){put(o-dist>=0?out[o-dist]:0)}}}
 return out.subarray(0,o)}
function trailSize(r,size,flags){var num=0,f=flags>>1;
 while(f){if(f&1){var bp=0,v=0,sz=size-num;if(sz>0){while(true){var b=r[sz-1];v|=(b&0x7F)<<bp;bp+=7;sz--;if((b&0x80)||bp>=28||sz===0)break}}num+=v}f>>=1}
 if(flags&1){num+=((r[size-num-1]||0)&3)+1}
 return num}
async function loadMobi(u8){
 var dv=new DataView(u8.buffer,u8.byteOffset,u8.byteLength),nrec=dv.getUint16(76),off=[];
 for(var i=0;i<nrec;i++)off.push(dv.getUint32(78+i*8));off.push(u8.length);
 var R=function(i){return u8.subarray(off[i],off[i+1])};
 var r0=R(0),d0=new DataView(r0.buffer,r0.byteOffset,r0.byteLength);
 var comp=d0.getUint16(0),textLen=d0.getUint32(4),count=d0.getUint16(8),enc=d0.getUint16(12);
 if(enc)throw new Error('This book is protected by DRM and cannot be opened.');
 if(comp!==1&&comp!==2)throw new Error('This MOBI uses an unsupported compression. Convert it to EPUB with Calibre.');
 var isMobi=String.fromCharCode(r0[16],r0[17],r0[18],r0[19])==='MOBI',hl=isMobi?d0.getUint32(20):0,ver=isMobi?d0.getUint32(36):0,codepage=isMobi?d0.getUint32(28):1252;
 if(ver===8)throw new Error('This is a KF8-only AZW3 file. Convert it to EPUB with Calibre.');
 var firstImg=isMobi&&hl>=108?d0.getUint32(108):0,flags=0;
 if(isMobi&&hl>=228)flags=d0.getUint16(242);
 var chunks=[],total=0;
 for(var k=1;k<=count&&k<nrec;k++){var rec=R(k),sz=rec.length;if(flags)sz-=trailSize(rec,sz,flags);if(sz<0)sz=0;var part=rec.subarray(0,sz);if(comp===2)part=palm(part);chunks.push(part);total+=part.length}
 var all=new Uint8Array(total),p=0;chunks.forEach(function(c){all.set(c,p);p+=c.length});
 if(textLen&&all.length>textLen)all=all.subarray(0,textLen);
 var html=codepage===65001?new TextDecoder('utf-8').decode(all):new TextDecoder('windows-1252').decode(all);
 var cover=null;
 if(isMobi&&(d0.getUint32(128)&0x40)){var ex=16+hl;if(String.fromCharCode(r0[ex],r0[ex+1],r0[ex+2],r0[ex+3])==='EXTH'){var cnt=d0.getUint32(ex+8),q=ex+12;
  for(var e=0;e<cnt&&q+8<=r0.length;e++){var ty=d0.getUint32(q),ln=d0.getUint32(q+4);if(ty===201&&ln>=12)cover=d0.getUint32(q+8);if(ln<8)break;q+=ln}}}
 function img(idx){var ri=firstImg+idx;if(!firstImg||ri<=0||ri>=nrec)return null;var rr=R(ri),m=mime(rr);return m?{m:m,b:rr}:null}
 html=html.replace(/<img\b[^>]*?recindex=["']?0*(\d+)["']?[^>]*>/gi,function(t,n){var im=img(+n-1);return im?'<img src="data:'+im.m+';base64,'+u2b(im.b)+'">':''})
  .replace(/<mbp:pagebreak[^>]*>/gi,'<hr>').replace(/<\/?mbp:[^>]*>/gi,'').replace(/<a\b[^>]*?filepos=[^>]*>/gi,'<a>');
 doc.innerHTML=clean(html);
 if(!doc.textContent.trim())throw new Error('The book appears to be empty.');
 try{var ci=cover!==null?img(cover):null;if(!ci&&firstImg){for(var z=firstImg;z<nrec&&z<firstImg+8&&!ci;z++){var rz=R(z),mz=mime(rz);if(mz&&rz.length>4000)ci={m:mz,b:rz}}}
  if(ci){var t=await thumb('data:'+ci.m+';base64,'+u2b(ci.b));if(t)sendCover(t,false)}}catch(e){}
}

window.load=async function(k,start,title){try{
 KIND=k;TITLE=title||'';
 var b=window.__c.join('');window.__c=[];
 if(!b)throw new Error('The file is empty.');
 doc.innerHTML='';
 if(k==='pdf'){
  if(!window.pdfjsLib)throw new Error('PDF engine missing');
  pdfjsLib.GlobalWorkerOptions.workerSrc=URL.createObjectURL(new Blob([PW],{type:'text/javascript'}));
  var pdf=await pdfjsLib.getDocument({data:b2u(b)}).promise;total=pdf.numPages;
  var p1=await pdf.getPage(1),v1=p1.getViewport({scale:1}),w=doc.clientWidth||innerWidth-32,sc=w/v1.width,dpr=Math.min(window.devicePixelRatio||1,2);
  var io=new IntersectionObserver(function(es){es.forEach(function(e){var t=e.target;
   if(e.isIntersecting&&!t.d){t.d=1;
    pdf.getPage(+t.dataset.n).then(function(pg){if(!t.d)return;var vp=pg.getViewport({scale:sc*dpr}),c=document.createElement('canvas');
     c.width=vp.width;c.height=vp.height;c.style.width=w+'px';c.style.height=(vp.height/dpr)+'px';t.style.height=(vp.height/dpr)+'px';t.appendChild(c);
     var rt=pg.render({canvasContext:c.getContext('2d'),viewport:vp});t.rt=rt;rt.promise.catch(function(){})}).catch(function(){t.d=0})}
   else if(!e.isIntersecting&&t.d){t.d=0;try{if(t.rt)t.rt.cancel()}catch(x){}while(t.firstChild){t.firstChild.width=0;t.removeChild(t.firstChild)}}})},{rootMargin:'1200px'});
  for(var i=1;i<=total;i++){var d=document.createElement('div');d.className='pg';d.dataset.n=i;d.style.cssText='width:'+w+'px;height:'+(v1.height*sc)+'px';doc.appendChild(d);io.observe(d)}
  try{var cv=document.createElement('canvas'),cvp=p1.getViewport({scale:240/v1.width}),cx0=cv.getContext('2d');cv.width=cvp.width;cv.height=cvp.height;cx0.fillStyle='#fff';cx0.fillRect(0,0,cv.width,cv.height);await p1.render({canvasContext:cx0,viewport:cvp}).promise;sendCover(cv.toDataURL('image/jpeg',0.85),false)}catch(e){}
 }else if(k==='docx'){
  if(!window.mammoth)throw new Error('DOCX engine missing');
  doc.innerHTML=clean((await mammoth.convertToHtml({arrayBuffer:b2u(b).buffer})).value);
  if(!doc.textContent.trim()&&!doc.querySelector('img'))throw new Error('The document appears to be empty.');
 }else if(k==='mobi'){
  await loadMobi(b2u(b));
 }else if(k==='txt'){
  var pre=document.createElement('div');pre.className='txt';pre.textContent=utf8(b2u(b));doc.appendChild(pre);
 }else if(k==='html'){
  doc.innerHTML=clean(utf8(b2u(b)));
 }else{
  if(!window.JSZip)throw new Error('EPUB engine missing');
  var z=await JSZip.loadAsync(b,{base64:true}),P=new DOMParser(),low={};
  z.forEach(function(p){low[p.toLowerCase()]=p});
  var zf=function(p){return z.file(p)||(low[p.toLowerCase()]?z.file(low[p.toLowerCase()]):null)};
  var cf0=zf('META-INF/container.xml');if(!cf0)throw new Error('Not a valid EPUB (container.xml missing).');
  var op=P.parseFromString(await cf0.async('string'),'text/xml').getElementsByTagName('rootfile')[0].getAttribute('full-path');
  var opff=zf(op);if(!opff)throw new Error('Not a valid EPUB (package file missing).');
  var opf=P.parseFromString(await opff.async('string'),'text/xml'),man={},items=[].slice.call(opf.getElementsByTagName('item'));
  items.forEach(function(i){man[i.getAttribute('id')]=i});
  var base=op.indexOf('/')>-1?op.slice(0,op.lastIndexOf('/')+1):'';
  async function imgData(path){var f=zf(path);if(!f)return null;var ext=path.split('.').pop().toLowerCase();var mt=ext==='svg'?'image/svg+xml':ext==='jpg'||ext==='jpeg'?'image/jpeg':'image/'+ext;return 'data:'+mt+';base64,'+await f.async('base64')}
  try{var ci=null,metas=[].slice.call(opf.getElementsByTagName('meta')),cm=metas.filter(function(m){return m.getAttribute('name')==='cover'})[0];
   if(cm&&man[cm.getAttribute('content')])ci=man[cm.getAttribute('content')].getAttribute('href');
   if(!ci){items.forEach(function(i){if(!ci&&(i.getAttribute('properties')||'').indexOf('cover-image')>-1)ci=i.getAttribute('href')})}
   if(!ci){items.forEach(function(i){var mt=i.getAttribute('media-type')||'';if(!ci&&mt.indexOf('image/')===0&&/cover/i.test((i.getAttribute('id')||'')+(i.getAttribute('href')||'')))ci=i.getAttribute('href')})}
   if(ci){var du=await imgData(res(base,ci));if(du){var tn=await thumb(du);if(tn)sendCover(tn,false)}}}catch(e){}
  var ids=[].map.call(opf.getElementsByTagName('itemref'),function(r){return r.getAttribute('idref')});
  window.__secs={};
  for(var i=0;i<ids.length;i++){var it=man[ids[i]];if(!it)continue;var path=res(base,it.getAttribute('href')||''),f=zf(path);if(!f)continue;
   var dd=P.parseFromString(await f.async('string'),'text/html'),im=dd.querySelectorAll('img,image');
   for(var j=0;j<im.length;j++){var src=im[j].getAttribute('src')||im[j].getAttribute('xlink:href')||im[j].getAttribute('href');if(!src||src.indexOf('data:')===0)continue;var du2=await imgData(res(path,src));if(du2){im[j].setAttribute(im[j].hasAttribute('src')?'src':'href',du2)}}
   var s=document.createElement('section');s.setAttribute('data-p',path);s.innerHTML=clean(dd.body?dd.body.innerHTML:'');doc.appendChild(s);window.__secs[path.toLowerCase()]=s;
   if(!coverSent&&i===0){var fi=s.querySelector('img');if(fi&&fi.src.indexOf('data:')===0){try{var t2=await thumb(fi.src);if(t2&&doc.textContent.trim().length<200)sendCover(t2,false)}catch(e){}}}}
  if(!doc.textContent.trim()&&!doc.querySelector('img'))throw new Error('The EPUB has no readable content.');
 }
 if(!coverSent){try{sendCover(genCover(TITLE,k),true)}catch(e){}}
 restore(start||0);RN({type:'loaded'});
}catch(e){RN({type:'err',msg:String(e&&e.message||e)})}};
window.hl=function(){var s=getSelection();if(!s.rangeCount||s.isCollapsed)return;var t=s.toString(),r=s.getRangeAt(0),m=document.createElement('mark');
 try{r.surroundContents(m)}catch(e){}RN({type:'hl',text:t});s.removeAllRanges()};
RN({type:'ready'});
</script></body></html>`;
