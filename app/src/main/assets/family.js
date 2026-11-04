const $=id=>document.getElementById(id);
const shareToken=new URLSearchParams(location.search).get('token')||'';
let refreshing=false, submitting=false, lastState=null, linkValid=!!shareToken;
let connectionFailures=0, stateVersion=0;

function duration(ms){
  const seconds=Math.ceil(Math.max(0,ms)/1000);
  return String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');
}
async function familyApi(path,body){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),body?45000:12000);
  const options={headers:{'X-Family-Key':shareToken},cache:'no-store',signal:controller.signal};
  if(body){options.method='POST';options.headers['Content-Type']='application/json';options.body=JSON.stringify(body)}
  try{
    const response=await fetch(path,options);
    let result={};try{result=await response.json()}catch(error){if(error.name==='AbortError')throw error}
    if(!response.ok){const error=new Error(result.error||'连接失败');error.status=response.status;throw error}
    return result;
  }catch(error){if(error.name==='AbortError')throw new Error('请求超时，请检查连接');throw error}
  finally{clearTimeout(timer)}
}
function updateButtons(){
  const valid=linkValid;
  $('familyLock').disabled=!valid||submitting;
  $('familyPractice').disabled=!valid||submitting;
  $('familyRelease').disabled=!valid||submitting||!!(lastState&&lastState.releaseRemaining<=0);
}
function renderState(data){
  stateVersion++;connectionFailures=0;
  lastState=data;
  $('familyStatus').textContent=data.status||'状态暂不可用';
  $('familyRemaining').textContent=duration(data.remainingMs||0);
  $('familyConnection').textContent='已连接儿童手机';
  $('familyConnection').className='badge ok';

  const answered=Math.max(0,data.practiceAnswered||0);
  const complete=!!data.practiceComplete;
  const active=!!data.practiceEnabled&&!complete;
  $('familyPractice').textContent=active?'停止刷题':complete?'再刷一轮（50题）':answered?'继续刷题（50题）':'开始刷题（50题）';
  $('familyPracticeStatus').textContent=active?'刷题进行中 · '+answered+'/50':complete?'本轮刷题已完成 · 50/50':answered?'刷题已暂停 · '+answered+'/50':'刷题模式未开启';

  const remaining=Math.max(0,Number(data.releaseRemaining)||0);
  $('familyQuota').textContent=remaining>0?'今日家长放行剩余 '+remaining+' 次':'今日家长放行次数已用完';
  updateButtons();
}
function showUnavailable(message){
  $('familyConnection').textContent=message;
  $('familyConnection').className='badge';
}
async function refreshState(){
  if(!shareToken||!linkValid||refreshing||document.hidden)return;
  refreshing=true;
  const renderVersion=stateVersion;
  try{const data=await familyApi('/api/family/state');if(renderVersion===stateVersion)renderState(data)}
  catch(error){
    if(error.status===401){
      linkValid=false;
      showUnavailable('分享链接无效或已撤销');
      $('familyStatus').textContent='请联系家长重新分享';
      updateButtons();
    }else if(renderVersion===stateVersion){
      connectionFailures++;
      showUnavailable(connectionFailures<3?'正在重连':'暂时连不上，请确认使用同一 Wi-Fi');
    }
  }finally{refreshing=false}
}
async function runAction(action){
  if(submitting||!shareToken)return;
  let confirmText='';
  if(action==='lock')confirmText='立即在儿童手机显示答题页？';
  else if(action==='release')confirmText='家长放行当前答题？';
  else if(lastState&&lastState.practiceEnabled&&!lastState.practiceComplete)confirmText='停止当前刷题？';
  else confirmText=lastState&&lastState.practiceAnswered?'继续刷题？':'开始刷题（50题）？';
  if(!confirm(confirmText))return;
  submitting=true;updateButtons();
  $('familyFeedback').textContent='正在更新…';
  try{
    const state=await familyApi('/api/family/action',{action});
    renderState(state);$('familyFeedback').textContent='已更新';
  }catch(error){
    if(error.status===401)linkValid=false;
    $('familyFeedback').textContent=error.status===401?'分享链接无效或已撤销':error.message;
    if(error.status===401)updateButtons();
    if(error.status===429)await refreshState();
  }finally{
    submitting=false;updateButtons();
  }
}

if(!shareToken){
  showUnavailable('分享链接不完整');
  $('familyStatus').textContent='请联系家长重新分享';
  updateButtons();
}else{
  refreshState();
  setInterval(refreshState,5000);
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refreshState()});
}
$('familyLock').addEventListener('click',()=>runAction('lock'));
$('familyPractice').addEventListener('click',()=>runAction('practice'));
$('familyRelease').addEventListener('click',()=>runAction('release'));
