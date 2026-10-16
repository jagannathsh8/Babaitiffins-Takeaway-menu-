const endpoint='/petpooja_server/get_pending_orders';
function clean(value){return value==null?'':String(value).trim();}
function ready(order){if(Number(order.status)===5)return true;const done=clean(order.mark_as_done_date);return Boolean(done&&!/^null$/i.test(done));}
function keys(order){return [order.client_order_id,order.auth_no,order.id,order.web_id,order.kot_id].map(clean).filter(Boolean).map(x=>x.toLowerCase());}
function sourceIp(value){const text=clean(value);if(text==='::1')return '127.0.0.1';const match=text.match(/(?:^|::ffff:)(\d{1,3}(?:\.\d{1,3}){3})$/i);return match?match[1]:'';}
// Babai: JSON.parse rounds integers above 2^53, so a 19-digit Ownly order ID 1791982601318835770
// became 1791982601318835700 and never matched its receipt. Quote long integers before parsing.
export function parseKeepingLongNumbers(text){return JSON.parse(String(text).replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,}\]])/g,'$1"$2"'));}
export function createPetpoojaLive({getSettings,updateSettings,getJobs,saveJob}){
  let timer=null,running=false;
  const state={enabled:false,status:'off',serverIp:'',lastSync:null,lastMessage:'Save the Petpooja server IP to start.',orderCount:0};
  async function sync(){
    if(running)return;const settings=getSettings();state.enabled=Boolean(settings.kdsEnabled);const automaticIp=[...getJobs()].reverse().map(job=>sourceIp(job.source)).find(Boolean)||'';const serverIp=settings.petpoojaIp||automaticIp;state.serverIp=serverIp;state.automatic=!settings.petpoojaIp&&Boolean(serverIp);
    if(!settings.kdsEnabled){state.status='off';state.lastMessage='Live Petpooja status is off.';return;}
    if(!serverIp){state.status='setup';state.lastMessage='Send one Petpooja print to detect its server, or enter the server IP manually.';return;}
    running=true;state.status='connecting';
    try{
      if(!settings.petpoojaSyncCode){
        state.lastMessage='Getting restaurant details from Petpooja…';
        const setupResponse=await fetch(`http://${serverIp}:3000/petpooja_server/get_token_default_data`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({clientIdentifier:'token',intra_server_version:'1'}),signal:AbortSignal.timeout(6000)});
        if(!setupResponse.ok)throw Error(`Petpooja setup returned HTTP ${setupResponse.status}`);
        const setup=await setupResponse.json();if(String(setup.error||'')!=='')throw Error(clean(setup.message)||'Petpooja setup was rejected');
        const discovered=clean(setup.sync_code);if(!discovered)throw Error('Petpooja did not return its restaurant code.');
        settings.petpoojaSyncCode=discovered;updateSettings(settings);
      }
      const response=await fetch(`http://${serverIp}:3000${endpoint}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sync_code:settings.petpoojaSyncCode,clientIdentifier:'token',intra_server_version:'1',online:'1',pickup:'1',delivery:'1',dinein:'1'}),signal:AbortSignal.timeout(6000)});
      if(!response.ok)throw Error(`Petpooja returned HTTP ${response.status}`);const result=parseKeepingLongNumbers(await response.text());if(String(result.error??'0')==='1')throw Error(clean(result.message)||'Petpooja rejected the request');
      const orders=[...(Array.isArray(result.data)?result.data:[]),...(Array.isArray(result.pos_data)?result.pos_data:[]),...(Array.isArray(result.dine_data)?result.dine_data:[])];const byKey=new Map();for(const order of orders)for(const key of keys(order))byKey.set(key,order);let changed=0;
      for(const job of getJobs()){const orderId=clean(job.metadata?.orderId).toLowerCase();if(!orderId)continue;const order=byKey.get(orderId),current=job.petpooja||{};if(!order){if(current.active===true){job.petpooja={...current,active:false,removedAt:new Date().toISOString(),updatedAt:new Date().toISOString()};saveJob(job);changed++;}continue;}const values={active:true,status:ready(order)?'ready':'preparing',rawStatus:Number(order.status)||0,otp:clean(order.otp),brand:clean(order.user_name),orderId:clean(order.client_order_id)||clean(order.auth_no)||clean(order.id)};if(Object.entries(values).some(([key,value])=>current[key]!==value)){job.petpooja={...values,updatedAt:new Date().toISOString()};saveJob(job);changed++;}}
      state.status='connected';state.lastSync=new Date().toISOString();state.orderCount=orders.length;state.lastMessage=`Connected · ${orders.length} active orders${changed?` · ${changed} updated`:''}`;
    }catch(error){state.status='error';state.lastMessage=error.name==='TimeoutError'?'Petpooja server did not answer on port 3000.':error.message;}finally{running=false;}
  }
  function start(){if(timer)return;void sync();timer=setInterval(sync,3000);timer.unref();}function refresh(){void sync();}return {state,start,refresh};
}
