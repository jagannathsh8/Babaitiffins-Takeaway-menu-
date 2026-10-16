const $=id=>document.getElementById(id);
async function keepDashboardRegistered(){for(;;){try{const response=await fetch('/api/dashboard/watch',{cache:'no-store'});await response.text();}catch{}await new Promise(resolve=>setTimeout(resolve,1000));}}
void keepDashboardRegistered();
let state,initialized=false,dirty=false,refreshing=false,refreshFailures=0;
const fields=['printerName','heading','tokenLabel','tokenEnabled','autoPrint','autoSource','showOrderId','tokenLimit','petpoojaIp','androidIp','kdsEnabled'];
const settingsDialog=$('settings-dialog');
function openSettings(){settingsDialog.showModal();}
$('open-settings').onclick=openSettings;
$('preview-settings').onclick=openSettings;
$('close-settings').onclick=()=>settingsDialog.close();
settingsDialog.addEventListener('click',event=>{if(event.target===settingsDialog)settingsDialog.close();});
for(const tab of document.querySelectorAll('.settings-tab'))tab.onclick=()=>{document.querySelectorAll('.settings-tab,.tab-panel').forEach(x=>x.classList.remove('active'));tab.classList.add('active');$(tab.dataset.panel).classList.add('active');};
const jobPreview=$('job-preview');
$('close-preview').onclick=()=>jobPreview.close();
jobPreview.addEventListener('click',event=>{if(event.target===jobPreview)jobPreview.close();});
function showJobPreview(job){
  const source=job.customization?.autoSource&&job.metadata?.brand?job.metadata.brand:null;
  $('job-preview-title').textContent=`${source||'Order'} preview`;
  $('jobPreviewHeading').textContent=job.customization?.heading||'';
  $('jobPreviewToken').textContent=`${source||job.customization?.tokenLabel||'TOKEN'} ${String(job.token)}`;
  $('jobPreviewOrder').textContent=job.customization?.showOrderId&&job.metadata?.orderId?`Order ID: ${String(job.metadata.orderId).slice(-4)}`:'';
  $('jobPreviewDetails').textContent=`Received ${new Date(job.createdAt).toLocaleString()} · ${(job.bytes/1024).toFixed(1)} KB`;
  jobPreview.showModal();
}
async function printers(){
  $('reload').disabled=true;
  try {
    const response=await fetch('/api/printers');const result=await response.json();if(!response.ok)throw Error(result.error);
    const selected=$('printerName').value||state?.settings.printerName||'';
    $('printerName').replaceChildren(new Option('Select your physical printer',''));
    for(const printer of result.printers) $('printerName').append(new Option(printer.Name,printer.Name));
    $('printerName').value=selected;
    $('printer-status').textContent=result.printers.length?'Choose the Epson connected to your LAN. Do not select the incoming Bridge print queue.':'Install your Epson driver on this computer, then refresh this list.';
  }catch(e){$('printer-status').textContent='Could not list printers. Check that Windows Print Spooler is running, then refresh.';}
  finally{$('reload').disabled=false;}
}
$('reload').onclick=printers;
function preview(){
  const recent=state?.jobs.find(j=>j.metadata?.brand)?.metadata;
  const brand=$('autoSource').checked?recent?.brand:null;
  $('previewHeading').textContent=$('heading').value;
  $('previewToken').textContent=$('tokenEnabled').checked?`${brand||$('tokenLabel').value||'TOKEN'} ${String(state?.nextToken||1)}`:'';
  $('previewOrder').textContent=$('tokenEnabled').checked&&$('showOrderId').checked&&recent?.orderId?`Order ID: ${String(recent.orderId).slice(-4)}`:'';
}
$('reset-form').onsubmit=async event=>{
  event.preventDefault();$('reset').disabled=true;
  try{await post('/api/token/reset',{next:Number($('resetTo').value)});$('reset-status').textContent='Next token updated. Existing jobs keep their numbers.';await refresh();}
  catch(e){$('reset-status').textContent=e.message;}finally{$('reset').disabled=false;}
};
$('clear-orders').onclick=async()=>{if(!state?.jobs.length)return;if(!confirm(`Remove all ${state.jobs.length} orders from the dashboard and TV?`))return;try{await post('/api/jobs/clear',{});await refresh();}catch(e){$('message').textContent=e.message;}};
$('settings').addEventListener('input',()=>{dirty=true;preview();});
async function post(url,data){const response=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json','X-Bridge-Token':state.csrf},body:JSON.stringify(data)});const result=await response.json();if(!response.ok)throw Error(result.error);return result;}
function drawJobs(jobs){
  $('jobs').replaceChildren();
  if(!jobs.length){const p=document.createElement('p');p.className='empty';p.textContent='No prints received yet. Select Bridge print in Petpooja and send a test receipt.';$('jobs').append(p);return;}
  for(const job of jobs){
    const row=document.createElement('article');row.className='job';
    const token=document.createElement('span');token.className='token';token.textContent=(job.customization?.autoSource&&job.metadata?.brand?job.metadata.brand+' ':'')+String(job.token);
    const info=document.createElement('div');info.className='job-main';
    const status=document.createElement('span');status.className=`status ${job.status}`;status.textContent=job.status;
    const meta=document.createElement('p');meta.textContent=`${new Date(job.createdAt).toLocaleString()} · ${(job.bytes/1024).toFixed(1)} KB`;
    info.append(status,meta);if(job.error){const p=document.createElement('p');p.textContent=job.error;info.append(p);}
    if(job.metadata){const p=document.createElement('p');p.textContent=[job.metadata.orderId?'Order ID: '+job.metadata.orderId:'Order ID not found',!job.metadata.brand?'Source not found — fallback label used':'',job.metadata.warning].filter(Boolean).join(' · ');info.append(p);}
    row.append(token,info);
    const actions=document.createElement('div');actions.className='job-actions';
    const view=document.createElement('button');view.className='icon-button';view.type='button';view.title='Preview print';view.setAttribute('aria-label','Preview print');view.textContent='◉';view.onclick=()=>showJobPreview(job);actions.append(view);
    if(['sent','uncertain'].includes(job.status)){const b=document.createElement('button');b.className='icon-button';b.type='button';b.title='Reprint';b.setAttribute('aria-label','Reprint');b.textContent='↻';b.onclick=async()=>{if(!confirm(`Reprint ${token.textContent} with the same token number?`))return;b.disabled=true;try{await post(`/api/jobs/${job.id}/reprint`,{});await refresh();}catch(e){$('message').textContent=e.message;b.disabled=false;}};actions.append(b);}
    const remove=document.createElement('button');remove.className='icon-button delete-button';remove.type='button';remove.title='Delete order';remove.setAttribute('aria-label','Delete order');remove.textContent='×';remove.onclick=async()=>{if(!confirm(`Remove ${token.textContent} from the dashboard and TV?`))return;remove.disabled=true;try{await post(`/api/jobs/${job.id}/delete`,{});await refresh();}catch(e){$('message').textContent=e.message;remove.disabled=false;}};actions.append(remove);
    row.append(actions);
    $('jobs').append(row);
  }
}
async function refresh(){
  if(refreshing)return;
  refreshing=true;
  const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),8000);
  try{const response=await fetch('/api/state',{cache:'no-store',signal:controller.signal});if(!response.ok)throw Error('Could not read bridge status.');state=await response.json();refreshFailures=0;
    const configured=state.settings.outputMode==='windows'?state.settings.printerName:state.settings.printerIp;
    $('connection').textContent=state.settings.autoPrint&&configured?'Auto print on':configured?'Printing paused':'Printer setup needed';
    $('message').textContent=configured?'':'Select your installed Epson printer to start automatic printing. Incoming jobs will wait here until it is configured.';
    $('next').textContent=String(state.nextToken);$('waiting').textContent=state.counts.queued;$('attention').textContent=state.counts.uncertain;
    $('ip').textContent=state.addresses.join(' / ')||'No LAN address found';$('port').textContent=`Print port ${state.receivePort}`;$('receive-error').textContent=state.lastReceiveError;$('petpooja-live-ip').textContent=state.petpooja?.serverIp?`${state.petpooja.serverIp}${state.petpooja.automatic?' (automatic)':''}`:'Not set';$('petpooja-status').textContent=state.petpooja?.lastMessage||'Live status has not started.';
    const mobile=state.mobile||{};$('android-lookup-status').textContent=mobile.lastRequestAt?`Last request from ${mobile.lastClient||'Android'} · Order ${mobile.lastOrderId} · ${mobile.lastMatchedToken?`matched token ${mobile.lastMatchedToken}`:'no matching Bridge order'}`:'No lookup received yet. Open the modified Token app on the same Wi-Fi.';
    if(!initialized||!dirty){for(const key of fields){if(typeof state.settings[key]==='boolean')$(key).checked=state.settings[key];else $(key).value=state.settings[key];}initialized=true;}
    preview();drawJobs(state.jobs);
  }catch(e){refreshFailures++;if(refreshFailures>=3){$('connection').textContent='Reconnecting…';$('message').textContent='The last order list is being kept while Bridge reconnects.';}}
  finally{clearTimeout(timeout);refreshing=false;}
}
$('settings').onsubmit=async event=>{event.preventDefault();$('save').disabled=true;try{if(!state)throw Error('Wait for the bridge to connect.');const input={...state.settings,outputMode:'windows'};for(const key of fields)input[key]=$(key).type==='checkbox'?$(key).checked:$(key).value;input.tokenLimit=Number(input.tokenLimit);const result=await post('/api/settings',input);dirty=false;$('save-status').textContent=result.warning||'Settings saved.';await refresh();if(!result.warning)settingsDialog.close();}catch(e){$('save-status').textContent=e.message;}finally{$('save').disabled=false;}};
await refresh();await printers();setInterval(refresh,2500);

