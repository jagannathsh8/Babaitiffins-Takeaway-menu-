const orders=document.getElementById('orders'),clock=document.getElementById('clock'),pageLabel=document.getElementById('page-label');
let state,page=0,pageSize=12,visibleJobs=[],refreshing=false,refreshFailures=0;
let screenWakeLock=null;
async function keepScreenAwake(){
  if(!('wakeLock' in navigator)||document.visibilityState!=='visible')return;
  try{screenWakeLock=await navigator.wakeLock.request('screen');screenWakeLock.addEventListener('release',()=>{screenWakeLock=null;},{once:true});}catch{}
}
document.getElementById('fullscreen').onclick=async()=>{if(document.fullscreenElement)await document.exitFullscreen();else await document.documentElement.requestFullscreen();await keepScreenAwake();};
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')void keepScreenAwake();});
for(const event of ['click','keydown','pointerdown'])addEventListener(event,()=>void keepScreenAwake(),{once:true});
function tick(){clock.textContent=new Date().toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});}tick();setInterval(tick,1000);
async function post(url){const response=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json','X-Bridge-Token':state.csrf},body:'{}'});const result=await response.json();if(!response.ok)throw Error(result.error);}
function card(job){const article=document.createElement('article'),live=job.petpooja,ready=live?.status==='ready';article.className=ready?'ready':job.status==='uncertain'?'attention':'preparing';const source=job.metadata?.displayBrand||job.metadata?.brand||live?.brand||job.customization?.tokenLabel||'TOKEN',orderId=String(job.metadata?.orderId||live?.orderId||''),shortOrderId=orderId.slice(-4);const token=document.createElement('strong'),tokenName=document.createElement('span'),tokenOrder=document.createElement('span');tokenName.textContent=`Token ${String(job.token)}`;tokenOrder.className='token-order-suffix';tokenOrder.textContent=shortOrderId;token.append(tokenName,tokenOrder);const status=document.createElement('span');status.textContent=ready?'Food Ready':job.status==='uncertain'?'Check Order':'Preparing';const details=document.createElement('div'),brand=document.createElement('b'),order=document.createElement('small'),otp=document.createElement('small');brand.className='source-name';brand.textContent=source;order.className='order-id';order.textContent=orderId?`Order ID ${orderId}`:'Order received';otp.textContent=live?.otp?`OTP ${live.otp}`:'';const remove=document.createElement('button');remove.className='remove';remove.type='button';remove.title='Remove order';remove.textContent='×';remove.onclick=async()=>{if(!confirm(`Remove ${tokenName.textContent}?`))return;await post(`/api/jobs/${job.id}/delete`);await refresh();};details.append(brand,order);if(live?.otp)details.append(otp);details.append(remove);article.append(token,status,details);return article;}
function capacity(){const columns=Math.max(1,Math.floor((innerWidth-32)/270)),rows=Math.max(1,Math.floor((innerHeight-120)/132)),cardWidth=(innerWidth-24-(columns-1)*8)/columns,availableHeight=(innerHeight-112-(rows-1)*8)/rows,cardHeight=Math.max(126,Math.min(150,availableHeight));pageSize=columns*rows;orders.style.setProperty('--cols',columns);orders.style.setProperty('--rows',rows);orders.style.setProperty('--card-height',`${cardHeight}px`);orders.dataset.density=cardWidth<290||cardHeight<132?'dense':cardWidth<360||cardHeight<145?'compact':'comfortable';}
function isReady(job){return job.petpooja?.status==='ready';}
function screenSequence(){
  const result=[];
  for(const group of [visibleJobs.filter(isReady),visibleJobs.filter(job=>!isReady(job))]){
    for(let start=0;start<group.length;start+=pageSize)result.push(group.slice(start,start+pageSize));
  }
  return result.length?result:[[]];
}
function render(){capacity();const screens=screenSequence(),pages=screens.length;if(page>=pages)page=pages-1;orders.replaceChildren();for(const job of screens[page])orders.append(card(job));if(!visibleJobs.length){const p=document.createElement('p');p.className='empty';p.textContent='Waiting for orders…';orders.append(p);}pageLabel.textContent=`Page ${page+1} of ${pages}`;document.getElementById('previous').disabled=pages===1;document.getElementById('next').disabled=pages===1;}
function advance(){const screens=screenSequence();if(screens.length>1){page=(page+1)%screens.length;render();}scheduleAdvance();}
let rotationTimer;
function scheduleAdvance(){clearTimeout(rotationTimer);const current=screenSequence()[page]||[];rotationTimer=setTimeout(advance,current.some(isReady)?20000:10000);}
async function refresh(){
  if(refreshing)return;
  refreshing=true;
  const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),8000);
  try{
    const response=await fetch('/api/state',{cache:'no-store',signal:controller.signal});if(!response.ok)throw Error();
    state=await response.json();refreshFailures=0;
    const latestByToken=new Map();for(const job of state.jobs)if(job.petpooja?.active!==false&&!latestByToken.has(job.token))latestByToken.set(job.token,job);
    visibleJobs=[...latestByToken.values()].slice(0,state.settings.tokenLimit);render();
  }catch{
    refreshFailures++;
    // Keep the last valid order list on screen during short Wi-Fi or browser interruptions.
    if(!state&&refreshFailures>=3)orders.innerHTML='<p class="empty">Connecting to Bridge…</p>';
  }finally{clearTimeout(timeout);refreshing=false;}
}
document.getElementById('previous').onclick=()=>{const pages=screenSequence().length;page=(page-1+pages)%pages;render();scheduleAdvance();};document.getElementById('next').onclick=()=>{const pages=screenSequence().length;page=(page+1)%pages;render();scheduleAdvance();};document.getElementById('clear-orders').onclick=async()=>{if(!state?.jobs.length)return;if(!confirm(`Remove all ${state.jobs.length} orders?`))return;await post('/api/jobs/clear');page=0;await refresh();scheduleAdvance();};addEventListener('resize',()=>{render();scheduleAdvance();});await keepScreenAwake();await refresh();scheduleAdvance();setInterval(refresh,2500);
