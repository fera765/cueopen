package dev.cueopen.app.sessionapp

/**
 * App base: garante que TODA seção tenha um app funcional mesmo se o coordenador não conseguir publicar o
 * dele. Mostra só dados reais do retrato da sessão (agentes, plano, dados publicados, arquivos, eventos).
 */
object SessionAppTemplate {
    val HTML = """<!doctype html><html lang="pt-br"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>App da seção</title><style>
*{box-sizing:border-box}body{margin:0;background:#0e0f12;color:#e9e9ec;font:14px/1.45 system-ui,sans-serif;padding:14px 14px 90px}
h1{font-size:20px;margin:0 0 2px}h2{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:#8b8d98;margin:22px 0 8px}
.goal{color:#a5a7b2;font-size:13px}.pill{display:inline-block;padding:3px 10px;border-radius:99px;background:#1c1e25;color:#9fe3b4;font-size:11px;margin-top:8px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:8px}.card{background:#16181e;border:1px solid #23262f;border-radius:14px;padding:12px}
.name{font-weight:600}.muted{color:#8b8d98;font-size:12px}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;background:#555}
.working .dot{background:#ff7a4d;animation:p 1s infinite}.error .dot{background:#ff5d6c}.waiting_user .dot{background:#ffd166}.recovering .dot{background:#6cb6ff}
@keyframes p{50%{opacity:.25}}.row{display:flex;gap:8px;align-items:flex-start;padding:8px 0;border-bottom:1px solid #1f222a}.row:last-child{border:0}
.st{font-size:11px;padding:2px 8px;border-radius:99px;background:#22252e;white-space:nowrap}.done{color:#7ee0a1}.running{color:#ff9d7a}.review{color:#ffd166}.failed,.blocked{color:#ff7d8a}
table{width:100%;border-collapse:collapse;font-size:12px}td,th{text-align:left;padding:6px 8px;border-bottom:1px solid #1f222a;vertical-align:top}th{color:#8b8d98;font-weight:500}
.scroll{overflow-x:auto}pre{white-space:pre-wrap;word-break:break-word;margin:0;font-size:12px;color:#c8cad3}.empty{color:#6d6f7a;font-size:13px;padding:6px 0}
.bar{position:fixed;left:0;right:0;bottom:0;background:#0e0f12;border-top:1px solid #23262f;padding:10px;display:flex;gap:8px}
input{flex:1;background:#16181e;border:1px solid #2a2d37;border-radius:12px;color:#fff;padding:11px;font-size:14px}button{background:#ff6b3d;border:0;border-radius:12px;color:#fff;font-weight:600;padding:0 16px}
</style></head><body>
<h1 id="name">Seção</h1><div class="goal" id="goal"></div><span class="pill" id="state"></span>
<h2>Agentes</h2><div class="grid" id="agents"></div>
<h2>Plano</h2><div class="card" id="plan"></div>
<h2>Resultados</h2><div id="data"></div>
<h2>Arquivos</h2><div class="card" id="files"></div>
<h2>Atividade</h2><div class="card" id="events"></div>
<div class="bar"><input id="ask" placeholder="Pedir algo ao coordenador…"><button id="go">Enviar</button></div>
<script>
function esc(v){return String(v==null?'':v).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
var LBL={working:'trabalhando',idle:'livre',waiting_user:'aguardando você',error:'erro',recovering:'retomando'};
function val(v){return typeof v==='object'&&v!==null?esc(JSON.stringify(v)):esc(v);}
function renderData(k,v){var h='<div class="card" style="margin-bottom:8px"><div class="name">'+esc(k)+'</div>';
 if(Array.isArray(v)&&v.length&&typeof v[0]==='object'&&v[0]!==null){var cols=[];v.forEach(function(r){Object.keys(r||{}).forEach(function(c){if(cols.indexOf(c)<0&&cols.length<8)cols.push(c);});});
  h+='<div class="scroll"><table><tr>'+cols.map(function(c){return '<th>'+esc(c)+'</th>';}).join('')+'</tr>'+v.slice(0,100).map(function(r){return '<tr>'+cols.map(function(c){return '<td>'+val(r[c])+'</td>';}).join('')+'</tr>';}).join('')+'</table></div>';}
 else if(Array.isArray(v)){h+=v.length?v.slice(0,100).map(function(x){return '<div class="row">'+val(x)+'</div>';}).join(''):'<div class="empty">Lista vazia.</div>';}
 else if(typeof v==='object'&&v!==null){h+='<table>'+Object.keys(v).map(function(c){return '<tr><th>'+esc(c)+'</th><td>'+val(v[c])+'</td></tr>';}).join('')+'</table>';}
 else h+='<pre>'+esc(v)+'</pre>';return h+'</div>';}
function render(s){s=s||{};var sec=s.section||{};
 document.getElementById('name').textContent=sec.name||'Seção';document.getElementById('goal').textContent=sec.goal||'';
 document.getElementById('state').textContent='Plano: '+(s.planState||'sem plano');
 var ag=s.agents||[];document.getElementById('agents').innerHTML=ag.length?ag.map(function(a){return '<div class="card '+esc(a.status)+'"><div class="name"><span class="dot"></span>'+esc(a.name)+'</div><div class="muted">'+esc(LBL[a.status]||a.status)+'</div><div class="muted">'+esc(a.activity||a.task||a.role||'')+'</div></div>';}).join(''):'<div class="empty">Nenhum agente.</div>';
 var pl=s.plan||[];document.getElementById('plan').innerHTML=pl.length?pl.map(function(t){return '<div class="row"><span class="st '+esc(t.status)+'">'+esc(t.status)+'</span><div><div class="name">'+esc(t.id)+' · '+esc(t.agent)+'</div><div class="muted">'+esc(t.task)+'</div></div></div>';}).join(''):'<div class="empty">O coordenador ainda não registrou um plano.</div>';
 var d=s.data||{},keys=Object.keys(d);document.getElementById('data').innerHTML=keys.length?keys.map(function(k){return renderData(k,d[k]);}).join(''):'<div class="card empty">Os resultados dos agentes aparecem aqui assim que forem publicados.</div>';
 var f=s.files||[];document.getElementById('files').innerHTML=f.length?f.slice(0,30).map(function(x){return '<div class="row"><div style="flex:1">'+esc(x.path)+'</div><span class="muted">'+Math.max(1,Math.round(x.size/1024))+' KB</span></div>';}).join(''):'<div class="empty">Nenhum arquivo ainda.</div>';
 var ev=(s.events||[]).slice().reverse();document.getElementById('events').innerHTML=ev.length?ev.map(function(e){var t=new Date(e.time);return '<div class="row"><span class="muted">'+('0'+t.getHours()).slice(-2)+':'+('0'+t.getMinutes()).slice(-2)+'</span><div>'+esc(e.text)+'</div></div>';}).join(''):'<div class="empty">Sem atividade ainda.</div>';}
SessionApp.onUpdate(render);
document.getElementById('go').onclick=function(){var i=document.getElementById('ask');if(i.value.trim()){SessionApp.send('pedido do usuário',i.value.trim());i.value='';}};
</script></body></html>"""
}
